package npc

import org.chipsalliance.cde.config.{Config => CDEConfig}

/** 反射加载自动发现的独立 SPMV 仿真终端。 */
object SpmvConfigResolver {
  def resolve(defaultShortName: String):
      (ConfigCatalog.Entry, CDEConfig with SpmvSimulationConstruction with MakeTerminal) = {
    val requested = ConfigCatalog.selectedName(defaultShortName)
    val entry = ConfigCatalog.resolve(requested, Set("spmv"))
    val instance = try {
      Class.forName(entry.className).getDeclaredConstructor().newInstance()
    } catch {
      case error: ReflectiveOperationException =>
        throw new IllegalArgumentException(
          s"无法构造 SPMV 配置 ${entry.className}：${error.getMessage}", error)
    }
    instance match {
      case config: CDEConfig with SpmvSimulationConstruction with MakeTerminal =>
        require(config.isInstanceOf[LocalSpmvSimulationTerminal] ||
          config.isInstanceOf[LocalSpmvPerformanceMonitorTerminal],
          s"SPMV 配置 ${entry.className} 必须直接挂载 SPMV simulation terminal")
        require(config.constructionScope == entry.scope && config.constructionTarget == entry.target)
        entry -> config
      case _ => throw new IllegalArgumentException(
        s"SPMV 配置 ${entry.className} 必须直接挂载 SPMV simulation terminal")
    }
  }
}
