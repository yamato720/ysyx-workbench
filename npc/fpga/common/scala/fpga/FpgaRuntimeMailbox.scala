package fpga

import chisel3._
import chisel3.util._
import npc.protocol._

/** FPGA runtime control plane.
  *
  * `sdbEnabled=false` retains only the batch lifecycle registers required by
  * a performance-monitor host.  The interactive halt/step FSM and wide
  * architectural snapshots are then not synthesized.
  */
class FpgaRuntimeMailbox(width: Int, sdbEnabled: Boolean = true, cacheEnabled: Boolean = false) extends Module {
  require(width == 32 || width == 64)

  val io = IO(new Bundle {
    val axi = Flipped(new AxiLiteMasterIO(32, 32))
    val runtime = Input(new FpgaRuntimeDebug(width, sdbEnabled))
    val putch = Flipped(Decoupled(UInt(8.W)))
    val coreReset = Output(Bool())
    val dispatchPermit = Output(Bool())
    val memoryHostBase = Output(UInt(64.W))
    val traceHostBase = Output(UInt(64.W))
    val traceClear = Output(Bool())
    val traceClassSelector = Output(UInt(5.W))
    val traceStageSelector = Output(UInt(3.W))
    val traceStallSelector = Output(UInt(3.W))
    val trace = Input(new FpgaRuntimeTraceStatus)
    val cache = Input(new FpgaRuntimeCacheStatus)
    val guestExternalInterrupt = Output(Bool())
    val mailboxInterrupt = Output(Bool())
    val cacheDrainRequest = Output(Bool())
    val cacheDrained = Input(Bool())
    val memoryDrained = Input(Bool())
  })

  val coreReset = RegInit(true.B)
  val commandSequence = RegInit(0.U(32.W))
  val putchPending = RegInit(false.B)
  val putchData = RegInit(0.U(8.W))
  val completionPending = RegInit(false.B)
  val completionCode = RegInit(0.U(width.W))
  val completionPc = RegInit(0.U(width.W))
  val completionNextPc = RegInit(0.U(width.W))
  val completionDraining = RegInit(false.B)
  // mtestexit 后 core 会复位；缓存统计先复制到 mailbox，避免复位丢失。
  val completionCache = RegInit(0.U.asTypeOf(new FpgaRuntimeCacheStatus))
  val completionCacheValid = RegInit(false.B)
  val completionCacheSnapshotPending = RegInit(false.B)
  val completionGprs = if (sdbEnabled) Some(RegInit(VecInit(Seq.fill(32)(0.U(width.W))))) else None
  val guestExternalInterrupt = RegInit(false.B)
  val gprIndex = if (sdbEnabled) Some(RegInit(0.U(5.W))) else None
  val csrIndex = if (sdbEnabled) Some(RegInit(0.U(3.W))) else None
  val memoryHostBaseLow = RegInit(0.U(32.W))
  val memoryHostBaseHigh = RegInit(0.U(32.W))
  val traceHostBaseLow = RegInit(0.U(32.W))
  val traceHostBaseHigh = RegInit(0.U(32.W))
  val traceClassSelector = RegInit(0.U(5.W))
  val traceStageSelector = RegInit(0.U(3.W))
  val traceStallSelector = RegInit(0.U(3.W))
  val traceClear = WireDefault(false.B)

  val batchCommitCount = RegInit(0.U(64.W))
  val debugCommand = WireDefault(0.U.asTypeOf(Valid(new FpgaDebugCommand)))
  val resetControl = WireDefault(0.U.asTypeOf(Valid(new FpgaResetControl)))
  val debugDispatchPermit = WireDefault(!coreReset)
  val debugCompletedSequence = WireDefault(0.U(32.W))
  val debugProtocolError = WireDefault(false.B)
  val debugStepping = WireDefault(false.B)
  val debugHalting = WireDefault(false.B)
  val debugStableHalted = WireDefault(coreReset)
  val debugHalted = WireDefault(coreReset)
  val debugRunning = WireDefault(!coreReset)
  val debugStopReason = WireDefault(FpgaStopReason.none)
  val debugHaltPc = WireDefault(0.U(width.W))
  val debugHaltNextPc = WireDefault(0.U(width.W))
  val debugCommitCount = WireDefault(batchCommitCount)
  if (sdbEnabled) {
    val debugController = Module(new FpgaDebugController(width))
    debugController.io.runtime := io.runtime
    debugController.io.coreReset := coreReset
    debugController.io.command := debugCommand
    debugController.io.resetControl := resetControl
    debugDispatchPermit := debugController.io.dispatchPermit
    debugCompletedSequence := debugController.io.status.completedSequence
    debugProtocolError := debugController.io.status.protocolError
    debugStepping := debugController.io.status.stepping
    debugHalting := debugController.io.status.halting
    debugStableHalted := debugController.io.status.stableHalted
    debugHalted := debugController.io.status.halted
    debugRunning := debugController.io.status.running
    debugStopReason := debugController.io.status.stopReason
    debugHaltPc := debugController.io.status.haltPc
    debugHaltNextPc := debugController.io.status.haltNextPc
    debugCommitCount := debugController.io.status.commitCount
  }

