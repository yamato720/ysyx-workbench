package accelerators.spmv.inputmul.cuperflow

import accelerators.spmv.SpmvCuperflowConfig
import accelerators.spmv.inputmul.common.SpmvCuperDecode
import chisel3._
import chisel3.util._
import npc.ip.memory.{OnChipMaskedTrueDualPortMemory, OnChipMemoryPrimitive}

/** Cuperflow 的 `2 x 4` packed local-X。
  *
  * 每个 replica 的容量仍为 8192 个 FP64，但物理上组织为 1024 个 512-bit line。一条
  * line 正好对应输入 X beat 的八个 FP64；因此连续 X 可在一拍广播写入四份 replica。
  * A 阶段每份 replica 的两个真双口分别返回两个任意 line，并由列号低三位选出 FP64。
  *
  * marker 可能令一个 decoded beat 覆盖多个 line。输入 batch 被暂存后，每拍用两口最多
  * 提交两个不同 line 的 64-bit-lane masked write；连续流只占一个 line，保持 8 token/cycle。
  */
final class SpmvCuperflowLocalX(config: SpmvCuperflowConfig) extends Module {
  private val replicaCount = config.xReplicaCount
  private val readPortCount = SpmvCuperDecode.lanesPerBeat
  private val readsPerReplica = readPortCount / replicaCount
  private val addressBits = log2Ceil(config.xWindowSize)
  private val elementsPerLine = config.xWordsPerBeat
  private val lineDepth = config.xWindowSize / elementsPerLine

  require(readsPerReplica == 2,
    s"Cuperflow 每份 X replica 必须为两个 FMUL lane 提供读口，实际为 $readsPerReplica")
  require(config.xWindowSize % elementsPerLine == 0,
    s"Cuperflow X window 必须按 512-bit line 对齐，实际为 ${config.xWindowSize}/$elementsPerLine")

  val io = IO(new Bundle {
    /** decoder 每拍最多交付八笔 value 写入；marker 已在 decoder 内消化。 */
    val write = Flipped(Decoupled(new SpmvCuperflowXWriteBatch(config)))
    /** 所有 decoded write 已落入 inactive bank 时为真，方可切换 active bank。 */
    val writeIdle = Output(Bool())
    /** 当前 work 写入的 inactive ping/pong bank。 */
    val loadBank = Input(Bool())
    /** X writer 清空后，将 load bank 原子切为 A 阶段的 active bank。 */
    val activate = Input(Bool())
    val activeBank = Output(Bool())
    val readEnable = Input(Vec(readPortCount, Bool()))
    val readColumn = Input(Vec(readPortCount, UInt(addressBits.W)))
    val readData = Output(Vec(readPortCount, UInt(config.xElementWidth.W)))
  })

  private val activeBank = RegInit(false.B)
  private val pendingValid = RegInit(false.B)
  private val pendingWrite = Reg(new SpmvCuperflowXWriteBatch(config))
  private val pendingRemaining = RegInit(0.U(elementsPerLine.W))
  private val memories = Seq.fill(2) {
    Seq.fill(replicaCount)(Module(new OnChipMaskedTrueDualPortMemory(
      lineDepth, config.axiDataWidth, elementsPerLine, OnChipMemoryPrimitive.UltraRam)))
  }

  private val pendingLine = VecInit(pendingWrite.address.map(_ >> log2Ceil(elementsPerLine)))
  private val pendingElement = VecInit(pendingWrite.address.map(
    _(log2Ceil(elementsPerLine) - 1, 0)))
  private val groupFirst = VecInit((0 until elementsPerLine).map { index =>
    val earlierSameLine = (0 until index).map { earlier =>
      pendingRemaining(earlier) && pendingLine(earlier) === pendingLine(index)
    }.foldLeft(false.B)(_ || _)
    pendingRemaining(index) && !earlierSameLine
  })
  private val firstGroupPresent = groupFirst.asUInt.orR
  private val firstGroupIndex = PriorityEncoder(groupFirst)
  private val firstGroupOneHot = UIntToOH(firstGroupIndex, elementsPerLine) &
    Fill(elementsPerLine, firstGroupPresent)
  private val secondGroupCandidates = groupFirst.asUInt & ~firstGroupOneHot
  private val secondGroupPresent = secondGroupCandidates.orR
  private val secondGroupIndex = PriorityEncoder(secondGroupCandidates)

