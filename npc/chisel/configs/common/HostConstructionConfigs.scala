package scpu

/** profile 与反射解析器共享的最小运行构造接口。 */
trait HostConstructionConfig {
  protected def configuredNemu: NemuHostConfig

  final val capability: String = "run"
  def nemuConfig: NemuHostConfig = configuredNemu
  final def nemuPreset: String = NemuHostConfig.presetName(nemuConfig)
}

/** 本地 NPC/SoC 仿真终端；终端必须显式提供 local NEMU 配方。 */
trait NemuSimulationConstructionConfig extends HostConstructionConfig {
  final override def nemuConfig: NemuHostConfig = {
    val config = configuredNemu
    require(config.backend == NemuBackend.LocalVerilator,
      s"本地仿真只能使用 local NEMU backend，实际为 ${config.backend.id}")
    config
  }
}

/** FPGA 终端；终端必须同时显式提供 NEMU 与 FPGA 工具链配方。 */
trait FpgaConstructionConfig extends HostConstructionConfig {
  protected def configuredFpga: FpgaToolchainConfig

  final override def nemuConfig: NemuHostConfig = {
    fpgaToolchainConfig
    configuredNemu
  }

  final def fpgaToolchainConfig: FpgaToolchainConfig = {
    val fpga = configuredFpga
    val expectedBackend = fpga.device.board match {
      case "u55c" => NemuBackend.U55c
      case "zcu102" => NemuBackend.Zcu102
      case board => throw new IllegalArgumentException(s"不支持的 FPGA 工具链板卡：$board")
    }
    require(configuredNemu.backend == expectedBackend,
      s"FPGA 工具链板卡 ${fpga.device.board} 必须绑定 ${expectedBackend.id} NEMU backend，" +
        s"实际为 ${configuredNemu.backend.id}")
    fpga
  }
}

/** 自动目录用于识别可由 Make 直接选择、且必定经 NEMU 运行的完整终端。 */
trait MakeTerminalConfig { self: HostConstructionConfig =>
  def constructionScope: String
  def constructionTarget: String
}

trait NpcTerminalConfig extends MakeTerminalConfig {
  self: NemuSimulationConstructionConfig =>
  final override val constructionScope: String = "npc"
  final override val constructionTarget: String = "NPC"
}

trait SocTerminalConfig extends MakeTerminalConfig {
  self: NemuSimulationConstructionConfig =>
  final override val constructionScope: String = "soc"
  final override val constructionTarget: String = "SOC"
}

trait FpgaNpcTerminalConfig extends MakeTerminalConfig {
  self: FpgaConstructionConfig =>
  final override val constructionScope: String = "fpga"
  final override val constructionTarget: String = "NPC"
}

trait FpgaSocTerminalConfig extends MakeTerminalConfig {
  self: FpgaConstructionConfig =>
  final override val constructionScope: String = "fpga"
  final override val constructionTarget: String = "SOC"
}

/** 仅供 Scala/RTL 检查的 Config；它不是 Make 构造或运行入口。 */
trait CheckOnlyConstructionConfigBase {
  final val capability: String = "check-only"
}
