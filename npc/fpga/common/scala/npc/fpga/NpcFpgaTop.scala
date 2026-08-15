package npc.fpga

import chisel3._
import org.chipsalliance.cde.config.Parameters
import _root_.fpga._
import npc.{CacheConfig, CacheMapping, CacheReadMissPolicy, CacheReplacement, CacheStorage, CacheWriteMissPolicy, CacheWritePolicy, NpcCore}

/** Board-neutral bare-core system; DDR/HBM address conversion belongs to board RTL. */
class NpcFpgaSystem(implicit parameters: Parameters) extends Module {
  private val config = FpgaConfigParameters.npcCoreConfig
  require(config.debug.enableTopDebugIo, "NpcFpgaSystem requires runtime debug signals")

  private val width = config.isa.xlen
  private val axiConfig = config.axi
  private val memoryDataWidth = config.memoryDataWidth
  private val performanceMonitor = FpgaConfigParameters.performanceMonitor
  private val runtimeSdb = FpgaConfigParameters.runtimeSdb
  val io = IO(new FpgaSystemIO(axiConfig.addrWidth, memoryDataWidth, axiConfig.idWidth,
    performanceMonitor.enabled, performanceMonitor.traceDataWidth))

  val mailbox = Module(new FpgaRuntimeMailbox(width, runtimeSdb.enabled, config.cache.dcache.enabled))
  val runtime = Wire(new FpgaRuntimeDebug(width, runtimeSdb.enabled))
  mailbox.io.runtime := runtime
  val core = withReset(reset.asBool || mailbox.io.coreReset) {
    Module(new NpcCore(config, FpgaCoreComponents.forAttachment(FpgaConfigParameters.ipAttachment)))
  }
  core.io.cacheMaintenance.foreach { maintenance =>
    maintenance.drainRequest := mailbox.io.cacheDrainRequest
    mailbox.io.cacheDrained := maintenance.drained
  }
  if (core.io.cacheMaintenance.isEmpty) {
    mailbox.io.cacheDrained := true.B
  }
  core.io.interrupt := io.interrupt || mailbox.io.guestExternalInterrupt
  io.master <> core.io.master
  io.mailboxInterrupt := mailbox.io.mailboxInterrupt
  io.memoryHostBase := mailbox.io.memoryHostBase
  mailbox.io.putch <> core.io.putch.get

  val debug = core.io.debug.get
  val cacheStatus = WireDefault(0.U.asTypeOf(new FpgaRuntimeCacheStatus))

  def mappingId(mapping: CacheMapping): Int = mapping match {
    case CacheMapping.DirectMapped => 0
    case _: CacheMapping.SetAssociative => 1
    case CacheMapping.FullyAssociative => 2
  }

  def replacementId(replacement: CacheReplacement): Int = replacement match {
    case CacheReplacement.LRU => 0
    case CacheReplacement.TreePLRU => 1
    case CacheReplacement.FIFO => 2
    case CacheReplacement.Random => 3
  }

  def readMissId(policy: CacheReadMissPolicy): Int = policy match {
    case CacheReadMissPolicy.ReadAllocate => 0
    case CacheReadMissPolicy.ReadBypass => 1
  }

  def writePolicyId(policy: CacheWritePolicy): Int = policy match {
    case CacheWritePolicy.WriteBack => 0
    case CacheWritePolicy.WriteThrough => 1
  }

  def writeMissId(policy: CacheWriteMissPolicy): Int = policy match {
    case CacheWriteMissPolicy.WriteAllocate => 0
    case CacheWriteMissPolicy.NoWriteAllocate => 1
  }

  def storageId(storage: CacheStorage): Int = storage match {
    case CacheStorage.Auto => 0
    case CacheStorage.Registers => 1
    case CacheStorage.Uram => 2
  }

  def connectCacheStatus(target: FpgaCacheStatus, source: CacheConfig): Unit = {
    target.configuration.enabled := source.enabled.B
    target.configuration.capacityBytes := source.geometry.capacityBytes.U
    target.configuration.lineBytes := source.geometry.lineBytes.U
    target.configuration.ways := source.geometry.ways.U
    target.configuration.sets := source.geometry.sets.U
    target.configuration.mapping := mappingId(source.geometry.mapping).U
    target.configuration.replacement := replacementId(source.replacement).U
    target.configuration.readMiss := readMissId(source.policy.readMiss).U
    target.configuration.writePolicy := writePolicyId(source.policy.write).U
    target.configuration.writeMiss := writeMissId(source.policy.writeMiss).U
    target.configuration.storage := storageId(source.storage).U
  }

