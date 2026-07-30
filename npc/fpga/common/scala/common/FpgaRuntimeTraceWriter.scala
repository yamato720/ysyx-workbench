package npc.fpga

import chisel3._
import chisel3.util._
import npc.protocol.Axi4FullMasterIO

class FpgaMonitorSample(width: Int) extends Bundle {
  val valid = Bool()
  val pc = UInt(width.W)
  val instruction = UInt(32.W)
  val cycle = UInt(64.W)
  val stages = Vec(FpgaPerformanceMonitor.stageCount, UInt(64.W))
  val pipelineFeatures = UInt(3.W)
}

class FpgaMonitorClassified(width: Int) extends Bundle {
  val valid = Bool()
  val classes = Vec(3, UInt(5.W))
  val classValid = Vec(3, Bool())
  val pc = UInt(width.W)
  val instruction = UInt(32.W)
  val stages = Vec(FpgaPerformanceMonitor.stageCount, UInt(64.W))
  val total = UInt(64.W)
}

/** Exact aggregate counters for the batch monitor.
  *
  * The old monitor carried a 30-way match vector and all debug state through
  * several wide stages.  This version has one commit sample stage followed by
  * three concrete class updates (major class, instruction detail, all).  The
  * aggregate counters remain exact; per-class "last instruction" data is
  * reconstructed from the HBM records by the host, while this module only
  * retains the globally last committed sample.
  */
class FpgaRuntimeStatistics(width: Int, maxRecords: Int) extends Module {
  require(width == 32 || width == 64)
  require(maxRecords > 0)

  val io = IO(new Bundle {
    val telemetry = Input(new FpgaCommitTelemetry(width))
    val clear = Input(Bool())
    val classSelector = Input(UInt(5.W))
    val stageSelector = Input(UInt(3.W))
    val stallSelector = Input(UInt(3.W))
    val status = Output(new FpgaRuntimeTraceStatus)
  })

  val counts = RegInit(VecInit(Seq.fill(FpgaPerformanceMonitor.classCount)(0.U(64.W))))
  val totals = RegInit(VecInit(Seq.fill(FpgaPerformanceMonitor.classCount)(
    VecInit(Seq.fill(FpgaPerformanceMonitor.stageCount)(0.U(64.W))))))
  val maximums = RegInit(VecInit(Seq.fill(FpgaPerformanceMonitor.classCount)(0.U(64.W))))
  val lastPc = RegInit(0.U(64.W))
  val lastInstruction = RegInit(0.U(32.W))
  val lastClass = RegInit(0.U(5.W))
  val lastStages = RegInit(VecInit(Seq.fill(FpgaPerformanceMonitor.stageCount)(0.U(64.W))))
  val storedCycles = RegInit(0.U(64.W))
  val storedStalls = RegInit(VecInit(Seq.fill(FpgaPerformanceMonitor.stallCount)(0.U(64.W))))
  val storedPipelineFeatures = RegInit(0.U(3.W))

  val sample = RegInit(0.U.asTypeOf(new FpgaMonitorSample(width)))
  val classified = RegInit(0.U.asTypeOf(new FpgaMonitorClassified(width)))

  def add64(left: UInt, right: UInt): UInt = (left +& right)(63, 0)

  def classify(instruction: UInt): (UInt, Bool, UInt, Bool) = {
    val opcode = instruction(6, 0)
    val funct3 = instruction(14, 12)
    val funct7 = instruction(31, 25)
    val major = WireDefault(0.U(5.W))
    val detail = WireDefault(0.U(5.W))
    val hasDetail = WireDefault(false.B)
    val isLoad = opcode === "b0000011".U
    val isStore = opcode === "b0100011".U
    val isM = (opcode === "b0110011".U || opcode === "b0111011".U) && funct7 === 1.U
    when(isLoad) {
      major := 1.U
      hasDetail := true.B
      detail := MuxLookup(funct3, 4.U)(Seq(
        0.U -> 4.U, 1.U -> 5.U, 2.U -> 6.U, 3.U -> 7.U,
        4.U -> 8.U, 5.U -> 9.U, 6.U -> 10.U
      ))
    }.elsewhen(isStore) {
      major := 2.U
      hasDetail := true.B
      detail := MuxLookup(funct3, 11.U)(Seq(
        0.U -> 11.U, 1.U -> 12.U, 2.U -> 13.U, 3.U -> 14.U
      ))
    }.elsewhen(isM) {
      major := 3.U
      hasDetail := true.B
      detail := MuxLookup(funct3, 15.U)(Seq(
        0.U -> 15.U, 1.U -> 16.U, 2.U -> 17.U, 3.U -> 18.U,
        4.U -> 19.U, 5.U -> 20.U, 6.U -> 21.U, 7.U -> 22.U
      ))
      when(opcode === "b0111011".U) {
        detail := MuxLookup(funct3, 23.U)(Seq(
          0.U -> 23.U, 4.U -> 24.U, 5.U -> 25.U, 6.U -> 26.U, 7.U -> 27.U
        ))
      }
    }
    (major, hasDetail, detail, isLoad || isStore || isM)
  }

