package npc

/*
 * NEMU host 可勾选开关（对应 menuconfig / NEMU_* profile 字段）。
 * 性能报告与 SDB 消费默认打开：host 只消费硬件已导出的信号，不改变架构。
 * 额外引脚由硬件 Config 决定：
 *
 *   域内硬件依赖：++ InstructionLogConfig / PipelineLogConfig / CacheLogConfig / BpLogConfig
 *   终端：++ new SdbDebugConfig ++ new FinalLogConfig
 *   TraceConfig 暂时保留，供 U55C v13 HBM 通路使用。
 *
 * 定制 host 时在终端 override configuredNemu，对已有 preset 做 copy(...)：
 *
 *   configuredNemu = NemuHostConfig.LocalPipelineTrace.copy(vcd = true, trace = true)
 *
 *   backend                 宿主后端：LocalVerilator / U55c / Zcu102
 *   trace                   指令/功能追踪（NEMU_TRACE）
 *   watchpoint              监视点（NEMU_WATCHPOINT）；默认开
 *   vcd                     SDB 交互式 VCD；依赖 trace，且仅 LocalVerilator
 *                           开/关都会改 Verilator ABI，必须完整 rebuild
 *   performanceHtml         性能主页 + instructions.html；默认开，无硬件信号则写空报告
 *   cacheHtml               cache.html；默认开，依赖 performanceHtml
 *   pipelineHtml            pipeline.html；默认开，依赖 performanceHtml
 *   softwareDifftest        逐提交 NPC/NEMU 软件自查；仅 LocalVerilator
 *   devices                 外设模型（本地默认开，FPGA host 默认关）
 *   optimization            编译优化：O0 / O1 / O2 / O3
 *   debug                   带调试符号编译
 *   lto                     链接时优化
 *   asan                    AddressSanitizer
 *   memoryStatisticsMode    性能报告 MEM 口径：Split 或 ServiceOnly
 *
 * 起点配方见下方 LocalBase、LocalPerformance、LocalPipelineTrace、LocalVcdTrace、
 * U55cBase、Zcu102Base。U55cPerformanceMonitor 是 U55cBase 的别名。
 * 未再登记的 copy 记为 Custom。
 * XLEN、ISA、板卡地址和 mailbox ABI 仍来自硬件 Config，不能在此覆盖。
 */

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
    performanceHtml = true,
    cacheHtml = true,
    pipelineHtml = true,
    softwareDifftest = false,
    devices = true,
    optimization = "O2",
    debug = false,
    lto = false,
    asan = false
  )

  /** 只保留性能主页的较轻本地配方。 */
  val LocalPerformance: NemuHostConfig = LocalBase.copy(
    cacheHtml = false,
    pipelineHtml = false
  )

  /** 本地 Verilator 的提交级流水线与软件自查配方。 */
  val LocalPipelineTrace: NemuHostConfig = LocalBase.copy(
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
    performanceHtml = true,
    cacheHtml = true,
    pipelineHtml = true,
    softwareDifftest = false,
    devices = false,
    optimization = "O2",
    debug = false,
    lto = false,
    asan = false
  )

  /** 与 U55cBase 相同：host 默认即可消费 v13 监测数据。 */
  val U55cPerformanceMonitor: NemuHostConfig = U55cBase

  /** ZCU102 PS Linux host 的完整基础配方。 */
  val Zcu102Base: NemuHostConfig = NemuHostConfig(
    backend = NemuBackend.Zcu102,
    trace = false,
    watchpoint = true,
    vcd = false,
    performanceHtml = true,
    cacheHtml = true,
    pipelineHtml = true,
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
    Preset("Zcu102Base", Zcu102Base)
  )

  /** 已登记 Base 使用稳定名称；局部 `copy(...)` 的自定义配方统一标记为 Custom。 */
  def presetName(config: NemuHostConfig): String =
    registeredPresets.find(_.config == config).map(_.name).getOrElse("Custom")
}