  io.coreReset := coreReset
  io.dispatchPermit := debugDispatchPermit && !completionDraining && !completionCacheSnapshotPending
  io.cacheDrainRequest := completionDraining
  io.memoryHostBase := Cat(memoryHostBaseHigh, memoryHostBaseLow)
  io.traceHostBase := Cat(traceHostBaseHigh, traceHostBaseLow)
  io.traceClear := traceClear
  io.traceClassSelector := traceClassSelector
  io.traceStageSelector := traceStageSelector
  io.traceStallSelector := traceStallSelector
  io.putch.ready := !putchPending
  when(io.putch.fire) {
    putchPending := true.B
    putchData := io.putch.bits
  }
  io.guestExternalInterrupt := guestExternalInterrupt
  io.mailboxInterrupt := putchPending || completionPending
  when(io.runtime.commitValid && !coreReset) {
    batchCommitCount := batchCommitCount + 1.U
  }

  val awValid = RegInit(false.B)
  val awAddress = Reg(UInt(32.W))
  val wValid = RegInit(false.B)
  val wData = Reg(UInt(32.W))
  val wStrb = Reg(UInt(4.W))
  val bValid = RegInit(false.B)
  val rValid = RegInit(false.B)
  val rData = Reg(UInt(32.W))

  io.axi.aw.ready := !awValid
  io.axi.w.ready := !wValid
  io.axi.b.valid := bValid
  io.axi.b.bits.resp := AxiLiteResp.OKAY
  io.axi.ar.ready := !rValid
  io.axi.r.valid := rValid
  io.axi.r.bits.data := rData
  io.axi.r.bits.resp := AxiLiteResp.OKAY

  when(io.axi.aw.fire) {
    awValid := true.B
    awAddress := io.axi.aw.bits.addr
  }
  when(io.axi.w.fire) {
    wValid := true.B
    wData := io.axi.w.bits.data
    wStrb := io.axi.w.bits.strb
  }
  when(io.axi.b.fire) { bValid := false.B }

  def writeMasked(previous: UInt, next: UInt, strobe: UInt): UInt =
    VecInit((0 until 4).map(index => Mux(strobe(index),
      next(8 * index + 7, 8 * index), previous(8 * index + 7, 8 * index)))).asUInt