  val selectedClass = Mux(io.classSelector < FpgaPerformanceMonitor.classCount.U,
    io.classSelector, 0.U)
  val selectedStage = Mux(io.stageSelector < FpgaPerformanceMonitor.stageCount.U,
    io.stageSelector, 0.U)
  val selectedStall = Mux(io.stallSelector < FpgaPerformanceMonitor.stallCount.U,
    io.stallSelector, 0.U)

  when(io.clear) {
    counts.foreach(_ := 0.U)
    totals.foreach(_.foreach(_ := 0.U))
    maximums.foreach(_ := 0.U)
    lastPc := 0.U
    lastInstruction := 0.U
    lastClass := 0.U
    lastStages.foreach(_ := 0.U)
    storedCycles := 0.U
    storedStalls.foreach(_ := 0.U)
    storedPipelineFeatures := 0.U
    sample.valid := false.B
    classified.valid := false.B
  }.otherwise {
    sample.valid := io.telemetry.commitValid
    when(io.telemetry.commitValid) {
      sample.pc := io.telemetry.commitPc
      sample.instruction := io.telemetry.commitInstruction
      sample.cycle := io.telemetry.commitCycle
      sample.stages := io.telemetry.stages
      sample.pipelineFeatures := io.telemetry.pipelineFeatures
    }

    classified.valid := sample.valid
    when(sample.valid) {
      val (major, hasDetail, detail, _) = classify(sample.instruction)
      classified.classes(0) := major
      classified.classValid(0) := true.B
      classified.classes(1) := detail
      classified.classValid(1) := hasDetail
      classified.classes(2) := (FpgaPerformanceMonitor.classCount - 1).U
      classified.classValid(2) := true.B
      classified.pc := sample.pc
      classified.instruction := sample.instruction
      classified.stages := sample.stages
      classified.total := sample.stages.foldLeft(0.U(64.W)) {
        case (sum, stage) => add64(sum, stage)
      }
    }

    when(classified.valid) {
      for (lane <- 0 until 3) {
        when(classified.classValid(lane)) {
          val index = classified.classes(lane)
          counts(index) := counts(index) + 1.U
          maximums(index) := Mux(classified.total > maximums(index),
            classified.total, maximums(index))
          for (stage <- 0 until FpgaPerformanceMonitor.stageCount) {
            totals(index)(stage) := add64(totals(index)(stage), classified.stages(stage))
          }
        }
      }
      lastPc := classified.pc.pad(64)
      lastInstruction := classified.instruction
      lastClass := classified.classes(1)
      lastStages := classified.stages
    }

    // These counters are sampled exactly on the completion commit before the
    // core reset domain clears its backend state.  Aggregate event stages keep
    // draining independently afterwards.
    when(io.telemetry.completionValid) {
      storedCycles := io.telemetry.completionCycle
      storedStalls := io.telemetry.completionStalls
      storedPipelineFeatures := io.telemetry.pipelineFeatures
    }
  }

  io.status.enabled := true.B
  io.status.formatVersion := FpgaPerformanceMonitor.formatVersion.U
  io.status.recordBytes := FpgaPerformanceMonitor.recordBytes.U
  io.status.maxRecords := maxRecords.U
  io.status.records := 0.U
  io.status.dropped := 0.U
  io.status.drained := !sample.valid && !classified.valid
  io.status.cycles := storedCycles
  io.status.commits := counts(FpgaPerformanceMonitor.classCount - 1)
  io.status.pipelineFeatures := storedPipelineFeatures
  io.status.classSampleCount := counts(selectedClass)
  io.status.classStageTotal := totals(selectedClass)(selectedStage)
  io.status.classMaxTotal := maximums(selectedClass)
  io.status.classLastPc := Mux(selectedClass === lastClass, lastPc, 0.U)
  io.status.classLastInstruction := Mux(selectedClass === lastClass, lastInstruction, 0.U)
  io.status.classLastStage := Mux(selectedClass === lastClass, lastStages(selectedStage), 0.U)
  io.status.lastClass := lastClass
  io.status.lastStage := lastStages(selectedStage)
  io.status.lastTotal := lastStages.foldLeft(0.U(64.W)) { case (sum, stage) => add64(sum, stage) }
  io.status.selectedStall := storedStalls(selectedStall)
}

