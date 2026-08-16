package npc

/** 无依赖 NPC 参数的组合片段。
  *
  * `left ++ right` 先应用右侧，再由左侧覆盖同一参数。
  */
trait ConfigFragment {
  private[npc] def applyTo(base: NpcConfig): NpcConfig

  final def ++(base: ConfigFragment): ConfigFragment =
    new ConfigComposition(this, base)

  final def build: NpcConfig = applyTo(NpcConfig()).validated
}

private final class ConfigComposition(
  overrideFragment: ConfigFragment,
  baseFragment: ConfigFragment
) extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig =
    overrideFragment.applyTo(baseFragment.applyTo(base))
}

/** 可复用的 NPC 组合成品；可继续置入更高层的 `++` 链。 */
abstract class ConfigBundle(layers: ConfigFragment) extends ConfigFragment {
  override final private[npc] def applyTo(base: NpcConfig): NpcConfig = layers.applyTo(base)
}

/** NPC 参数的起点，不修改 `NpcConfig()` 的默认值。 */
class BaseConfig extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig = base
}