  val writeCommit = awValid && wValid && !bValid
  when(writeCommit) {
    // The U55C kernel exposes a 4 KiB AXI-Lite control aperture.  Runtime
    // trace registers live above 0x100, so decoding only the low byte would
    // alias them with the legacy mailbox map and make trace BO setup inert.
    switch(awAddress(11, 0)) {
      is("h40".U) { commandSequence := writeMasked(commandSequence, wData, wStrb) }
      is("h44".U) {
        if (sdbEnabled) {
          when(wStrb(0)) {
            debugCommand.valid := true.B
            debugCommand.bits.sequence := commandSequence
            debugCommand.bits.operation := wData(2, 0)
          }
        }
      }
      is("h80".U) {
        when(wStrb(0)) {
          coreReset := wData(0)
          resetControl.valid := true.B
          resetControl.bits.asserted := wData(0)
          resetControl.bits.run := wData(1)
          when(wData(0) || wData(2)) { putchPending := false.B }
          when(wData(0) || wData(1) || wData(3)) {
            completionPending := false.B
            completionDraining := false.B
            completionCacheSnapshotPending := false.B
          }
          when(wData(0) || wData(1)) { traceClear := true.B }
          // An acknowledgement commonly carries CORE_RESET as well (0x9), so
          // only discard a completed cache snapshot for an actual re-arm.
          when(!wData(3) || wData(1)) { completionCacheValid := false.B }
          when(wData(0)) { batchCommitCount := 0.U }
        }
      }
      is("h70".U) { when(wStrb(0)) { guestExternalInterrupt := wData(0) } }
      is("h8c".U) { if (sdbEnabled) { when(wStrb(0)) { gprIndex.get := wData(4, 0) } } }
      is("h5c".U) { if (sdbEnabled) { when(wStrb(0)) { csrIndex.get := wData(2, 0) } } }
      is("hf0".U) { memoryHostBaseLow := writeMasked(memoryHostBaseLow, wData, wStrb) }
      is("hf4".U) { memoryHostBaseHigh := writeMasked(memoryHostBaseHigh, wData, wStrb) }
      is("h120".U) { traceHostBaseLow := writeMasked(traceHostBaseLow, wData, wStrb) }
      is("h124".U) { traceHostBaseHigh := writeMasked(traceHostBaseHigh, wData, wStrb) }
      is("h128".U) { when(wStrb(0)) { traceClassSelector := wData(4, 0) } }
      is("h12c".U) { when(wStrb(0)) { traceStageSelector := wData(2, 0) } }
      is("h160".U) { when(wStrb(0)) { traceStallSelector := wData(2, 0) } }
    }
    awValid := false.B
    wValid := false.B
    bValid := true.B
  }

  // mtestexit 提交后先停止新的 dispatch，等待外部 AXI 与 cache 维护完成，
  // 再产生 completion 并复位 core，避免把尚未返回的 HBM 事务留在卡上。
  val completionCommit = io.runtime.completionCommitValid
  when(completionCommit && !coreReset && !completionPending && !completionDraining) {
    completionCode := io.runtime.completionCode
    completionPc := io.runtime.completionCommitPc
    completionNextPc := io.runtime.completionCommitNextPc
    if (sdbEnabled) { completionGprs.get := io.runtime.sdb.get.gprs }
    completionDraining := true.B
  }

  when(completionDraining && io.memoryDrained &&
      (!cacheEnabled.B || io.cacheDrained) && !completionCacheSnapshotPending) {
    if (cacheEnabled) {
      // 延迟一拍再采样，确保 drain 应答边沿产生的最后一次 writeback 计数已可见。
      completionCacheSnapshotPending := true.B
    } else {
      completionDraining := false.B
      completionPending := true.B
      coreReset := true.B
    }
  }

  when(cacheEnabled.B && completionCacheSnapshotPending) {
    completionCache := io.cache
    completionCacheValid := true.B
    completionCacheSnapshotPending := false.B
    completionDraining := false.B
    completionPending := true.B
    coreReset := true.B
  }

