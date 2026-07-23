package scpu

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
  pipelineHtml: Boolean,
  softwareDifftest: Boolean,
  devices: Boolean,
  optimization: String,
  debug: Boolean,
  lto: Boolean,
  asan: Boolean
) {
  require(Set("O0", "O1", "O2", "O3").contains(optimization),
    s"NEMU optimization must be O0/O1/O2/O3, got $optimization")
  require(!vcd || trace, "NEMU VCD requires trace to be enabled")
  require(!vcd || backend == NemuBackend.LocalVerilator,
    "NEMU VCD only supports the local Verilator host")
  require(!pipelineHtml || performanceHtml,
    "NEMU pipeline HTML requires performance HTML")
  require(!pipelineHtml || backend == NemuBackend.LocalVerilator,
    "NEMU pipeline HTML only supports the local Verilator host")
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
    pipelineHtml = true,
    softwareDifftest = true
  )

  /** U55C XRT host 的完整基础配方。 */
  val U55cBase: NemuHostConfig = NemuHostConfig(
    backend = NemuBackend.U55c,
    trace = false,
    watchpoint = true,
    vcd = false,
    performanceHtml = false,
    pipelineHtml = false,
    softwareDifftest = false,
    devices = false,
    optimization = "O2",
    debug = false,
    lto = false,
    asan = false
  )

  /** ZCU102 PS Linux host 的完整基础配方。 */
  val Zcu102Base: NemuHostConfig = NemuHostConfig(
    backend = NemuBackend.Zcu102,
    trace = false,
    watchpoint = true,
    vcd = false,
    performanceHtml = false,
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
    Preset("U55cBase", U55cBase),
    Preset("Zcu102Base", Zcu102Base)
  )

  /** 已登记 Base 使用稳定名称；局部 `copy(...)` 的自定义配方统一标记为 Custom。 */
  def presetName(config: NemuHostConfig): String =
    registeredPresets.find(_.config == config).map(_.name).getOrElse("Custom")
}
