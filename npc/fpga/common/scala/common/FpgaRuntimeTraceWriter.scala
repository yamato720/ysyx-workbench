package npc.fpga

import chisel3._
import chisel3.util._
import npc.protocol.Axi4FullMasterIO

/** Snapshot and classify all committed instructions without feeding any ready
  * signal back into the core.  The module lives outside the core reset domain;
  * a host start command explicitly clears it, while mtestexit preserves the
  * final counters for mailbox reads.
  */
class FpgaRuntimeStatistics(width: Int, maxRecords: Int) extends Module {
  require(width == 32 || width == 64)
  require(maxRecords > 0)

  val io = IO(new Bundle {
    val runtime = Input(new FpgaRuntimeDebug(width))
    val coreReset = Input(Bool())
    val clear = Input(Bool())
    val classSelector = Input(UInt(5.W))
    val stageSelector = Input(UInt(3.W))
    val stallSelector = Input(UInt(3.W))
    val status = Output(new FpgaRuntimeTraceStatus)
  })

  val counts = RegInit(VecInit(Seq.fill(FpgaRuntimeTrace.classCount)(0.U(64.W))))
  val totals = RegInit(VecInit(Seq.fill(FpgaRuntimeTrace.classCount)(
    VecInit(Seq.fill(FpgaRuntimeTrace.stageCount)(0.U(64.W))))))
  val maximums = RegInit(VecInit(Seq.fill(FpgaRuntimeTrace.classCount)(0.U(64.W))))
  val lastPc = RegInit(VecInit(Seq.fill(FpgaRuntimeTrace.classCount)(0.U(64.W))))
  val lastInstruction = RegInit(VecInit(Seq.fill(FpgaRuntimeTrace.classCount)(0.U(32.W))))
  val lastStages = RegInit(VecInit(Seq.fill(FpgaRuntimeTrace.classCount)(
    VecInit(Seq.fill(FpgaRuntimeTrace.stageCount)(0.U(64.W))))))
  val lastClass = RegInit(0.U(5.W))
  val lastStage = RegInit(VecInit(Seq.fill(FpgaRuntimeTrace.stageCount)(0.U(64.W))))
  val storedCycles = RegInit(0.U(64.W))
  val storedStalls = RegInit(VecInit(Seq.fill(FpgaRuntimeTrace.stallCount)(0.U(64.W))))
  val storedPipelineFeatures = RegInit(0.U(3.W))

  val instruction = io.runtime.sampleCommitInstruction
  val opcode = instruction(6, 0)
  val funct3 = instruction(14, 12)
  val funct7 = instruction(31, 25)
  val isLoad = opcode === "b0000011".U
  val isStore = opcode === "b0100011".U
  val isM = (opcode === "b0110011".U || opcode === "b0111011".U) && funct7 === 1.U
  val matches = Wire(Vec(FpgaRuntimeTrace.classCount, Bool()))
  matches.foreach(_ := false.B)
  val detailClass = WireDefault(0.U(5.W))

  when(isLoad) {
    matches(1) := true.B
    switch(funct3) {
      is(0.U) { matches(4) := true.B; detailClass := 4.U }
      is(1.U) { matches(5) := true.B; detailClass := 5.U }
      is(2.U) { matches(6) := true.B; detailClass := 6.U }
      is(3.U) { matches(7) := true.B; detailClass := 7.U }
      is(4.U) { matches(8) := true.B; detailClass := 8.U }
      is(5.U) { matches(9) := true.B; detailClass := 9.U }
      is(6.U) { matches(10) := true.B; detailClass := 10.U }
    }
  }.elsewhen(isStore) {
    matches(2) := true.B
    switch(funct3) {
      is(0.U) { matches(11) := true.B; detailClass := 11.U }
      is(1.U) { matches(12) := true.B; detailClass := 12.U }
      is(2.U) { matches(13) := true.B; detailClass := 13.U }
      is(3.U) { matches(14) := true.B; detailClass := 14.U }
    }
  }.elsewhen(isM) {
    matches(3) := true.B
    when(opcode === "b0111011".U) {
      switch(funct3) {
        is(0.U) { matches(23) := true.B; detailClass := 23.U }
        is(4.U) { matches(24) := true.B; detailClass := 24.U }
        is(5.U) { matches(25) := true.B; detailClass := 25.U }
        is(6.U) { matches(26) := true.B; detailClass := 26.U }
        is(7.U) { matches(27) := true.B; detailClass := 27.U }
      }
    }.otherwise {
      switch(funct3) {
        is(0.U) { matches(15) := true.B; detailClass := 15.U }
        is(1.U) { matches(16) := true.B; detailClass := 16.U }
        is(2.U) { matches(17) := true.B; detailClass := 17.U }
        is(3.U) { matches(18) := true.B; detailClass := 18.U }
        is(4.U) { matches(19) := true.B; detailClass := 19.U }
        is(5.U) { matches(20) := true.B; detailClass := 20.U }
        is(6.U) { matches(21) := true.B; detailClass := 21.U }
        is(7.U) { matches(22) := true.B; detailClass := 22.U }
      }
    }
  }.otherwise {
    matches(0) := true.B
  }
  matches(29) := true.B

