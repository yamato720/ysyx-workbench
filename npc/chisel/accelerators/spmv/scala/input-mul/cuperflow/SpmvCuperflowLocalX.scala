package accelerators.spmv.inputmul.cuperflow

import accelerators.spmv.SpmvCuperflowConfig
import accelerators.spmv.inputmul.common.SpmvCuperDecode
import chisel3._
import chisel3.util._
import npc.ip.memory.OnChipMaskedTrueDualPortMemory

/** 分组后的 dual-port packed 写命令，已与输入 token 解耦。 */
private final class SpmvCuperflowPackedXWrite(
  lineAddrWidth: Int,
  dataWidth: Int,
  maskWidth: Int
) extends Bundle {
  val write0 = Bool()
  val address0 = UInt(lineAddrWidth.W)
  val data0 = UInt(dataWidth.W)
  val mask0 = UInt(maskWidth.W)
  val write1 = Bool()
  val address1 = UInt(lineAddrWidth.W)
  val data1 = UInt(dataWidth.W)
  val mask1 = UInt(maskWidth.W)
  val loadBank = Bool()
}

/** 每个物理 URAM 一份 issued 写寄存器，避免 256-bit DIN 从同一拍跨 SLR 扇出。 */
private final class SpmvCuperflowIssuedXWriteStage(
  lineAddrWidth: Int,
  dataWidth: Int,
  maskWidth: Int
) extends Module {
  val io = IO(new Bundle {
    val inValid = Input(Bool())
    val in = Input(new SpmvCuperflowPackedXWrite(lineAddrWidth, dataWidth, maskWidth))
    val outValid = Output(Bool())
    val out = Output(new SpmvCuperflowPackedXWrite(lineAddrWidth, dataWidth, maskWidth))
  })

  private val valid = RegInit(false.B)
  private val bits = Reg(new SpmvCuperflowPackedXWrite(lineAddrWidth, dataWidth, maskWidth))
  valid := io.inValid
  when(io.inValid) {
    bits := io.in
  }
  io.outValid := valid
  io.out := bits
}

/** Cuperflow 的 packed local-X。
  *
  * 每个 replica 的容量为 8192 个 FP64，物理上组织为 2048 个 256-bit half-line。
  * 默认只实例化一套窗口，写完再读。`xPingPong` 打开后变成两套 bank：写入
  * inactive，`activate` 后切到 A 读口。
  *
  * marker 可能令一个 decoded beat 覆盖多个 line。输入 batch 被暂存后，每拍用两口最多
  * 提交两个不同 line 的 64-bit-lane masked write；连续流只占一个 line，保持 8 token/cycle。
  *
  * 分组后的 packed 写命令先锁一拍，再按每个物理 URAM 复制一拍后进入 DIN。
  */
final class SpmvCuperflowLocalX(config: SpmvCuperflowConfig) extends Module {
  private val replicaCount = config.xReplicaCount
  private val bankCount = config.xBankCount
  private val readPortCount = SpmvCuperDecode.lanesPerBeat
  private val readsPerReplica = readPortCount / replicaCount
  private val addressBits = log2Ceil(config.xWindowSize)
  private val tokensPerBeat = config.xWordsPerBeat
  private val elementsPerMemoryLine = config.xMemoryDataWidth / config.xElementWidth
  private val lineDepth = config.xWindowSize / elementsPerMemoryLine

  require(readsPerReplica == 2,
    s"Cuperflow 每份 X replica 必须为两个 FMUL lane 提供读口，实际为 $readsPerReplica")
  require(config.xWindowSize % elementsPerMemoryLine == 0,
    s"Cuperflow X window 必须按 local-X half-line 对齐，实际为 ${config.xWindowSize}/$elementsPerMemoryLine")
  require(elementsPerMemoryLine == 4,
    s"Cuperflow 当前 local-X half-line 必须包含 4 个 FP64，实际为 $elementsPerMemoryLine")
  require(bankCount == 1 || bankCount == 2,
    s"Cuperflow local-X 只支持单窗口或 ping/pong 双窗口，实际为 $bankCount")

  val io = IO(new Bundle {
    /** decoder 每拍最多交付八笔 value 写入；marker 已在 decoder 内消化。 */
    val write = Flipped(Decoupled(new SpmvCuperflowXWriteBatch(config)))
    /** pending 与 packed/issued 写流水都空时为真，方可开始 A 读或切 bank。 */
    val writeIdle = Output(Bool())
    /** 当前写入的 inactive ping/pong bank；单窗口时忽略。 */
    val loadBank = Input(Bool())
    /** 写排空后把 load bank 切成 A 的 active bank；单窗口时忽略。 */
    val activate = Input(Bool())
    val activeBank = Output(Bool())
    val readEnable = Input(Vec(readPortCount, Bool()))
    val readColumn = Input(Vec(readPortCount, UInt(addressBits.W)))
    val readData = Output(Vec(readPortCount, UInt(config.xElementWidth.W)))
  })