  /** 返回本次物理 line 写消费的输入 token，供 pending batch 前移。 */
  private def groupInputMask(groupIndex: UInt, groupPresent: Bool): UInt = VecInit(
    (0 until elementsPerLine).map { lane =>
      pendingRemaining(lane) && pendingLine(lane) === pendingLine(groupIndex) && groupPresent
    }
  ).asUInt

  /** 返回 packed line 的 64-bit lane 掩码，索引必须来自目标列号而非输入 token 序号。 */
  private def groupMemoryMask(groupIndex: UInt, groupPresent: Bool): UInt = VecInit(
    (0 until elementsPerLine).map { element =>
      (0 until elementsPerLine).map { lane =>
        pendingRemaining(lane) && pendingLine(lane) === pendingLine(groupIndex) &&
          pendingElement(lane) === element.U && groupPresent
      }.foldLeft(false.B)(_ || _)
    }
  ).asUInt
  private def groupData(groupIndex: UInt): UInt = {
    val result = Wire(Vec(elementsPerLine, UInt(config.xElementWidth.W)))
    for (element <- 0 until elementsPerLine) {
      result(element) := 0.U
      // 按编码 token 的先后覆盖，保留 marker 回跳后同址 value 的串行语义。
      for (lane <- 0 until elementsPerLine) {
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

  io.write.ready := !pendingValid || finishingPending
  io.writeIdle := !pendingValid
  io.activeBank := activeBank
  when(io.activate) {
    assert(!pendingValid, "Cuperflow active bank switch requires an idle packed X writer")
    activeBank := io.loadBank
  }
  when(pendingValid) {
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

  for (pingPong <- 0 until 2) {
    val physicalBankSelected = if (pingPong == 0) !activeBank else activeBank
    val isLoadBank = if (pingPong == 0) !io.loadBank else io.loadBank
    for (replica <- 0 until replicaCount) {
      val lane0 = replica * readsPerReplica
      val lane1 = lane0 + 1
      val memory = memories(pingPong)(replica)
      val write0 = pendingValid && firstGroupPresent && isLoadBank
      val write1 = pendingValid && secondGroupPresent && isLoadBank
      val read0 = physicalBankSelected && io.readEnable(lane0)
      val read1 = physicalBankSelected && io.readEnable(lane1)
      memory.io.a.enable := write0 || read0
      memory.io.a.write := write0
      memory.io.a.address := Mux(write0, pendingLine(firstGroupIndex),
        io.readColumn(lane0) >> log2Ceil(elementsPerLine))
      memory.io.a.wdata := groupData(firstGroupIndex)
      memory.io.a.wmask := firstMemoryMask
      memory.io.b.enable := write1 || read1
      memory.io.b.write := write1
      memory.io.b.address := Mux(write1, pendingLine(secondGroupIndex),
        io.readColumn(lane1) >> log2Ceil(elementsPerLine))
      memory.io.b.wdata := groupData(secondGroupIndex)
      memory.io.b.wmask := secondMemoryMask
    }
  }

  private val issuedActiveBank = RegNext(activeBank, false.B)
  private val issuedEnable = RegNext(io.readEnable, VecInit(Seq.fill(readPortCount)(false.B)))
  private val issuedElement = io.readColumn.map(column =>
    RegEnable(column(log2Ceil(elementsPerLine) - 1, 0), 0.U, true.B)
  )
  for (replica <- 0 until replicaCount) {
    val lane0 = replica * readsPerReplica
    val lane1 = lane0 + 1
    val lane0Line = WireDefault(0.U(config.axiDataWidth.W))
    val lane1Line = WireDefault(0.U(config.axiDataWidth.W))
    for (pingPong <- 0 until 2) {
      val selected = if (pingPong == 0) !issuedActiveBank else issuedActiveBank
      when(selected) {
        lane0Line := memories(pingPong)(replica).io.a.rdata
        lane1Line := memories(pingPong)(replica).io.b.rdata
      }
    }
    val lane0Elements = lane0Line.asTypeOf(Vec(elementsPerLine, UInt(config.xElementWidth.W)))
    val lane1Elements = lane1Line.asTypeOf(Vec(elementsPerLine, UInt(config.xElementWidth.W)))
    io.readData(lane0) := Mux(issuedEnable(lane0), lane0Elements(issuedElement(lane0)), 0.U)
    io.readData(lane1) := Mux(issuedEnable(lane1), lane1Elements(issuedElement(lane1)), 0.U)
  }
}