  val stages = Wire(Vec(FpgaRuntimeTrace.stageCount, UInt(64.W)))
  stages(0) := io.runtime.sampleFetchCycles
  stages(1) := io.runtime.sampleDecodeCycles
  stages(2) := io.runtime.sampleExecuteCycles
  stages(3) := io.runtime.sampleMemoryCycles
  stages(4) := io.runtime.sampleWritebackCycles
  val total = stages.foldLeft(0.U(64.W)) { case (sum, stage) => (sum +& stage)(63, 0) }

  when(io.clear) {
    counts.foreach(_ := 0.U)
    totals.foreach(_.foreach(_ := 0.U))
    maximums.foreach(_ := 0.U)
    lastPc.foreach(_ := 0.U)
    lastInstruction.foreach(_ := 0.U)
    lastStages.foreach(_.foreach(_ := 0.U))
    lastClass := 0.U
    lastStage.foreach(_ := 0.U)
    storedCycles := 0.U
    storedStalls.foreach(_ := 0.U)
    storedPipelineFeatures := 0.U
  }.otherwise {
    when(!io.coreReset) {
      storedCycles := io.runtime.cycleCount
      storedPipelineFeatures := io.runtime.pipelineFeatures
      storedStalls(0) := io.runtime.fetchAxiWaitCycles
      storedStalls(1) := io.runtime.idStallCycles
      storedStalls(2) := io.runtime.executeStallCycles
      storedStalls(3) := io.runtime.memoryStallCycles
      storedStalls(4) := io.runtime.redirectFlushCount
    }
    when(io.runtime.sampleCommitValid) {
      for (index <- 0 until FpgaRuntimeTrace.classCount) {
        when(matches(index)) {
          counts(index) := counts(index) + 1.U
          maximums(index) := Mux(total > maximums(index), total, maximums(index))
          lastPc(index) := io.runtime.sampleCommitPc
          lastInstruction(index) := instruction
          for (stage <- 0 until FpgaRuntimeTrace.stageCount) {
            totals(index)(stage) := totals(index)(stage) + stages(stage)
            lastStages(index)(stage) := stages(stage)
          }
        }
      }
      lastClass := detailClass
      for (stage <- 0 until FpgaRuntimeTrace.stageCount) lastStage(stage) := stages(stage)
    }
  }

  val selectedClass = Mux(io.classSelector < FpgaRuntimeTrace.classCount.U,
    io.classSelector, 0.U)
  val selectedStage = Mux(io.stageSelector < FpgaRuntimeTrace.stageCount.U,
    io.stageSelector, 0.U)
  val selectedStall = Mux(io.stallSelector < FpgaRuntimeTrace.stallCount.U,
    io.stallSelector, 0.U)
  io.status.enabled := true.B
  io.status.formatVersion := FpgaRuntimeTrace.formatVersion.U
  io.status.recordBytes := FpgaRuntimeTrace.recordBytes.U
  io.status.maxRecords := maxRecords.U
  io.status.records := 0.U
  io.status.dropped := 0.U
  io.status.drained := true.B
  io.status.cycles := storedCycles
  io.status.commits := counts(29)
  io.status.pipelineFeatures := storedPipelineFeatures
  io.status.classSampleCount := counts(selectedClass)
  io.status.classStageTotal := totals(selectedClass)(selectedStage)
  io.status.classMaxTotal := maximums(selectedClass)
  io.status.classLastPc := lastPc(selectedClass)
  io.status.classLastInstruction := lastInstruction(selectedClass)
  io.status.classLastStage := lastStages(selectedClass)(selectedStage)
  io.status.lastClass := lastClass
  io.status.lastStage := lastStage(selectedStage)
  io.status.lastTotal := lastStage.foldLeft(0.U(64.W)) { case (sum, stage) => (sum +& stage)(63, 0) }
  io.status.selectedStall := storedStalls(selectedStall)
}

/** A bounded, non-blocking trace writer.
  *
  * The core-side producer writes one 72-byte commit record per cycle into a
  * SyncReadMem FIFO.  The U55C packager applies `RAM_STYLE=ultra` to its
  * stable generated name.  The AXI writer drains it independently; a full FIFO only
  * increments `dropped` and never exerts backpressure on the CPU.
  */
