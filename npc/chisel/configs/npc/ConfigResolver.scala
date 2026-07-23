package scpu

/** 反射加载自动发现的完整 NPC 构造。 */
object ConfigResolver {
  def resolve(defaultShortName: String): (ConfigCatalog.Entry, ConstructionConfig with NemuSimulationConstructionConfig) = {
    val requested = ConfigCatalog.selectedName(defaultShortName)
    val entry = ConfigCatalog.resolve(requested, Set("npc"))
    val instance = try {
      Class.forName(entry.className).getDeclaredConstructor().newInstance()
    } catch {
      case error: ReflectiveOperationException =>
        throw new IllegalArgumentException(s"Cannot construct NPC configuration ${entry.className}: ${error.getMessage}", error)
    }
    instance match {
      case config: ConstructionConfig with MakeTerminalConfig with NemuSimulationConstructionConfig =>
        require(config.constructionScope == entry.scope && config.constructionTarget == entry.target,
          s"NPC configuration ${entry.className} terminal marker conflicts with catalog metadata")
        entry -> config
      case _ => throw new IllegalArgumentException(
        s"NPC configuration ${entry.className} must be a local NEMU simulation ConstructionConfig terminal"
      )
    }
  }
}