/** Non-blocking, 256-bit HBM writer for the batch performance-monitor ABI.
  *
  * A trace record is exactly one AXI beat.  The writer emits contiguous
  * bursts and keeps up to four responses in flight, so B-channel latency does
  * not serialize producer throughput.  A full FIFO turns the detail stream
  * into a prefix and increments `dropped`; it never backpressures the CPU.
  */
class FpgaRuntimeTraceWriter(
  width: Int,
  idWidth: Int,
  maxRecords: Int,
  cacheRecords: Int,
  burstRecords: Int
) extends Module {
  require(width == 32 || width == 64)
  require(maxRecords > 0)
  require(cacheRecords >= 2 && (cacheRecords & (cacheRecords - 1)) == 0)
  require(burstRecords >= 2 && (burstRecords & (burstRecords - 1)) == 0)

  private val traceWidth = FpgaPerformanceMonitor.traceDataWidth
  private val burstCountWidth = log2Ceil(burstRecords + 1)
  private val pointerWidth = log2Ceil(cacheRecords)
  private val occupancyWidth = log2Ceil(cacheRecords + 1)
  private val maxOutstanding = 4

  val io = IO(new Bundle {
    val telemetry = Input(new FpgaCommitTelemetry(width))
    val traceBase = Input(UInt(64.W))
    val clear = Input(Bool())
    val axi = new Axi4FullMasterIO(64, traceWidth, idWidth)
    val records = Output(UInt(64.W))
    val dropped = Output(UInt(64.W))
    val drained = Output(Bool())
  })

  val idle :: sendAddress :: sendData :: Nil = Enum(3)
  val state = RegInit(idle)
  val captured = RegInit(0.U(64.W))
  val written = RegInit(0.U(64.W))
  val dropped = RegInit(0.U(64.W))
  val captureStopped = RegInit(false.B)
  val captureFinished = RegInit(false.B)
  val writePointer = RegInit(0.U(pointerWidth.W))
  val readPointer = RegInit(0.U(pointerWidth.W))
  val occupancy = RegInit(0.U(occupancyWidth.W))
  val burstLength = RegInit(0.U(burstCountWidth.W))
  val burstSent = RegInit(0.U(burstCountWidth.W))
  val burstReadsIssued = RegInit(0.U(burstCountWidth.W))
  val outstanding = RegInit(0.U(3.W))
  val readPending = RegInit(false.B)
  val buffered0Valid = RegInit(false.B)
  val buffered1Valid = RegInit(false.B)
  val buffered0 = Reg(UInt(traceWidth.W))
  val buffered1 = Reg(UInt(traceWidth.W))
  val traceMemory = SyncReadMem(cacheRecords, UInt(traceWidth.W))
  traceMemory.suggestName("performance_monitor_uram_fifo")

  def packedStage(value: UInt): UInt = Mux(value(63, 16).orR, "hffff".U(16.W), value(15, 0))
  val saturation = VecInit(io.telemetry.stages.map(_.asUInt(63, 16).orR)).asUInt
  val incomingRecord = Cat(
    saturation.pad(8),
    io.telemetry.pipelineFeatures.pad(8),
    packedStage(io.telemetry.stages(4)),
    packedStage(io.telemetry.stages(3)),
    packedStage(io.telemetry.stages(2)),
    packedStage(io.telemetry.stages(1)),
    packedStage(io.telemetry.stages(0)),
    io.telemetry.commitCycle,
    io.telemetry.commitInstruction,
    io.telemetry.commitPc.pad(64)
  )

  val canCapture = io.traceBase =/= 0.U && !captureStopped &&
    captured < maxRecords.U && occupancy < cacheRecords.U
  val enqueue = io.telemetry.commitValid && canCapture
  val startingBurst = state === idle && occupancy =/= 0.U && outstanding < maxOutstanding.U &&
    (occupancy >= burstRecords.U || captureFinished)
  val nextBurstLength = Mux(occupancy >= burstRecords.U, burstRecords.U, occupancy)
  val finalBeat = burstSent + 1.U === burstLength
  val dequeued = state === sendData && buffered0Valid && io.axi.w.ready
  val bufferOccupancy = buffered0Valid.asUInt +& buffered1Valid.asUInt
  val issueRead = state === sendData && burstReadsIssued < burstLength &&
    (bufferOccupancy +& readPending.asUInt - dequeued.asUInt < 2.U)
  val readData = traceMemory.read(readPointer, issueRead)
  val responseArrived = readPending

  io.axi.aw.valid := state === sendAddress
  io.axi.aw.bits.id := 0.U
  io.axi.aw.bits.addr := io.traceBase + (written * FpgaPerformanceMonitor.recordBytes.U)
  io.axi.aw.bits.len := burstLength - 1.U
  io.axi.aw.bits.size := log2Ceil(traceWidth / 8).U
  io.axi.aw.bits.burst := 1.U
  io.axi.aw.bits.lock := 0.U
  io.axi.aw.bits.cache := 0.U
  io.axi.aw.bits.prot := 0.U
  io.axi.aw.bits.qos := 0.U
  io.axi.w.valid := state === sendData && buffered0Valid
  io.axi.w.bits.data := buffered0
  io.axi.w.bits.strb := Fill(traceWidth / 8, 1.U(1.W))
  io.axi.w.bits.last := finalBeat
  io.axi.b.ready := !io.clear
  io.axi.ar.valid := false.B
  io.axi.ar.bits.id := 0.U
  io.axi.ar.bits.addr := 0.U
  io.axi.ar.bits.len := 0.U
  io.axi.ar.bits.size := 0.U
  io.axi.ar.bits.burst := 0.U
  io.axi.ar.bits.lock := 0.U
  io.axi.ar.bits.cache := 0.U
  io.axi.ar.bits.prot := 0.U
  io.axi.ar.bits.qos := 0.U
  io.axi.r.ready := false.B

  when(io.clear) {
    state := idle
    captured := 0.U
    written := 0.U
    dropped := 0.U
    captureStopped := false.B
    captureFinished := false.B
    writePointer := 0.U
    readPointer := 0.U
    occupancy := 0.U
    burstLength := 0.U
    burstSent := 0.U
    burstReadsIssued := 0.U
    outstanding := 0.U
    readPending := false.B
    buffered0Valid := false.B
    buffered1Valid := false.B
  }.otherwise {
    when(io.telemetry.completionValid) { captureFinished := true.B }
    when(io.telemetry.commitValid) {
      when(enqueue) {
        traceMemory.write(writePointer, incomingRecord)
        writePointer := writePointer + 1.U
        captured := captured + 1.U
      }.elsewhen(io.traceBase =/= 0.U) {
        captureStopped := true.B
        dropped := dropped + 1.U
      }
    }

    switch(Cat(enqueue, dequeued)) {
      is("b10".U) { occupancy := occupancy + 1.U }
      is("b01".U) { occupancy := occupancy - 1.U }
    }

    when(io.axi.b.fire) { outstanding := outstanding - 1.U }
    when(io.axi.aw.fire && !io.axi.b.fire) { outstanding := outstanding + 1.U }

    when(startingBurst) {
      burstLength := nextBurstLength
      burstSent := 0.U
      burstReadsIssued := 0.U
      state := sendAddress
    }
    when(io.axi.aw.fire) { state := sendData }

    // The two-entry output queue hides the synchronous URAM read latency.  A
    // response can refill it in the same cycle that AXI consumes its head.
    when(responseArrived && dequeued) {
      when(buffered1Valid) {
        buffered0 := buffered1
        buffered0Valid := true.B
        buffered1 := readData
        buffered1Valid := true.B
      }.otherwise {
        buffered0 := readData
        buffered0Valid := true.B
        buffered1Valid := false.B
      }
    }.elsewhen(responseArrived) {
      when(!buffered0Valid) {
        buffered0 := readData
        buffered0Valid := true.B
      }.otherwise {
        buffered1 := readData
        buffered1Valid := true.B
      }
    }.elsewhen(dequeued) {
      buffered0 := buffered1
      buffered0Valid := buffered1Valid
      buffered1Valid := false.B
    }
    readPending := issueRead
    when(issueRead) {
      readPointer := readPointer + 1.U
      burstReadsIssued := burstReadsIssued + 1.U
    }

    when(dequeued) {
      written := written + 1.U
      burstSent := burstSent + 1.U
      when(finalBeat) {
        state := idle
        burstSent := 0.U
        burstReadsIssued := 0.U
      }
    }
  }

  io.records := written
  io.dropped := dropped
  io.drained := captureFinished && state === idle && occupancy === 0.U &&
    outstanding === 0.U && !readPending && !buffered0Valid && !buffered1Valid
}