  private val activeBank = RegInit(false.B)
  private val pendingValid = RegInit(false.B)
  private val pendingWrite = Reg(new SpmvCuperflowXWriteBatch(config))
  private val pendingRemaining = RegInit(0.U(tokensPerBeat.W))
  private val memories = Seq.fill(bankCount) {
    Seq.fill(replicaCount)(Module(new OnChipMaskedTrueDualPortMemory(
      lineDepth, config.xMemoryDataWidth, elementsPerMemoryLine, config.xMemoryPrimitive)))
  }

  private val pendingLine = VecInit(pendingWrite.address.map(_ >> log2Ceil(elementsPerMemoryLine)))
  private val pendingElement = VecInit(pendingWrite.address.map(
    _(log2Ceil(elementsPerMemoryLine) - 1, 0)))
  private val groupFirst = VecInit((0 until tokensPerBeat).map { index =>
    val earlierSameLine = (0 until index).map { earlier =>
      pendingRemaining(earlier) && pendingLine(earlier) === pendingLine(index)
    }.foldLeft(false.B)(_ || _)
    pendingRemaining(index) && !earlierSameLine
  })
  private val firstGroupPresent = groupFirst.asUInt.orR
  private val firstGroupIndex = PriorityEncoder(groupFirst)
  private val firstGroupOneHot = UIntToOH(firstGroupIndex, tokensPerBeat) &
    Fill(tokensPerBeat, firstGroupPresent)
  private val secondGroupCandidates = groupFirst.asUInt & ~firstGroupOneHot
  private val secondGroupPresent = secondGroupCandidates.orR
  private val secondGroupIndex = PriorityEncoder(secondGroupCandidates)

  /** 返回本次物理 line 写消费的输入 token，供 pending batch 前移。 */
  private def groupInputMask(groupIndex: UInt, groupPresent: Bool): UInt = VecInit(
    (0 until tokensPerBeat).map { lane =>
      pendingRemaining(lane) && pendingLine(lane) === pendingLine(groupIndex) && groupPresent
    }
  ).asUInt

  /** 返回 packed line 的 64-bit lane 掩码，索引必须来自目标列号而非输入 token 序号。 */
  private def groupMemoryMask(groupIndex: UInt, groupPresent: Bool): UInt = VecInit(
    (0 until elementsPerMemoryLine).map { element =>
      (0 until tokensPerBeat).map { lane =>
        pendingRemaining(lane) && pendingLine(lane) === pendingLine(groupIndex) &&
          pendingElement(lane) === element.U && groupPresent
      }.foldLeft(false.B)(_ || _)
    }
  ).asUInt
  private def groupData(groupIndex: UInt): UInt = {
    val result = Wire(Vec(elementsPerMemoryLine, UInt(config.xElementWidth.W)))
    for (element <- 0 until elementsPerMemoryLine) {
      result(element) := 0.U
      // 按编码 token 的先后覆盖，保留 marker 回跳后同址 value 的串行语义。
      for (lane <- 0 until tokensPerBeat) {
        when(pendingRemaining(lane) && pendingLine(lane) === pendingLine(groupIndex) &&
            pendingElement(lane) === element.U) {
          result(element) := pendingWrite.data(lane)
        }
      }
    }
    result.asUInt
  }

  private val firstInputMask = groupInputMask(firstGroupIndex, firstGroupPresent)
  private val secondInputMask = groupInputMask(secondGroupIndex, secondGroupPresent)
  private val firstMemoryMask = groupMemoryMask(firstGroupIndex, firstGroupPresent)
  private val secondMemoryMask = groupMemoryMask(secondGroupIndex, secondGroupPresent)
  private val drainedMask = firstInputMask | secondInputMask
  private val remainingAfterWrite = pendingRemaining & ~drainedMask
  private val finishingPending = pendingValid && remainingAfterWrite === 0.U
  private val lineAddrWidth = log2Ceil(lineDepth)
  private val nextPacked = Wire(new SpmvCuperflowPackedXWrite(
    lineAddrWidth, config.xMemoryDataWidth, elementsPerMemoryLine))
  nextPacked.write0 := firstGroupPresent
  nextPacked.address0 := pendingLine(firstGroupIndex)
  nextPacked.data0 := groupData(firstGroupIndex)
  nextPacked.mask0 := firstMemoryMask
  nextPacked.write1 := secondGroupPresent
  nextPacked.address1 := pendingLine(secondGroupIndex)
  nextPacked.data1 := groupData(secondGroupIndex)
  nextPacked.mask1 := secondMemoryMask
  nextPacked.loadBank := io.loadBank

  private val packedValid = RegInit(false.B)
  private val packedCommand = Reg(new SpmvCuperflowPackedXWrite(
    lineAddrWidth, config.xMemoryDataWidth, elementsPerMemoryLine))
  packedValid := pendingValid && firstGroupPresent
  when(pendingValid && firstGroupPresent) {
    packedCommand := nextPacked
  }
  dontTouch(packedCommand)