  connectCacheStatus(cacheStatus.instruction, config.cache.icache)
  connectCacheStatus(cacheStatus.data, config.cache.dcache)
  cacheStatus.instruction.statistics := debug.cache.instruction
  cacheStatus.data.statistics := debug.cache.data
  cacheStatus.instructionBufferEnabled := config.cache.instructionBuffer.enabled.B
  cacheStatus.instructionBufferEntries := config.cache.instructionBuffer.entries.U
  mailbox.io.cache := cacheStatus

  runtime.commitValid := debug.backend.commitValid
  runtime.commitPc := debug.backend.commitPc
  runtime.commitInstruction := debug.backend.commitInstruction
  runtime.commitNextPc := debug.backend.commitNextPc
  runtime.completionCommitValid := debug.backend.completionCommitValid
  runtime.completionCommitPc := debug.backend.completionCommitPc
  runtime.completionCommitNextPc := debug.backend.completionCommitNextPc
  runtime.completionCode := debug.backend.registers(10)
  runtime.cycleCount := debug.backend.cycleCount
  core.io.dispatchControl.get.dispatchPermit := mailbox.io.dispatchPermit
  runtime.sdb.foreach { sdb =>
    sdb.currentPc := debug.frontend.currentPc
    sdb.nextArchitecturalPc := debug.frontend.nextArchitecturalPc
    sdb.frontendInstruction := debug.frontend.frontendInstruction
    sdb.fcsr := debug.backend.fcsr
    sdb.mstatus := debug.backend.mstatus
    sdb.mcause := debug.backend.mcause
    sdb.mepc := debug.backend.mepc
    sdb.mtvec := debug.backend.mtvec
    sdb.coreBusy := debug.backend.coreBusy
    sdb.dispatchFire := core.io.dispatchControl.get.dispatchFire
    sdb.backpressureReasons := debug.backpressureReasons
    sdb.gprs := debug.backend.registers
  }

  val telemetry = Wire(new FpgaCommitTelemetry(width))
  telemetry.commitValid := debug.backend.sampleCommitValid
  telemetry.commitPc := debug.backend.sampleCommitPc
  telemetry.commitInstruction := debug.backend.sampleCommitInstruction
  telemetry.commitCycle := debug.backend.cycleCount
  telemetry.stages(0) := debug.backend.sampleFetchCycles
  telemetry.stages(1) := debug.backend.sampleDecodeCycles
  telemetry.stages(2) := debug.backend.sampleExecuteCycles
  telemetry.stages(3) := debug.backend.sampleMemoryCycles
  telemetry.stages(4) := debug.backend.sampleWritebackCycles
  telemetry.completionValid := debug.backend.completionCommitValid
  telemetry.completionCycle := debug.backend.cycleCount
  telemetry.completionStalls(0) := debug.frontend.fetchStarvationCycles
  telemetry.completionStalls(1) := debug.backend.idStallCycles
  telemetry.completionStalls(2) := debug.backend.executeStallCycles
  telemetry.completionStalls(3) := debug.backend.memoryStallCycles
  telemetry.completionStalls(4) := debug.frontend.redirectFlushCount
  telemetry.pipelineFeatures := debug.backend.pipelineFeatures

  val traceStatus = WireDefault(0.U.asTypeOf(new FpgaRuntimeTraceStatus))
  mailbox.io.trace := traceStatus
  if (performanceMonitor.enabled) {
    val statistics = Module(new FpgaRuntimeStatistics(width, performanceMonitor.maxRecords))
    val writer = Module(new FpgaRuntimeTraceWriter(
      width, axiConfig.idWidth, performanceMonitor.maxRecords, performanceMonitor.cacheRecords,
      performanceMonitor.burstRecords))
    statistics.io.telemetry := telemetry
    statistics.io.clear := mailbox.io.traceClear
    statistics.io.classSelector := mailbox.io.traceClassSelector
    statistics.io.stageSelector := mailbox.io.traceStageSelector
    statistics.io.stallSelector := mailbox.io.traceStallSelector
    writer.io.telemetry := telemetry
    writer.io.traceBase := mailbox.io.traceHostBase
    writer.io.clear := mailbox.io.traceClear
    io.trace.get <> writer.io.axi
    traceStatus := statistics.io.status
    traceStatus.records := writer.io.records
    traceStatus.dropped := writer.io.dropped
    traceStatus.drained := statistics.io.status.drained && writer.io.drained
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
  private val memoryDataWidth = config.memoryDataWidth
  private val performanceMonitor = FpgaConfigParameters.performanceMonitor
  val io = IO(new FpgaSystemIO(axiConfig.addrWidth, memoryDataWidth, axiConfig.idWidth,
    performanceMonitor.enabled, performanceMonitor.traceDataWidth))
  private val system = Module(new NpcFpgaSystem)
  FpgaSystemIO.connect(io, system.io)
}

/** Compatibility entry point for older direct callers; it selects ZCU102. */
class NpcFpgaTop(implicit parameters: Parameters)
    extends NpcFpgaShell(FpgaBoard.Zcu102)
