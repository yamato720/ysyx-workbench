package scpu.fpga

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.scpu.{ConfigCatalog, FpgaConstructionConfig, HostConstructionConfig, MakeTerminalConfig, NemuSimulationConstructionConfig}

/** 反射加载自动发现的完整 CDE 构造。 */
object CdeConfigResolver {
  def resolve(
    defaultShortName: String,
    allowedScopes: Set[String]
  ): (ConfigCatalog.Entry, CDEConfig with HostConstructionConfig) = {
    val requested = ConfigCatalog.selectedName(defaultShortName)
    val entry = ConfigCatalog.resolve(requested, allowedScopes)
    val instance = try {
      Class.forName(entry.className).getDeclaredConstructor().newInstance()
    } catch {
      case error: ReflectiveOperationException =>
        throw new IllegalArgumentException(s"Cannot construct CDE configuration ${entry.className}: ${error.getMessage}", error)
    }
    instance match {
      case config: CDEConfig with MakeTerminalConfig with HostConstructionConfig =>
        require(config.constructionScope == entry.scope && config.constructionTarget == entry.target,
          s"CDE configuration ${entry.className} terminal marker conflicts with catalog metadata")
        entry.scope match {
          case "soc" => require(config.isInstanceOf[NemuSimulationConstructionConfig],
            s"SoC configuration ${entry.className} must use NemuSimulationConstructionConfig")
          case "fpga" => require(config.isInstanceOf[FpgaConstructionConfig],
            s"FPGA configuration ${entry.className} must use FpgaConstructionConfig")
          case scope => throw new IllegalArgumentException(s"Unsupported CDE terminal scope $scope")
        }
        entry -> config
      case _ => throw new IllegalArgumentException(
        s"CDE configuration ${entry.className} must be a NEMU-running CDE Config terminal"
      )
    }
  }
}
