package scpu

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** NEMU host 的固定后端。硬件终端直接选择其中之一，不由 Make 推断。 */
sealed abstract class NemuBackend(val id: String, val kconfigSymbol: String)

object NemuBackend {
  case object LocalVerilator extends NemuBackend("local", "CONFIG_FPGA_BACKEND_NONE")
  case object U55c extends NemuBackend("u55c", "CONFIG_FPGA_BACKEND_U55C")
  case object Zcu102 extends NemuBackend("zcu102", "CONFIG_FPGA_BACKEND_ZCU102")
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

/** 为 Make 的 `host-config-list` 写出显式登记的 NEMU Base。 */
object DescribeNemuConfigCatalog extends App {
  require(args.length == 1, "用法：scpu.DescribeNemuConfigCatalog <output.tsv>")
  val output = Path.of(args(0)).toAbsolutePath.normalize
  val rows = NemuHostConfig.registeredPresets.map { preset =>
    val settings = preset.config
    val bit = (value: Boolean) => if (value) 1 else 0
    val policy = Seq(
      s"trace=${bit(settings.trace)}",
      s"watchpoint=${bit(settings.watchpoint)}",
      s"vcd=${bit(settings.vcd)}",
      s"performance-html=${bit(settings.performanceHtml)}",
      s"pipeline-html=${bit(settings.pipelineHtml)}",
      s"software-difftest=${bit(settings.softwareDifftest)}",
      s"devices=${bit(settings.devices)}",
      s"opt=${settings.optimization}",
      s"debug=${bit(settings.debug)}",
      s"lto=${bit(settings.lto)}",
      s"asan=${bit(settings.asan)}"
    ).mkString(",")
    s"${preset.name}\t${settings.backend.id}\t$policy"
  }
  Option(output.getParent).foreach(Files.createDirectories(_))
  val content = ("# 此文件由 scpu.DescribeNemuConfigCatalog 自动生成；不要手工编辑。" +: rows)
    .mkString("\n") + "\n"
  Files.writeString(output, content, StandardCharsets.UTF_8)
}
