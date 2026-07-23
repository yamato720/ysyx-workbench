package scpu

private[scpu] object ConstructionValidation {
  def localNemu(config: NemuHostConfig): NemuHostConfig = {
    require(config.backend == NemuBackend.LocalVerilator,
      s"本地仿真只能使用 local NEMU backend，实际为 ${config.backend.id}")
    config
  }

  def fpga(nemu: NemuHostConfig, fpga: FpgaToolchainConfig): FpgaToolchainConfig = {
    val expectedBackend = fpga.device.board match {
      case "u55c" => NemuBackend.U55c
      case "zcu102" => NemuBackend.Zcu102
      case board => throw new IllegalArgumentException(s"不支持的 FPGA 工具链板卡：$board")
    }
    require(nemu.backend == expectedBackend,
      s"FPGA 工具链板卡 ${fpga.device.board} 必须绑定 ${expectedBackend.id} NEMU backend，" +
        s"实际为 ${nemu.backend.id}")
    fpga
  }
}

/** profile 与反射解析器共享的最小运行构造接口。 */
trait HostConstruction {
  protected def configuredNemu: NemuHostConfig

  final val capability: String = "run"
  def nemuConfig: NemuHostConfig = configuredNemu
  final def nemuPreset: String = NemuHostConfig.presetName(nemuConfig)
}

/** 本地 NPC/SoC 仿真底层行为；完整终端预设必须提供 local NEMU 配方。 */
trait NemuSimulationConstruction extends HostConstruction {
  final override def nemuConfig: NemuHostConfig = ConstructionValidation.localNemu(configuredNemu)
}

/** FPGA 底层行为；完整终端预设必须同时提供 NEMU 与 FPGA 工具链配方。 */
trait FpgaConstruction extends HostConstruction {
  protected def configuredFpga: FpgaToolchainConfig

  final override def nemuConfig: NemuHostConfig = {
    fpgaToolchainConfig
    configuredNemu
  }

  final def fpgaToolchainConfig: FpgaToolchainConfig = {
    ConstructionValidation.fpga(configuredNemu, configuredFpga)
  }
}

/** 自动目录用于识别可由 Make 直接选择、且必定经 NEMU 运行的完整终端。 */
trait MakeTerminal { self: HostConstruction =>
  def constructionScope: String
  def constructionTarget: String
}
