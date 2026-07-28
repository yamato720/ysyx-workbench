package npc

import org.chipsalliance.cde.config.View

private[npc] object ConstructionValidation {
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

/** 与运行宿主平行的计算 IP 挂载合同。
  *
  * 完整构造必须由终端 trait 主动提供该选择；核心组合和 CDE 图不能通过构造参数或
  * `++` 链自行挑选 NEMU/FPGA 后端。
  */
trait IpConstruction {
  protected def configuredIp: IpComputeSelection

  final def ipComputeSelection: IpComputeSelection = configuredIp
}

/** 从 CDE 顶层终端取得已挂载的计算 IP。
  *
  * CDE 的 `site` 始终是最终终端，因此 NPC 与 SoC 可共享这个桥接，而不必把 IP
  * 选择作为各层 Config 的构造参数继续传递。
  */
private[npc] object IpConstruction {
  def selection(site: View): IpComputeSelection = site match {
    case construction: IpConstruction => construction.ipComputeSelection
    case _ => throw new IllegalArgumentException(
      "NPC CDE 构造必须挂载 IP terminal trait，例如 " +
        "NemuSimulationIpTerminal 或 FpgaIpTerminal"
    )
  }
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
