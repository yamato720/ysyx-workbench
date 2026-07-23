package scpu

import chisel3._
import chisel3.util._
import scpu.ipdpishell.MemoryFaultDpi
import scpu.protocol._

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

  require(axiConfig.addrWidth == 32,
    s"NpcCore currently uses a 32-bit physical address path, got ${axiConfig.addrWidth}")
  require(axiConfig.dataWidth == cfg.xlen,
    s"NPC AXI data width (${axiConfig.dataWidth}) must match XLEN (${cfg.xlen})")
  require(!pipelineConfig.enablePipeline || pipelineConfig.enableInterlock,
    "PipelineConfig(enablePipeline = true) requires enableInterlock for unavailable load and serial results")
  require(!components.exposesDispatchControl(config) || config.debug.enableDispatchControl,
    s"${components.name} component requires DebugConfig(enableDispatchControl = true)")

  val io = IO(new Bundle {
    val interrupt = Input(Bool())
    val master = new Axi4FullMasterIO(axiConfig.addrWidth, axiConfig.dataWidth, axiConfig.idWidth)
    val memoryFault = Output(new MemoryFault(axiConfig.addrWidth))
    val arithmeticAssist = if (components.exposesArithmeticAssist(config)) {
      Some(new ArithmeticAssistPort(cfg.xlen))
    } else None
    val putch = if (axiConfig.useExternalMaster) Some(Decoupled(UInt(8.W))) else None
    val debug = if (debugEnabled) {
      Some(Output(new NpcCoreDebugBundle(cfg, axiConfig.addrWidth, axiConfig.dataWidth)))
    } else None
    val dispatchControl = if (components.exposesDispatchControl(config)) Some(new NpcDispatchControlPort) else None
  })

  val frontend = Module(new NpcFrontend(config))
  val backend = Module(new NpcBackend(config, components))
  val memoryFabric = Module(new NpcMemoryFabric(config))

  frontend.io.redirectValid := backend.io.redirectValid
  frontend.io.redirectTarget := backend.io.redirectTarget
  io.dispatchControl match {
    case Some(control) =>
      backend.io.dispatch.valid := frontend.io.dispatch.valid && control.dispatchPermit
      backend.io.dispatch.bits := frontend.io.dispatch.bits
      frontend.io.dispatch.ready := backend.io.dispatch.ready && control.dispatchPermit
      control.dispatchFire := backend.io.dispatch.fire
    case None => backend.io.dispatch <> frontend.io.dispatch
  }
  backend.io.interrupt := io.interrupt
  frontend.io.axi <> memoryFabric.io.instruction
  backend.io.axi <> memoryFabric.io.data
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
    val faultDpi = Module(new MemoryFaultDpi)
    faultDpi.io.clk := clock
    faultDpi.io.rst := reset.asBool
    faultDpi.io.valid := io.memoryFault.valid
    faultDpi.io.addr := io.memoryFault.addr
    faultDpi.io.write := io.memoryFault.write
    faultDpi.io.len := io.memoryFault.len
    faultDpi.io.reason := io.memoryFault.reason
  }
  (io.putch zip memoryFabric.io.putch).foreach { case (external, event) => external <> event }
  (io.arithmeticAssist zip backend.io.arithmeticAssist).foreach { case (external, assist) =>
    external.request.valid := assist.request.valid
    external.request.bits := assist.request.bits
    assist.request.ready := external.request.ready
    assist.response.valid := external.response.valid
    assist.response.bits := external.response.bits
    external.response.ready := assist.response.ready
    external.busy := assist.busy
  }

  if (debugEnabled) {
    val debug = io.debug.get
    debug.frontend := frontend.io.debug
    debug.backend := backend.io.debug

    val coreBusy = debug.frontend.fetchBusy || debug.backend.coreBusy
    val knownBackpressure = debug.frontend.fetchBusy || debug.frontend.dispatchBackpressured ||
      debug.backend.idExBackpressured || debug.backend.exMemBackpressured ||
      debug.backend.memoryWaitingForLsu || debug.backend.lsuTransactionActive ||
      debug.backend.serialExecuteActive || backend.io.redirectValid
    debug.backpressureReasons := Cat(
      coreBusy && !knownBackpressure,
      backend.io.redirectValid,
      debug.backend.serialExecuteActive,
      debug.backend.lsuTransactionActive,
      debug.backend.memoryWaitingForLsu,
      debug.backend.exMemBackpressured,
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
  }
}
