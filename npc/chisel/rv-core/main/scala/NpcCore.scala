package npc

import chisel3._
import chisel3.util._
import npc.ip.memory.{DpiMemoryFaultSink, MemoryFault}
import npc.protocol._

/**
  * NPC 顶层组装。
  *
  * CPU 保持历史顶层名称和 AXI 端口。启用调试的构建导出一个嵌套的
  * NpcCoreDebugBundle。微架构职责从此处分离：[[NpcFrontend]] 负责取指/译码，
  * [[NpcBackend]] 负责按序执行/提交，[[NpcMemoryFabric]] 负责内存路由。
  */
class NpcCore(
  config: NpcConfig = NpcConfig(),
  components: NpcCoreComponents = SimulationCoreComponents
) extends Module {
  override def desiredName: String = "CPU"

  private val cfg = config.isa
  private val pipelineConfig = config.pipeline
  private val debugEnabled = config.debug.enableTopDebugIo
  private val axiConfig = config.axi
  private val memoryDataWidth = config.memoryDataWidth
  private val cacheConfig = config.cache

  require(axiConfig.addrWidth == 32,
    s"NpcCore currently uses a 32-bit physical address path, got ${axiConfig.addrWidth}")
  require(axiConfig.dataWidth == cfg.xlen,
    s"NPC AXI data width (${axiConfig.dataWidth}) must match XLEN (${cfg.xlen})")
  require(!pipelineConfig.enablePipeline || pipelineConfig.enableInterlock,
    "PipelineConfig(enablePipeline = true) requires enableInterlock for unavailable load and serial results")
  require(!components.exposesDispatchControl(config) || config.debug.enableDispatchControl,
    s"${components.name} component requires DebugConfig(enableDispatchControl = true)")
  require(!cacheConfig.usesUram || components.supportsUram,
    "CacheStorage.Uram is only valid for an FPGA construction")

  val io = IO(new Bundle {
    val interrupt = Input(Bool())
    val master = new Axi4FullMasterIO(axiConfig.addrWidth, memoryDataWidth, axiConfig.idWidth)
    val memoryFault = Output(new MemoryFault(axiConfig.addrWidth))
    val putch = if (axiConfig.useExternalMaster) Some(Decoupled(UInt(8.W))) else None
    val debug = if (debugEnabled) {
      Some(Output(new NpcCoreDebugBundle(cfg, axiConfig.addrWidth, axiConfig.dataWidth)))
    } else None
    val dispatchControl = if (components.exposesDispatchControl(config)) Some(new NpcDispatchControlPort) else None
    val cacheMaintenance = if (cacheConfig.dcache.enabled && components.exposesDispatchControl(config)) {
      Some(new NpcCacheMaintenancePort)
    } else None
  })

  val frontend = Module(new NpcFrontend(config))
  val backend = Module(new NpcBackend(config, components))
  val memoryFabric = Module(new NpcMemoryFabric(config))

  frontend.io.redirectValid := backend.io.redirectValid
  frontend.io.redirectTarget := backend.io.redirectTarget
  val fenceHold = WireDefault(false.B)
  frontend.io.fenceHold := fenceHold
  val externalDispatchPermit = WireDefault(true.B)
  io.dispatchControl.foreach(control => externalDispatchPermit := control.dispatchPermit)
  val maintenanceDispatchPermit = WireDefault(true.B)
  val fenceIPending = frontend.io.dispatch.valid && config.isa.Zifencei.B &&
    Instructions.FENCE_I.matches(frontend.io.dispatch.bits.instruction)
  val fencePending = frontend.io.dispatch.valid &&
    Instructions.FENCE.matches(frontend.io.dispatch.bits.instruction)
  val cacheFencePending = fencePending || fenceIPending
  backend.io.dispatch.valid := frontend.io.dispatch.valid && externalDispatchPermit && maintenanceDispatchPermit
  backend.io.dispatch.bits := frontend.io.dispatch.bits
  frontend.io.dispatch.ready := backend.io.dispatch.ready && externalDispatchPermit && maintenanceDispatchPermit
  io.dispatchControl.foreach(_.dispatchFire := backend.io.dispatch.fire)
  backend.io.interrupt := io.interrupt
  backend.io.interruptPc := frontend.io.interruptPc

  val instructionCacheStatistics = WireDefault(0.U.asTypeOf(new CacheStatistics))
  val dataCacheStatistics = WireDefault(0.U.asTypeOf(new CacheStatistics))
  val unifiedL2Statistics = WireDefault(0.U.asTypeOf(new CacheStatistics))
  val instructionInvalidate = WireDefault(false.B)
  val instructionInvalidateDone = WireDefault(true.B)
  val dataFlush = WireDefault(false.B)
  val dataFlushDone = WireDefault(true.B)
  val l2Flush = WireDefault(false.B)
  val l2FlushDone = WireDefault(true.B)
  val dataCacheDrained = WireDefault(true.B)
  val l2Drained = WireDefault(true.B)
  // FENCE.I 维护期间会主动关闭取指，不能仅凭前后端暂时空闲就让仿真器
  // 将稳定的 PC 判定为程序结束；维护控制器重新放行 dispatch 前保持该标志。
  val cacheMaintenanceBusy = WireDefault(false.B)

  memoryFabric.io.l2Flush := l2Flush
  l2FlushDone := memoryFabric.io.l2FlushDone
  l2Drained := memoryFabric.io.l2Drained
  unifiedL2Statistics := memoryFabric.io.l2Statistics

  if (cacheConfig.icache.enabled) {
    val instructionCache = Module(new InstructionCache(config))
    frontend.io.axi <> instructionCache.io.cpu
    instructionCache.io.memory <> memoryFabric.io.instruction
    instructionCache.io.invalidate := instructionInvalidate
    instructionInvalidateDone := instructionCache.io.invalidateDone
    instructionCacheStatistics := instructionCache.io.statistics
  } else {
    frontend.io.axi <> memoryFabric.io.instruction
  }

  if (cacheConfig.dcache.enabled) {
    val dataCache = Module(new DataCache(config))
    backend.io.axi <> dataCache.io.cpu
    dataCache.io.memory <> memoryFabric.io.data
    dataCache.io.flush := dataFlush
    dataFlushDone := dataCache.io.flushDone
    dataCacheDrained := dataCache.io.drained
    dataCacheStatistics := dataCache.io.statistics
  } else {
    backend.io.axi <> memoryFabric.io.data
  }

  if (cacheConfig.enabled) {
    val maintenance = Module(new CacheMaintenanceController(
      cacheConfig.icache.enabled, cacheConfig.dcache.enabled, cacheConfig.l2cache.enabled))
    maintenance.io.fencePending := cacheFencePending
    maintenance.io.fenceInvalidatesInstruction := fenceIPending
    maintenance.io.fenceAccepted := backend.io.dispatch.fire && cacheFencePending
    maintenance.io.backendBusy := backend.io.debug.coreBusy
    maintenance.io.externalDrainRequest := io.cacheMaintenance.map(_.drainRequest).getOrElse(false.B)
    maintenance.io.dcacheFlushDone := dataFlushDone
    maintenance.io.l2FlushDone := l2FlushDone
    maintenance.io.icacheInvalidateDone := instructionInvalidateDone
    dataFlush := maintenance.io.dcacheFlush
    l2Flush := maintenance.io.l2Flush
    instructionInvalidate := maintenance.io.icacheInvalidate
    maintenanceDispatchPermit := maintenance.io.dispatchPermit
    // FENCE 只保证数据可见性，保留已经预取的指令；只有 FENCE.I 在 I$ 失效前
    // 丢弃这些年轻取指，避免之后执行旧指令字。流水模式打一拍后再关闭前端，
    // 切断“当前派发的 FENCE.I -> fetchFlush -> 当前响应”的组合环；阻塞模式
    // 保持原来的即时暂停时序。
    val fenceHoldValue = if (cacheConfig.accessMode == CacheAccessMode.PipelinedTwoCycle) {
      RegNext(fenceIPending && !maintenance.io.dispatchPermit, false.B)
    } else {
      fenceIPending && !maintenance.io.dispatchPermit
    }
    fenceHold := fenceHoldValue
    cacheMaintenanceBusy := !maintenance.io.dispatchPermit
    io.cacheMaintenance.foreach(_.drained := maintenance.io.externalDrained && dataCacheDrained && l2Drained)
  } else {
    io.cacheMaintenance.foreach(_.drained := true.B)
  }
  io.master <> memoryFabric.io.master

  // 后端故障优先，因为它携带了已提交到 MEM 阶段的指令访问。
  io.memoryFault.valid := backend.io.memoryFault.valid || frontend.io.memoryFault.valid
  io.memoryFault.addr := Mux(backend.io.memoryFault.valid,
    backend.io.memoryFault.addr, frontend.io.memoryFault.addr)
  io.memoryFault.write := Mux(backend.io.memoryFault.valid,
    backend.io.memoryFault.write, frontend.io.memoryFault.write)
  io.memoryFault.len := Mux(backend.io.memoryFault.valid,
    backend.io.memoryFault.len, frontend.io.memoryFault.len)
  io.memoryFault.reason := Mux(backend.io.memoryFault.valid,
    backend.io.memoryFault.reason, frontend.io.memoryFault.reason)

  if (!axiConfig.useExternalMaster) {
    val faultDpi = Module(new DpiMemoryFaultSink)
    faultDpi.io.clk := clock
    faultDpi.io.rst := reset.asBool
    faultDpi.io.valid := io.memoryFault.valid
    faultDpi.io.addr := io.memoryFault.addr
    faultDpi.io.write := io.memoryFault.write
    faultDpi.io.len := io.memoryFault.len
    faultDpi.io.reason := io.memoryFault.reason
  }
  // Option.zip 只在两端口都存在时生成配对，foreach 解构该二元组后用 <> 完成 Chisel 连接。
  (io.putch zip memoryFabric.io.putch).foreach { case (external, event) => external <> event }
  if (debugEnabled) {
    val debug = io.debug.get
    debug.frontend := frontend.io.debug
    debug.backend := backend.io.debug

    val coreBusy = debug.frontend.fetchBusy || debug.backend.coreBusy || cacheMaintenanceBusy
    val knownBackpressure = debug.frontend.fetchBusy || debug.frontend.dispatchBackpressured ||
      debug.backend.idExBackpressured || debug.backend.integerExecuteBackpressured ||
      debug.backend.exMemBackpressured ||
      debug.backend.memoryWaitingForLsu || debug.backend.lsuTransactionActive ||
      debug.backend.serialExecuteActive || backend.io.redirectValid || cacheMaintenanceBusy
    debug.backpressureReasons := Cat(
      coreBusy && !knownBackpressure,
      backend.io.redirectValid,
      debug.backend.serialExecuteActive,
      debug.backend.lsuTransactionActive,
      debug.backend.memoryWaitingForLsu,
      debug.backend.exMemBackpressured || debug.backend.integerExecuteBackpressured,
      debug.backend.idExBackpressured,
      debug.frontend.dispatchBackpressured,
      debug.frontend.fetchBusy
    )
    debug.coreBusy := coreBusy
    debug.bufferIndex := 0.U
    debug.bufferAccessCount := 0.U
    debug.waitCycles := 0.U
    debug.addressLow := 0.U
    debug.addressHigh := 0.U
    debug.pcBase := 0.U
    debug.instructionHighByte := 0.U
    debug.instructionLowByte := 0.U
    debug.extendedRegisters := VecInit(Seq.fill(128)(0.U(cfg.xlen.W)))
    debug.master.arValid := io.master.ar.valid
    debug.master.arReady := io.master.ar.ready
    debug.master.arAddress := io.master.ar.bits.addr
    debug.master.rValid := io.master.r.valid
    debug.master.rReady := io.master.r.ready
    debug.master.rData := io.master.r.bits.data
    debug.cache.instruction := instructionCacheStatistics
    debug.cache.data := dataCacheStatistics
    debug.cache.unifiedL2 := unifiedL2Statistics
    debug.cache.drained := dataCacheDrained && l2Drained
  }
}
