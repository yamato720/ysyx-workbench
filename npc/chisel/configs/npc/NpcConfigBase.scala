package scpu

/** 无依赖 NPC 配置的组合片段。
  *
  * `left ++ right` 先应用右侧，再由左侧覆盖同一参数。
  */
trait NpcConfigFragment {
  private[scpu] def applyTo(base: NpcConfig): NpcConfig

  final def ++(base: NpcConfigFragment): NpcConfigFragment =
    new NpcConfigComposition(this, base)

  final def build: NpcConfig = applyTo(NpcConfig())
}

private final class NpcConfigComposition(
  overrideFragment: NpcConfigFragment,
  baseFragment: NpcConfigFragment
) extends NpcConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig =
    overrideFragment.applyTo(baseFragment.applyTo(base))
}

/** 将若干片段封装为可直接 `build` 的命名 NPC 构造，同时仍可作为更高层的组合片段。
  */
trait MakeConstructionConfig {
  def capability: String
}

abstract class NpcConstructionConfig(
  layers: NpcConfigFragment,
  final override val capability: String
) extends NpcConfigFragment with MakeConstructionConfig {
  override final private[scpu] def applyTo(base: NpcConfig): NpcConfig = layers.applyTo(base)
  final lazy val config: NpcConfig = build
}

/** NPC 参数的起点，不修改 `NpcConfig()` 的默认值。 */
class BaseNpcConfig extends NpcConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig = base
}
