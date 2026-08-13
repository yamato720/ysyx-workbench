package npc

import org.chipsalliance.cde.config.{Config => CDEConfig, Parameters}
import _root_.fpga.FpgaConfigParameters

/** 反射加载自动发现的完整 CDE 构造。 */
object CdeConfigResolver {
  def resolve(
    defaultShortName: String,
    allowedScopes: Set[String]
  ): (ConfigCatalog.Entry, CDEConfig with Construction with MakeTerminal) = {
    val requested = ConfigCatalog.selectedName(defaultShortName)
    val entry = ConfigCatalog.resolve(requested, allowedScopes)
    val instance = try {
      Class.forName(entry.className).getDeclaredConstructor().newInstance()
    } catch {
      case error: ReflectiveOperationException =>
        throw new IllegalArgumentException(s"Cannot construct CDE configuration ${entry.className}: ${error.getMessage}", error)
    }
    instance match {
      case config: CDEConfig with MakeTerminal with Construction =>
        require(config.constructionScope == entry.scope && config.constructionTarget == entry.target,
          s"CDE configuration ${entry.className} terminal trait conflicts with catalog metadata")
        entry.scope match {
          case "soc" => require(config.isInstanceOf[HostConstruction] &&
            config.isInstanceOf[LocalSocTerminal] &&
            config.isInstanceOf[NemuSimulationIpTerminal],
            s"SoC configuration ${entry.className} must directly mount LocalSocTerminal and NemuSimulationIpTerminal")
          case "spmv" => require(config.isInstanceOf[AcceleratorHostConstruction] &&
            config.capability == "run" &&
            !config.isInstanceOf[HostConstruction] && !config.isInstanceOf[IpConstruction],
            s"Accelerator configuration ${entry.className} must mount a CPU-free run terminal")
          case "fpga" =>
            val validConstruction = entry.target match {
              case "SPMV" => (config.isInstanceOf[FpgaSynthesisConstruction] ||
                config.isInstanceOf[FpgaBitstreamConstruction]) &&
                config.isInstanceOf[AcceleratorHostConstruction] &&
                !config.isInstanceOf[HostConstruction] && !config.isInstanceOf[IpConstruction]
              case _ => config.isInstanceOf[FpgaConstruction] && config.isInstanceOf[FpgaIpTerminal]
            }
            require(validConstruction,
              s"FPGA configuration ${entry.className} must mount the construction terminal required by target ${entry.target}")
          case scope => throw new IllegalArgumentException(s"Unsupported CDE terminal scope $scope")
        }
        entry -> config
      case _ => throw new IllegalArgumentException(
        s"CDE configuration ${entry.className} must be a complete CDE Config terminal"
      )
    }
  }
}

/** SoC 描述器使用的中立 FPGA profile 视图，不向 ysyxSoC 暴露板卡实现类型。 */
final case class CdeFpgaPlatformProfile(
  board: String,
  clockMHz: Int,
  memoryHostBase: Long,
  controlBase: Long,
  mailboxBase: Long
)

object CdeConstructionParameters {
  def npcCoreConfig(implicit parameters: Parameters): NpcConfig = parameters(NpcCoreConfigKey)

  def fpgaPlatform(implicit parameters: Parameters): Option[CdeFpgaPlatformProfile] =
    FpgaConfigParameters.board.map { _ =>
      val platform = FpgaConfigParameters.platform
      CdeFpgaPlatformProfile(
        board = platform.board.name,
        clockMHz = platform.clockMHz,
        memoryHostBase = platform.memoryHostBase,
        controlBase = platform.controlBase,
        mailboxBase = platform.mailboxBase
      )
    }

  def fpgaIpAttachment(implicit parameters: Parameters): Option[FpgaIpAttachment] =
    FpgaConfigParameters.board.map(_ => FpgaConfigParameters.ipAttachment)
}
