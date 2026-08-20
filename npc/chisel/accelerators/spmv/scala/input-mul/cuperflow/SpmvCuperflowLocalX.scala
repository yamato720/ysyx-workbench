package accelerators.spmv.inputmul.cuperflow

import accelerators.spmv.SpmvCuperflowConfig
import accelerators.spmv.inputmul.common.SpmvCuperDecode
import chisel3._
import chisel3.util._
import npc.ip.memory.OnChipMaskedTrueDualPortMemory

/** 一个连续 X payload beat。`validElements` 只在最后一拍小于八。 */
final class SpmvCuperflowXLoadBeat(config: SpmvCuperflowConfig) extends Bundle {
  val data = UInt(config.axiDataWidth.W)
  val validElements = UInt(log2Ceil(config.xWordsPerBeat + 1).W)
}

/** 一拍连续搬运产生的两条相邻 256-bit half-line 写命令。 */
private final class SpmvCuperflowSequentialXWrite(
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

/** 每个物理 URAM 一份 issued 写寄存器，隔离 256-bit DIN 的跨区域扇出。 */
private final class SpmvCuperflowIssuedXWriteStage(
  lineAddrWidth: Int,
  dataWidth: Int,
  maskWidth: Int
) extends Module {
  val io = IO(new Bundle {
    val inValid = Input(Bool())
    val in = Input(new SpmvCuperflowSequentialXWrite(lineAddrWidth, dataWidth, maskWidth))
    val outValid = Output(Bool())
    val out = Output(new SpmvCuperflowSequentialXWrite(lineAddrWidth, dataWidth, maskWidth))
  })

  private val valid = RegInit(false.B)
  private val bits = Reg(new SpmvCuperflowSequentialXWrite(lineAddrWidth, dataWidth, maskWidth))
  valid := io.inValid
  when(io.inValid) {
    bits := io.in
  }
  io.outValid := valid
  io.out := bits
}

/** Cuperflow 的顺序装填 local-X。
  *
  * 一个 map 定义单段连续 X span。payload 是纯 FP64 value 流，因此每个 512-bit beat
  * 固定映射为相邻两条 256-bit half-line：低四项写 A 口，高四项写 B 口。没有地址
  * marker、动态分组或 PriorityEncoder；连续输入以 II=1 原样复制进每份 local-X。
  */
final class SpmvCuperflowLocalX(config: SpmvCuperflowConfig) extends Module {
  private val replicaCount = config.xReplicaCount
  private val bankCount = config.xBankCount
  private val readPortCount = SpmvCuperDecode.lanesPerBeat
  private val readsPerReplica = readPortCount / replicaCount
  private val addressBits = log2Ceil(config.xWindowSize)
  private val loadAddressBits = log2Ceil(config.xWindowSize + 1)
  private val elementsPerMemoryLine = config.xMemoryDataWidth / config.xElementWidth
  private val lineDepth = config.xWindowSize / elementsPerMemoryLine
  private val lineAddrWidth = log2Ceil(lineDepth)

  require(readsPerReplica == 2,
    s"Cuperflow 每份 X replica 必须为两个 FMUL lane 提供读口，实际为 $readsPerReplica")
  require(config.xWordsPerBeat == 2 * elementsPerMemoryLine,
    s"Cuperflow 一个 X beat 必须恰好覆盖两条 local-X half-line，实际为 ${config.xWordsPerBeat}/$elementsPerMemoryLine")
  require(config.xWindowSize % elementsPerMemoryLine == 0,
    s"Cuperflow X window 必须按 local-X half-line 对齐，实际为 ${config.xWindowSize}/$elementsPerMemoryLine")
  require(elementsPerMemoryLine == 4,
    s"Cuperflow 当前 local-X half-line 必须包含 4 个 FP64，实际为 $elementsPerMemoryLine")
  require(bankCount == 1 || bankCount == 2,
    s"Cuperflow local-X 只支持单窗口或 ping/pong 双窗口，实际为 $bankCount")

  val io = IO(new Bundle {
    /** 一个连续 span 开始时将顺序写指针重置到 local-X[0]。 */
    val clearLoad = Input(Bool())
    /** 每拍一个原始 512-bit HBM X beat，不经过 marker decoder。 */
    val write = Flipped(Decoupled(new SpmvCuperflowXLoadBeat(config)))
    /** issued 写级排空时为真，方可开始 A 读或切 bank。 */
    val writeIdle = Output(Bool())
    /** 当前写入的 inactive ping/pong bank；单窗口时忽略。 */
    val loadBank = Input(Bool())
    /** 写排空后把 load bank 切成 A 的 active bank；单窗口时忽略。 */
    val activate = Input(Bool())
    val activeBank = Output(Bool())
    val error = Output(Bool())
    val readEnable = Input(Vec(readPortCount, Bool()))
    val readColumn = Input(Vec(readPortCount, UInt(addressBits.W)))
    val readData = Output(Vec(readPortCount, UInt(config.xElementWidth.W)))
  })

  private val activeBank = RegInit(false.B)
  private val loadElement = RegInit(0.U(loadAddressBits.W))
  private val error = RegInit(false.B)
  private val words = io.write.bits.data.asTypeOf(
    Vec(config.xWordsPerBeat, UInt(config.xElementWidth.W)))
  private val validElements = io.write.bits.validElements
  private val validCount = validElements =/= 0.U && validElements <= config.xWordsPerBeat.U
  private val loadEnd = loadElement +& validElements
  private val loadInRange = validCount && loadEnd <= config.xWindowSize.U

  private val nextWrite = Wire(new SpmvCuperflowSequentialXWrite(
    lineAddrWidth, config.xMemoryDataWidth, elementsPerMemoryLine))
  private val firstLine = loadElement >> log2Ceil(elementsPerMemoryLine)
  nextWrite.write0 := loadInRange
  nextWrite.address0 := firstLine
  nextWrite.data0 := VecInit((0 until elementsPerMemoryLine).map(words)).asUInt
  nextWrite.mask0 := VecInit((0 until elementsPerMemoryLine).map { element =>
    element.U < validElements
  }).asUInt
  nextWrite.write1 := loadInRange && validElements > elementsPerMemoryLine.U
  nextWrite.address1 := firstLine + 1.U
  nextWrite.data1 := VecInit((elementsPerMemoryLine until config.xWordsPerBeat).map(words)).asUInt
  nextWrite.mask1 := VecInit((0 until elementsPerMemoryLine).map { element =>
    (element + elementsPerMemoryLine).U < validElements
  }).asUInt
  nextWrite.loadBank := io.loadBank

  private val issuedStages = Seq.tabulate(bankCount) { bank =>
    Seq.tabulate(replicaCount) { replica =>
      val stage = Module(new SpmvCuperflowIssuedXWriteStage(
        lineAddrWidth, config.xMemoryDataWidth, elementsPerMemoryLine))
      if (config.xPingPong) {
        stage.suggestName(s"issuedSequentialWrite_b${bank}_r$replica")
      } else {
        stage.suggestName(s"issuedSequentialWrite_r$replica")
      }
      stage.io.inValid := io.write.fire && loadInRange
      stage.io.in := nextWrite
      dontTouch(stage.io.outValid)
      dontTouch(stage.io.out)
      stage
    }
  }
  private val issuedWriteBusy = issuedStages.flatten.map(_.io.outValid).reduce(_ || _)

  io.write.ready := !io.clearLoad
  io.writeIdle := !io.write.valid && !issuedWriteBusy
  io.activeBank := activeBank
  io.error := error
  if (config.xPingPong) {
    when(io.activate) {
      assert(!io.write.valid && !issuedWriteBusy,
        "Cuperflow active bank switch requires an idle sequential X writer")
      activeBank := io.loadBank
    }
  }
  when(io.clearLoad) {
    assert(!io.write.valid && !issuedWriteBusy,
      "Cuperflow X span starts only after the preceding sequential writer drains")
    loadElement := 0.U
    error := false.B
  }.elsewhen(io.write.fire) {
    when(!loadInRange) {
      error := true.B
    }.otherwise {
      loadElement := loadEnd(loadAddressBits - 1, 0)
    }
  }
  when(io.write.valid || issuedWriteBusy) {
    assert(!io.readEnable.asUInt.orR,
      "Cuperflow strict preload forbids A/local-X reads while sequential X writer is active")
  }

  private val memories = Seq.fill(bankCount) {
    Seq.fill(replicaCount)(Module(new OnChipMaskedTrueDualPortMemory(
      lineDepth, config.xMemoryDataWidth, elementsPerMemoryLine, config.xMemoryPrimitive)))
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
