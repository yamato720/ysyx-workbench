package scpu.fpga

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.scpu.ConfigCatalog

/** 反射加载自动发现的完整 CDE 构造。 */
object CdeConfigResolver {
  def resolve(defaultShortName: String, allowedScopes: Set[String]): (ConfigCatalog.Entry, CDEConfig) = {
    val requested = ConfigCatalog.selectedName(defaultShortName)
    val entry = ConfigCatalog.resolve(requested, allowedScopes)
    val instance = try {
      Class.forName(entry.className).getDeclaredConstructor().newInstance()
    } catch {
      case error: ReflectiveOperationException =>
        throw new IllegalArgumentException(s"Cannot construct CDE configuration ${entry.className}: ${error.getMessage}", error)
    }
    instance match {
      case config: CDEConfig => entry -> config
      case _ => throw new IllegalArgumentException(
        s"CDE configuration ${entry.className} must extend org.chipsalliance.cde.config.Config"
      )
    }
  }
}