  def low(value: UInt): UInt = value(31, 0)
  def high(value: UInt): UInt = if (width == 64) value(63, 32) else 0.U(32.W)
  def cachePolicy(configuration: FpgaCacheConfiguration): UInt = Cat(
    0.U(23.W), configuration.storage, configuration.writeMiss,
    configuration.writePolicy, configuration.readMiss, configuration.replacement,
    configuration.mapping
  )
  val visibleCache = WireDefault(io.cache)
  when(completionCacheValid) { visibleCache := completionCache }
  val selectedCsr = WireDefault(0.U(width.W))
  val selectedGpr = WireDefault(0.U(width.W))
  val sdbMstatus = WireDefault(0.U(width.W))
  val sdbCurrentPc = WireDefault(0.U(width.W))
  val sdbFrontendInstruction = WireDefault(0.U(32.W))
  val sdbBackpressureReasons = WireDefault(0.U(9.W))
  if (sdbEnabled) {
    val sdb = io.runtime.sdb.get
    selectedCsr := MuxLookup(csrIndex.get, 0.U(width.W))(Seq(
      0.U -> sdb.mstatus,
      1.U -> sdb.mcause,
      2.U -> sdb.mepc,
      3.U -> sdb.mtvec,
      4.U -> sdb.nextArchitecturalPc
    ))
    selectedGpr := Mux(completionPending, completionGprs.get(gprIndex.get), sdb.gprs(gprIndex.get))
    sdbMstatus := sdb.mstatus
    sdbCurrentPc := sdb.currentPc
    sdbFrontendInstruction := sdb.frontendInstruction
    sdbBackpressureReasons := sdb.backpressureReasons
  }
  def readRegister(address: UInt): UInt = MuxLookup(address(11, 0), 0.U(32.W))(Seq(
    "h3c".U -> (if (sdbEnabled) 7.U(32.W) else 0.U(32.W)),
    "h40".U -> commandSequence,
    "h48".U -> debugCompletedSequence,
    "h4c".U -> Cat(0.U(26.W), debugProtocolError, coreReset,
      debugStepping, debugHalting, debugStableHalted, debugRunning),
    "h50".U -> low(debugHaltNextPc),
    "h54".U -> high(debugHaltNextPc),
    "h58".U -> Cat(0.U(28.W), debugStopReason),
    "h5c".U -> Cat(0.U(29.W), if (sdbEnabled) csrIndex.get else 0.U(3.W)),
    "h60".U -> low(completionPc),
    "h64".U -> high(completionPc),
    "h68".U -> low(completionNextPc),
    "h6c".U -> high(completionNextPc),
    "h70".U -> Cat(0.U(31.W), guestExternalInterrupt),
    "h80".U -> Cat(0.U(31.W), coreReset),
    "h84".U -> Cat(0.U(27.W), completionPending, debugProtocolError, putchPending,
      debugStableHalted, debugRunning),
    "h88".U -> Cat(2.U(8.W), (if (sdbEnabled) 7.U(8.W) else 0.U(8.W)),
      (width == 64).B, 0.U(7.W), width.U(8.W)),
    "h8c".U -> Cat(0.U(27.W), if (sdbEnabled) gprIndex.get else 0.U(5.W)),
    "h90".U -> low(selectedGpr),
    "h94".U -> high(selectedGpr),
    "h98".U -> 0.U,
    "h9c".U -> 0.U,
    "ha0".U -> 0.U,
    "ha4".U -> low(sdbMstatus),
    "ha8".U -> high(sdbMstatus),
    "hac".U -> low(sdbCurrentPc),
    "hb0".U -> high(sdbCurrentPc),
    "hb4".U -> low(Mux(debugHalted, debugHaltPc, io.runtime.commitPc)),
    "hb8".U -> high(Mux(debugHalted, debugHaltPc, io.runtime.commitPc)),
    "hbc".U -> io.runtime.commitInstruction,
    "hc0".U -> low(Mux(debugHalted, debugHaltNextPc, io.runtime.commitNextPc)),
    "hc4".U -> high(Mux(debugHalted, debugHaltNextPc, io.runtime.commitNextPc)),
    "hc8".U -> io.runtime.cycleCount(31, 0),
    "hcc".U -> io.runtime.cycleCount(63, 32),
    "hd0".U -> debugCommitCount(31, 0),
    "hd4".U -> debugCommitCount(63, 32),
    "hd8".U -> low(completionCode),
    "hdc".U -> high(completionCode),
    "he0".U -> Cat(0.U(23.W), sdbBackpressureReasons),
    "he4".U -> sdbFrontendInstruction,
    "he8".U -> Cat(0.U(24.W), putchData),
    "hec".U -> low(selectedCsr),
    "hf0".U -> memoryHostBaseLow,
    "hf4".U -> memoryHostBaseHigh,
    "hf8".U -> high(selectedCsr),
    "hfc".U -> "h4e504306".U,
    "h100".U -> Cat(0.U(31.W), io.trace.enabled),
    "h104".U -> io.trace.formatVersion,
    "h108".U -> io.trace.recordBytes,
    "h10c".U -> io.trace.maxRecords,
    "h110".U -> io.trace.records(31, 0),
    "h114".U -> io.trace.records(63, 32),
    "h118".U -> io.trace.dropped(31, 0),
    "h11c".U -> io.trace.dropped(63, 32),
    "h120".U -> traceHostBaseLow,
    "h124".U -> traceHostBaseHigh,
    "h128".U -> Cat(0.U(27.W), traceClassSelector),
    "h12c".U -> Cat(0.U(29.W), traceStageSelector),
    "h130".U -> io.trace.classSampleCount(31, 0),
    "h134".U -> io.trace.classSampleCount(63, 32),
    "h138".U -> io.trace.classStageTotal(31, 0),
    "h13c".U -> io.trace.classStageTotal(63, 32),
    "h140".U -> io.trace.classMaxTotal(31, 0),
    "h144".U -> io.trace.classMaxTotal(63, 32),
    "h148".U -> io.trace.classLastPc(31, 0),
    "h14c".U -> io.trace.classLastPc(63, 32),
    "h150".U -> io.trace.classLastInstruction,
    "h154".U -> io.trace.classLastStage(31, 0),
    "h158".U -> io.trace.classLastStage(63, 32),
    "h15c".U -> Cat(0.U(27.W), io.trace.lastClass),
    "h160".U -> Cat(0.U(29.W), traceStallSelector),
    "h164".U -> io.trace.selectedStall(31, 0),
    "h168".U -> io.trace.selectedStall(63, 32),
    "h16c".U -> io.trace.cycles(31, 0),
    "h170".U -> io.trace.cycles(63, 32),
    "h174".U -> io.trace.commits(31, 0),
    "h178".U -> io.trace.commits(63, 32),
    "h17c".U -> Cat(0.U(29.W), io.trace.pipelineFeatures),
    "h180".U -> Cat(0.U(30.W), io.trace.drained, io.trace.enabled),
    "h184".U -> io.trace.lastTotal(31, 0),
    "h188".U -> io.trace.lastTotal(63, 32),
    // Cache monitoring is mailbox-only: it leaves the fixed v13 HBM trace
    // record untouched while exposing the elaborated geometry and exact
    // whole-run counters to the remote NEMU report.
    "h190".U -> Cat(0.U(29.W), visibleCache.instructionBufferEnabled,
      visibleCache.data.configuration.enabled, visibleCache.instruction.configuration.enabled),
    "h194".U -> visibleCache.instructionBufferEntries,
    "h198".U -> visibleCache.instruction.configuration.capacityBytes,
    "h19c".U -> visibleCache.instruction.configuration.lineBytes,
    "h1a0".U -> visibleCache.instruction.configuration.ways,
    "h1a4".U -> visibleCache.instruction.configuration.sets,
    "h1a8".U -> cachePolicy(visibleCache.instruction.configuration),
    "h1ac".U -> visibleCache.instruction.statistics.hits(31, 0),
    "h1b0".U -> visibleCache.instruction.statistics.hits(63, 32),
    "h1b4".U -> visibleCache.instruction.statistics.misses(31, 0),
    "h1b8".U -> visibleCache.instruction.statistics.misses(63, 32),
    "h1bc".U -> visibleCache.instruction.statistics.refills(31, 0),
    "h1c0".U -> visibleCache.instruction.statistics.refills(63, 32),
    "h1c4".U -> visibleCache.instruction.statistics.writebacks(31, 0),
    "h1c8".U -> visibleCache.instruction.statistics.writebacks(63, 32),
    "h1cc".U -> visibleCache.instruction.statistics.evictions(31, 0),
    "h1d0".U -> visibleCache.instruction.statistics.evictions(63, 32),
    "h1d4".U -> visibleCache.data.configuration.capacityBytes,
    "h1d8".U -> visibleCache.data.configuration.lineBytes,
    "h1dc".U -> visibleCache.data.configuration.ways,
    "h1e0".U -> visibleCache.data.configuration.sets,
    "h1e4".U -> cachePolicy(visibleCache.data.configuration),
    "h1e8".U -> visibleCache.data.statistics.hits(31, 0),
    "h1ec".U -> visibleCache.data.statistics.hits(63, 32),
    "h1f0".U -> visibleCache.data.statistics.misses(31, 0),
    "h1f4".U -> visibleCache.data.statistics.misses(63, 32),
    "h1f8".U -> visibleCache.data.statistics.refills(31, 0),
    "h1fc".U -> visibleCache.data.statistics.refills(63, 32),
    "h200".U -> visibleCache.data.statistics.writebacks(31, 0),
    "h204".U -> visibleCache.data.statistics.writebacks(63, 32),
    "h208".U -> visibleCache.data.statistics.evictions(31, 0),
    "h20c".U -> visibleCache.data.statistics.evictions(63, 32)
  ))

  when(io.axi.ar.fire) {
    rData := readRegister(io.axi.ar.bits.addr)
    rValid := true.B
  }.elsewhen(io.axi.r.fire) {
    rValid := false.B
  }
}
