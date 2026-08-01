package npc

/** 性能报告中 MEM 阶段的统计口径。 */
sealed trait MemoryStatisticsMode {
  def name: String
}

object MemoryStatisticsMode {
  /** 同时保留访存排队时间和真实服务时间。 */
  case object Split extends MemoryStatisticsMode {
    override val name: String = "Split"
  }

  /** 只把 D$/下游事务的实际服务时间作为 MEM 统计。 */
  case object ServiceOnly extends MemoryStatisticsMode {
    override val name: String = "ServiceOnly"
  }

  val values: Seq[MemoryStatisticsMode] = Seq(Split, ServiceOnly)
}

/** 由终端直接挂载的完整 NEMU host 配方。
  *
  * XLEN、F 扩展、NPC/SoC 模式、板卡地址和 mailbox ABI 仍来自硬件 profile，不能
  * 在这里覆盖。所有字段均为普通 case class 数据，自定义终端可直接使用 `copy(...)`。
  */
final case class NemuHostConfig(
  backend: NemuBackend,
  trace: Boolean,
  watchpoint: Boolean,
  vcd: Boolean,
  performanceHtml: Boolean,
  cacheHtml: Boolean,
  pipelineHtml: Boolean,
  softwareDifftest: Boolean,
  devices: Boolean,
  optimization: String,
  debug: Boolean,
  lto: Boolean,
  asan: Boolean,
  memoryStatisticsMode: MemoryStatisticsMode = MemoryStatisticsMode.Split
) {
  require(Set("O0", "O1", "O2", "O3").contains(optimization),
    s"NEMU optimization must be O0/O1/O2/O3, got $optimization")
  require(!vcd || trace, "NEMU VCD requires trace to be enabled")
  require(!vcd || backend == NemuBackend.LocalVerilator,
    "NEMU VCD only supports the local Verilator host")
  require(!pipelineHtml || performanceHtml,
    "NEMU pipeline HTML requires performance HTML")
  require(!cacheHtml || performanceHtml,
    "NEMU cache HTML requires performance HTML")
  require(!softwareDifftest || backend == NemuBackend.LocalVerilator,
    "NEMU software difftest only supports the local Verilator host")
}

object NemuHostConfig {
  final case class Preset(name: String, config: NemuHostConfig) {
    require(name.matches("[A-Za-z][A-Za-z0-9]*"), s"非法 NEMU preset 名称：$name")
  }

  /** 本地 Verilator host 的完整基础配方。 */
  val LocalBase: NemuHostConfig = NemuHostConfig(
    backend = NemuBackend.LocalVerilator,
    trace = false,
    watchpoint = true,
    vcd = false,
    performanceHtml = false,
    cacheHtml = false,
    pipelineHtml = false,
    softwareDifftest = false,
    devices = true,
    optimization = "O2",
    debug = false,
    lto = false,
    asan = false
  )

  /** 本地 Verilator 的性能主页与逐指令明细配方。 */
  val LocalPerformance: NemuHostConfig = LocalBase.copy(
    performanceHtml = true
  )

  /** 本地 Verilator 的提交级流水线与软件自查配方。 */
  val LocalPipelineTrace: NemuHostConfig = LocalPerformance.copy(
    cacheHtml = true,
    pipelineHtml = true,
    softwareDifftest = true
  )

  /** 本地 Verilator 的交互式 VCD 与提交级流水记录配方。 */
  val LocalVcdTrace: NemuHostConfig = LocalPipelineTrace.copy(
    trace = true,
    vcd = true
  )

  /** U55C XRT host 的完整基础配方。 */
  val U55cBase: NemuHostConfig = NemuHostConfig(
    backend = NemuBackend.U55c,
    trace = false,
    watchpoint = true,
    vcd = false,
    performanceHtml = false,
    cacheHtml = false,
    pipelineHtml = false,
    softwareDifftest = false,
    devices = false,
    optimization = "O2",
    debug = false,
    lto = false,
    asan = false
  )

  /** Dedicated batch host for the U55C v13 performance-monitor xclbin. */
  val U55cPerformanceMonitor: NemuHostConfig = U55cBase.copy(
    performanceHtml = true,
    cacheHtml = true,
    pipelineHtml = true
  )

  /** ZCU102 PS Linux host 的完整基础配方。 */
  val Zcu102Base: NemuHostConfig = NemuHostConfig(
    backend = NemuBackend.Zcu102,
    trace = false,
    watchpoint = true,
    vcd = false,
    performanceHtml = false,
    cacheHtml = false,
    pipelineHtml = false,
    softwareDifftest = false,
    devices = false,
    optimization = "O2",
    debug = false,
    lto = false,
    asan = false
  )

  /** `host-config-list` 的显式登记表；它不扫描或反射 Scala 类。 */
  val registeredPresets: Vector[Preset] = Vector(
    Preset("LocalBase", LocalBase),
    Preset("LocalPerformance", LocalPerformance),
    Preset("LocalPipelineTrace", LocalPipelineTrace),
    Preset("LocalVcdTrace", LocalVcdTrace),
    Preset("U55cBase", U55cBase),
    Preset("U55cPerformanceMonitor", U55cPerformanceMonitor),
    Preset("Zcu102Base", Zcu102Base)
  )

  /** 已登记 Base 使用稳定名称；局部 `copy(...)` 的自定义配方统一标记为 Custom。 */
  def presetName(config: NemuHostConfig): String =
    registeredPresets.find(_.config == config).map(_.name).getOrElse("Custom")
}