  private val issuedStages = Seq.tabulate(bankCount) { bank =>
    Seq.tabulate(replicaCount) { replica =>
      val stage = Module(new SpmvCuperflowIssuedXWriteStage(
        lineAddrWidth, config.xMemoryDataWidth, elementsPerMemoryLine))
      if (config.xPingPong) {
        stage.suggestName(s"issuedWrite_b${bank}_r$replica")
      } else {
        stage.suggestName(s"issuedWrite_r$replica")
      }
      stage.io.inValid := packedValid
      stage.io.in := packedCommand
      dontTouch(stage.io.outValid)
      dontTouch(stage.io.out)
      stage
    }
  }
  private val issuedWriteBusy = packedValid ||
    issuedStages.flatten.map(_.io.outValid).reduce(_ || _)

  io.write.ready := !pendingValid || finishingPending
  io.writeIdle := !pendingValid && !issuedWriteBusy
  io.activeBank := activeBank
  if (config.xPingPong) {
    when(io.activate) {
      assert(!pendingValid && !issuedWriteBusy,
        "Cuperflow active bank switch requires an idle packed X writer")
      activeBank := io.loadBank
    }
  }
  when(pendingValid || issuedWriteBusy) {
    assert(!io.readEnable.asUInt.orR,
      "Cuperflow strict preload forbids A/local-X reads while packed X writer is active")
  }

  when(io.write.fire) {
    pendingWrite := io.write.bits
    pendingRemaining := io.write.bits.valid.asUInt
    pendingValid := io.write.bits.valid.asUInt.orR
  }.elsewhen(pendingValid) {
    pendingRemaining := remainingAfterWrite
    pendingValid := remainingAfterWrite.orR
  }

  for (bank <- 0 until bankCount) {
    for (replica <- 0 until replicaCount) {
      val lane0 = replica * readsPerReplica
      val lane1 = lane0 + 1
      val memory = memories(bank)(replica)
      val issuedValid = issuedStages(bank)(replica).io.outValid
      val issued = issuedStages(bank)(replica).io.out
      val isLoadBank = if (config.xPingPong) {
        if (bank == 0) !issued.loadBank else issued.loadBank
      } else {
        true.B
      }
      val isActiveBank = if (config.xPingPong) {
        if (bank == 0) !activeBank else activeBank
      } else {
        true.B
      }
      val write0 = issuedValid && issued.write0 && isLoadBank
      val write1 = issuedValid && issued.write1 && isLoadBank
      val read0 = isActiveBank && io.readEnable(lane0)
      val read1 = isActiveBank && io.readEnable(lane1)
      memory.io.a.enable := write0 || read0
      memory.io.a.write := write0
      memory.io.a.address := Mux(write0, issued.address0,
        io.readColumn(lane0) >> log2Ceil(elementsPerMemoryLine))
      memory.io.a.wdata := issued.data0
      memory.io.a.wmask := issued.mask0
      memory.io.b.enable := write1 || read1
      memory.io.b.write := write1
      memory.io.b.address := Mux(write1, issued.address1,
        io.readColumn(lane1) >> log2Ceil(elementsPerMemoryLine))
      memory.io.b.wdata := issued.data1
      memory.io.b.wmask := issued.mask1
    }
  }

  private val issuedActiveBank = RegNext(activeBank, false.B)
  private val issuedEnable = RegNext(io.readEnable, VecInit(Seq.fill(readPortCount)(false.B)))
  private val issuedElement = io.readColumn.map(column =>
    RegEnable(column(log2Ceil(elementsPerMemoryLine) - 1, 0), 0.U, true.B)
  )
  for (replica <- 0 until replicaCount) {
    val lane0 = replica * readsPerReplica
    val lane1 = lane0 + 1
    val lane0Line = WireDefault(0.U(config.xMemoryDataWidth.W))
    val lane1Line = WireDefault(0.U(config.xMemoryDataWidth.W))
    if (config.xPingPong) {
      for (bank <- 0 until bankCount) {
        val selected = if (bank == 0) !issuedActiveBank else issuedActiveBank
        when(selected) {
          lane0Line := memories(bank)(replica).io.a.rdata
          lane1Line := memories(bank)(replica).io.b.rdata
        }
      }
    } else {
      lane0Line := memories(0)(replica).io.a.rdata
      lane1Line := memories(0)(replica).io.b.rdata
    }
    val lane0Elements = lane0Line.asTypeOf(Vec(elementsPerMemoryLine, UInt(config.xElementWidth.W)))
    val lane1Elements = lane1Line.asTypeOf(Vec(elementsPerMemoryLine, UInt(config.xElementWidth.W)))
    io.readData(lane0) := Mux(issuedEnable(lane0), lane0Elements(issuedElement(lane0)), 0.U)
    io.readData(lane1) := Mux(issuedEnable(lane1), lane1Elements(issuedElement(lane1)), 0.U)
  }
}
