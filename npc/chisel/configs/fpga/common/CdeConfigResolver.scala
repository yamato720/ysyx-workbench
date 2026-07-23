package scpu.fpga

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.scpu.{ConfigCatalog, FpgaConstruction, HostConstruction, LocalSocTerminal, MakeTerminal}
import _root_.scpu.{U55cNpcTerminal, U55cSocTerminal, Zcu102NpcTerminal, Zcu102SocTerminal}

/** 反射加载自动发现的完整 CDE 构造。 */
object CdeConfigResolver {
  def resolve(
    defaultShortName: String,
    allowedScopes: Set[String]
  ): (ConfigCatalog.Entry, CDEConfig with HostConstruction with MakeTerminal) = {
    val requested = ConfigCatalog.selectedName(defaultShortName)
    val entry = ConfigCatalog.resolve(requested, allowedScopes)
    val instance = try {
      Class.forName(entry.className).getDeclaredConstructor().newInstance()
    } catch {
      case error: ReflectiveOperationException =>
        throw new IllegalArgumentException(s"Cannot construct CDE configuration ${entry.className}: ${error.getMessage}", error)
    }
    instance match {
      case config: CDEConfig with MakeTerminal with HostConstruction =>
        require(config.constructionScope == entry.scope && config.constructionTarget == entry.target,
          s"CDE configuration ${entry.className} terminal trait conflicts with catalog metadata")
        entry.scope match {
          case "soc" => require(config.isInstanceOf[LocalSocTerminal],
            s"SoC configuration ${entry.className} must directly mount LocalSocTerminal")
          case "fpga" =>
            val matchesPreset = (entry.board, entry.target) match {
              case (Some("u55c"), "NPC") => config.isInstanceOf[U55cNpcTerminal]
              case (Some("u55c"), "SOC") => config.isInstanceOf[U55cSocTerminal]
              case (Some("zcu102"), "NPC") => config.isInstanceOf[Zcu102NpcTerminal]
              case (Some("zcu102"), "SOC") => config.isInstanceOf[Zcu102SocTerminal]
              case _ => false
            }
            require(config.isInstanceOf[FpgaConstruction] && matchesPreset,
              s"FPGA configuration ${entry.className} must mount its matching board/target terminal preset")
          case scope => throw new IllegalArgumentException(s"Unsupported CDE terminal scope $scope")
        }
        entry -> config
      case _ => throw new IllegalArgumentException(
        s"CDE configuration ${entry.className} must be a NEMU-running CDE Config terminal"
      )
    }
  }
}
