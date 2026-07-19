package scpu

/** 反射加载自动发现的完整 NPC 构造。 */
object NpcConfigResolver {
  def resolve(defaultShortName: String): (ConfigCatalog.Entry, NpcConstructionConfig) = {
    val requested = ConfigCatalog.selectedName(defaultShortName)
    val entry = ConfigCatalog.resolve(requested, Set("npc"))
    val instance = try {
      Class.forName(entry.className).getDeclaredConstructor().newInstance()
    } catch {
      case error: ReflectiveOperationException =>
        throw new IllegalArgumentException(s"Cannot construct NPC configuration ${entry.className}: ${error.getMessage}", error)
    }
    instance match {
      case config: NpcConstructionConfig => entry -> config
      case _ => throw new IllegalArgumentException(
        s"NPC configuration ${entry.className} must extend NpcConstructionConfig"
      )
    }
  }
}
