package npc.fpga

import chisel3._
import org.chipsalliance.cde.config.Parameters
import npc.NpcCore

/** Board-neutral bare-core system; DDR/HBM address conversion belongs to board RTL. */
class NpcFpgaSystem(implicit parameters: Parameters) extends Module {
  private val config = FpgaConfigParameters.npcCoreConfig
  require(config.debug.enableTopDebugIo, "NpcFpgaSystem requires runtime debug signals")

  private val width = config.isa.xlen
  private val axiConfig = config.axi
  private val runtimeTrace = FpgaConfigParameters.runtimeTrace
  val io = IO(new FpgaSystemIO(axiConfig.addrWidth, axiConfig.dataWidth, axiConfig.idWidth,
    runtimeTrace.enabled))

  val mailbox = Module(new FpgaRuntimeMailbox(width))
  val runtime = Wire(new FpgaRuntimeDebug(width))
  mailbox.io.runtime := runtime
  val core = withReset(reset.asBool || mailbox.io.coreReset) {
    Module(new NpcCore(config, FpgaCoreComponents.forAttachment(FpgaConfigParameters.ipAttachment)))
  }
  core.io.interrupt := io.interrupt || mailbox.io.guestExternalInterrupt
  io.master <> core.io.master
  io.mailboxInterrupt := mailbox.io.mailboxInterrupt
  io.memoryHostBase := mailbox.io.memoryHostBase
  mailbox.io.putch <> core.io.putch.get

  val debug = core.io.debug.get
  runtime.currentPc := debug.frontend.currentPc
  runtime.nextArchitecturalPc := debug.frontend.nextArchitecturalPc
  runtime.frontendInstruction := debug.frontend.frontendInstruction
  runtime.commitValid := debug.backend.commitValid
  runtime.commitPc := debug.backend.commitPc
  runtime.commitInstruction := debug.backend.commitInstruction
  runtime.commitNextPc := debug.backend.commitNextPc
  runtime.sampleCommitValid := debug.backend.sampleCommitValid
  runtime.sampleCommitPc := debug.backend.sampleCommitPc
  runtime.sampleCommitInstruction := debug.backend.sampleCommitInstruction
  runtime.sampleCommitNextPc := debug.backend.sampleCommitNextPc
  runtime.sampleFetchCycles := debug.backend.sampleFetchCycles
  runtime.sampleDecodeCycles := debug.backend.sampleDecodeCycles
  runtime.sampleExecuteCycles := debug.backend.sampleExecuteCycles
  runtime.sampleMemoryCycles := debug.backend.sampleMemoryCycles
  runtime.sampleWritebackCycles := debug.backend.sampleWritebackCycles
  runtime.completionCommitValid := debug.backend.completionCommitValid
  runtime.completionCommitPc := debug.backend.completionCommitPc
  runtime.completionCommitNextPc := debug.backend.completionCommitNextPc
  runtime.cycleCount := debug.backend.cycleCount
  runtime.fcsr := debug.backend.fcsr
  runtime.mstatus := debug.backend.mstatus
  runtime.mcause := debug.backend.mcause
  runtime.mepc := debug.backend.mepc
  runtime.mtvec := debug.backend.mtvec
  runtime.coreBusy := debug.coreBusy
  runtime.dispatchFire := core.io.dispatchControl.get.dispatchFire
  core.io.dispatchControl.get.dispatchPermit := mailbox.io.dispatchPermit
  runtime.backpressureReasons := debug.backpressureReasons
  runtime.fetchAxiWaitCycles := debug.frontend.fetchAxiWaitCycles
  runtime.redirectFlushCount := debug.frontend.redirectFlushCount
  runtime.idStallCycles := debug.backend.idStallCycles
  runtime.executeStallCycles := debug.backend.executeStallCycles
  runtime.memoryStallCycles := debug.backend.memoryStallCycles
  runtime.pipelineFeatures := debug.backend.pipelineFeatures
  runtime.gprs := debug.backend.registers

  val traceStatus = WireDefault(0.U.asTypeOf(new FpgaRuntimeTraceStatus))
  mailbox.io.trace := traceStatus
  if (runtimeTrace.enabled) {
    val statistics = Module(new FpgaRuntimeStatistics(width, runtimeTrace.maxRecords))
    val writer = Module(new FpgaRuntimeTraceWriter(
      width, axiConfig.idWidth, runtimeTrace.maxRecords, runtimeTrace.cacheRecords))
    statistics.io.runtime := runtime
    statistics.io.coreReset := mailbox.io.coreReset
    statistics.io.clear := mailbox.io.traceClear
    statistics.io.classSelector := mailbox.io.traceClassSelector
    statistics.io.stageSelector := mailbox.io.traceStageSelector
    statistics.io.stallSelector := mailbox.io.traceStallSelector
    writer.io.runtime := runtime
    writer.io.traceBase := mailbox.io.traceHostBase
    writer.io.clear := mailbox.io.traceClear
    io.trace.get <> writer.io.axi
    traceStatus := statistics.io.status
    traceStatus.records := writer.io.records
    traceStatus.dropped := writer.io.dropped
    traceStatus.drained := writer.io.drained
  }

  mailbox.io.axi.aw.valid := io.control.aw.valid
  mailbox.io.axi.aw.bits := io.control.aw.bits
  io.control.aw.ready := mailbox.io.axi.aw.ready
  mailbox.io.axi.w.valid := io.control.w.valid
  mailbox.io.axi.w.bits := io.control.w.bits
  io.control.w.ready := mailbox.io.axi.w.ready
  io.control.b.valid := mailbox.io.axi.b.valid
  io.control.b.bits := mailbox.io.axi.b.bits
  mailbox.io.axi.b.ready := io.control.b.ready
  mailbox.io.axi.ar.valid := io.control.ar.valid
  mailbox.io.axi.ar.bits := io.control.ar.bits
  io.control.ar.ready := mailbox.io.axi.ar.ready
  io.control.r.valid := mailbox.io.axi.r.valid
  io.control.r.bits := mailbox.io.axi.r.bits
  mailbox.io.axi.r.ready := io.control.r.ready

}

/** Common shell for board-selected bare cores. */
abstract class NpcFpgaShell(board: FpgaBoard)(implicit parameters: Parameters) extends Module {
  private val config = FpgaConfigParameters.npcCoreConfig
  private val platform = FpgaConfigParameters.platform
  require(platform.board == board,
    s"elaboration selected ${platform.board.name}, but instantiated ${board.name} shell")
  require(FpgaConfigParameters.board.contains(board),
    s"CDE configuration selected ${FpgaConfigParameters.board.map(_.name).getOrElse("no board")}, but instantiated ${board.name} shell")
  override def desiredName: String = "NpcFpgaTop"

  private val axiConfig = config.axi
  private val runtimeTrace = FpgaConfigParameters.runtimeTrace
  val io = IO(new FpgaSystemIO(axiConfig.addrWidth, axiConfig.dataWidth, axiConfig.idWidth,
    runtimeTrace.enabled))
  private val system = Module(new NpcFpgaSystem)
  FpgaSystemIO.connect(io, system.io)
}

/** Compatibility entry point for older direct callers; it selects ZCU102. */
class NpcFpgaTop(implicit parameters: Parameters)
    extends NpcFpgaShell(FpgaBoard.Zcu102)