class FpgaRuntimeTraceWriter(
  width: Int,
  idWidth: Int,
  maxRecords: Int,
  cacheRecords: Int
) extends Module {
  require(width == 32 || width == 64)
  require(maxRecords > 0)
  require(cacheRecords >= 2 && (cacheRecords & (cacheRecords - 1)) == 0,
    s"trace cache depth must be a power of two, got $cacheRecords")
  private val wordsPerRecord = FpgaRuntimeTrace.recordBytes / (width / 8)
  private val recordBits = FpgaRuntimeTrace.recordBytes * 8
  private val pointerBits = log2Ceil(cacheRecords)
  private val occupancyBits = log2Ceil(cacheRecords + 1)

  val io = IO(new Bundle {
    val runtime = Input(new FpgaRuntimeDebug(width))
    val traceBase = Input(UInt(64.W))
    val clear = Input(Bool())
    val axi = new Axi4FullMasterIO(64, width, idWidth)
    val records = Output(UInt(64.W))
    val dropped = Output(UInt(64.W))
    val drained = Output(Bool())
  })

  val idle :: readRecord :: captureRead :: sendAddress :: sendData :: waitResponse :: Nil = Enum(6)
  val state = RegInit(idle)
  val captured = RegInit(0.U(64.W))
  val completed = RegInit(0.U(64.W))
  val dropped = RegInit(0.U(64.W))
  val captureStopped = RegInit(false.B)
  val beat = RegInit(0.U(log2Ceil(wordsPerRecord).W))
  val writePointer = RegInit(0.U(pointerBits.W))
  val readPointer = RegInit(0.U(pointerBits.W))
  val occupancy = RegInit(0.U(occupancyBits.W))
  val traceMemory = SyncReadMem(cacheRecords, UInt(recordBits.W))
  traceMemory.suggestName("trace_uram_fifo")
  val activeRecord = Reg(UInt(recordBits.W))

  val statusWord = Cat(0.U(29.W), io.runtime.pipelineFeatures, io.runtime.sampleCommitInstruction)
  val incomingRecord = Cat(
    io.runtime.sampleWritebackCycles,
    io.runtime.sampleMemoryCycles,
    io.runtime.sampleExecuteCycles,
    io.runtime.sampleDecodeCycles,
    io.runtime.sampleFetchCycles,
    io.runtime.cycleCount,
    statusWord,
    io.runtime.sampleCommitPc.pad(64),
    captured + 1.U
  )
  val dequeue = state === waitResponse && io.axi.b.fire
  val cacheHasRoom = occupancy < cacheRecords.U || dequeue
  val canCapture = io.traceBase =/= 0.U && !captureStopped &&
    captured < maxRecords.U && cacheHasRoom
  val enqueue = io.runtime.sampleCommitValid && canCapture
  val readData = traceMemory.read(readPointer, state === readRecord)
  val recordWords = Wire(Vec(wordsPerRecord, UInt(width.W)))
  recordWords := activeRecord.asTypeOf(Vec(wordsPerRecord, UInt(width.W)))
  val recordAddress = io.traceBase + completed * FpgaRuntimeTrace.recordBytes.U +
    beat * (width / 8).U

  io.axi.aw.valid := state === sendAddress
  io.axi.aw.bits.id := 0.U
  io.axi.aw.bits.addr := recordAddress
  io.axi.aw.bits.len := 0.U
  io.axi.aw.bits.size := log2Ceil(width / 8).U
  io.axi.aw.bits.burst := 1.U
  io.axi.aw.bits.lock := 0.U
  io.axi.aw.bits.cache := 0.U
  io.axi.aw.bits.prot := 0.U
  io.axi.aw.bits.qos := 0.U
  io.axi.w.valid := state === sendData
  io.axi.w.bits.data := recordWords(beat)
  io.axi.w.bits.strb := Fill(width / 8, 1.U(1.W))
  io.axi.w.bits.last := true.B
  io.axi.b.ready := state === waitResponse && !io.clear
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
    completed := 0.U
    dropped := 0.U
    captureStopped := false.B
    beat := 0.U
    writePointer := 0.U
    readPointer := 0.U
    occupancy := 0.U
  }.otherwise {
    when(io.runtime.sampleCommitValid) {
      when(enqueue) {
        traceMemory.write(writePointer, incomingRecord)
        writePointer := writePointer + 1.U
        captured := captured + 1.U
      }.elsewhen(io.traceBase =/= 0.U) {
        // The HTML trace is intentionally a prefix.  Once the bounded FIFO
        // cannot accept a commit, stop recording permanently while counters
        // continue to observe every later commit without stalling the core.
        captureStopped := true.B
        dropped := dropped + 1.U
      }
    }

    switch(Cat(enqueue, dequeue)) {
      is("b10".U) { occupancy := occupancy + 1.U }
      is("b01".U) { occupancy := occupancy - 1.U }
    }

    when(state === idle && occupancy =/= 0.U) {
      state := readRecord
    }
    when(state === readRecord) {
      state := captureRead
    }
    when(state === captureRead) {
      activeRecord := readData
      beat := 0.U
      state := sendAddress
    }
    when(io.axi.aw.fire) { state := sendData }
    when(io.axi.w.fire) { state := waitResponse }
    when(io.axi.b.fire) {
      when(beat === (wordsPerRecord - 1).U) {
        completed := completed + 1.U
        readPointer := readPointer + 1.U
        state := idle
      }.otherwise {
        beat := beat + 1.U
        state := sendAddress
      }
    }
  }
  io.records := completed
  io.dropped := dropped
  io.drained := state === idle && occupancy === 0.U
}
