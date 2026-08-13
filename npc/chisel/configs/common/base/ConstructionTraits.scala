package npc

import org.chipsalliance.cde.config.View

private[npc] object ConstructionValidation {
  def localNemu(config: NemuHostConfig): NemuHostConfig = {
    require(config.backend == NemuBackend.LocalVerilator,
      s"本地仿真只能使用 local NEMU backend，实际为 ${config.backend.id}")
    config
  }

  def fpgaToolchain(fpga: FpgaToolchainConfig): FpgaToolchainConfig = {
    require(Set("u55c", "zcu102").contains(fpga.device.board),
      s"不支持的 FPGA 工具链板卡：${fpga.device.board}")
    fpga
  }

  def fpga(nemu: NemuHostConfig, fpga: FpgaToolchainConfig): FpgaToolchainConfig = {
    fpgaToolchain(fpga)
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

/** profile 与反射解析器共享的最小构造接口。 */
trait Construction {
  protected def configuredCapability: String

  final def capability: String = configuredCapability
}

/** profile 与反射解析器共享的最小运行构造接口。 */
trait HostConstruction extends Construction {
  protected def configuredNemu: NemuHostConfig
  protected def configuredCapability: String = "run"

  def nemuConfig: NemuHostConfig = configuredNemu
  final def nemuPreset: String = NemuHostConfig.presetName(nemuConfig)
}

/** 与 FPGA 资产能力正交的软件加速器宿主合同。 */
final case class AcceleratorHostConfig(kind: String, abi: String) {
  require(kind.matches("[a-z][a-z0-9-]*"), s"非法 accelerator host 类型：$kind")
  require(abi.matches("[a-z][a-z0-9-]*-v[1-9][0-9]*"), s"非法 accelerator host ABI：$abi")
}

object AcceleratorHostConfig {
}

/** 终端可在综合或 bitstream 能力之外独立挂载纯软件 accelerator host。 */
trait AcceleratorHostConstruction extends Construction {
  protected def configuredAcceleratorHost: AcceleratorHostConfig

  final def acceleratorHostConfig: AcceleratorHostConfig = configuredAcceleratorHost
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

/** 不依赖运行宿主的 FPGA 工具链合同。 */
trait FpgaToolchainConstruction {
  protected def configuredFpga: FpgaToolchainConfig

  final def fpgaToolchainConfig: FpgaToolchainConfig =
    ConstructionValidation.fpgaToolchain(configuredFpga)
}

/** FPGA 运行构造；完整终端预设必须同时提供 NEMU 与 FPGA 工具链配方。 */
trait FpgaConstruction extends HostConstruction with FpgaToolchainConstruction {

  final override def nemuConfig: NemuHostConfig = {
    ConstructionValidation.fpga(configuredNemu, fpgaToolchainConfig)
    configuredNemu
  }
}

/** 只生成 FPGA 综合资产、不挂载运行宿主的构造。 */
trait FpgaSynthesisConstruction extends Construction with FpgaToolchainConstruction {
  final override protected def configuredCapability: String = "synthesize-only"
}

/** 只生成 FPGA bitstream/XCLBIN，不挂载 NEMU 或 XRT host 的构造。 */
trait FpgaBitstreamConstruction extends Construction with FpgaToolchainConstruction {
  final override protected def configuredCapability: String = "bitstream-only"
}

/** 自动目录用于识别可由 Make 直接选择的完整终端。 */
trait MakeTerminal { self: Construction =>
  def constructionScope: String
  def constructionTarget: String
}
