package scpu

import org.chipsalliance.cde.config.{Config => CDEConfig, Field, Parameters}

/** CDE 图中的已完成 NPC 核心。
  *
  * L1 NPC Config 直接提供此键，因此 L2 SoC 与 L4 FPGA Config 可以把完整 NPC
  * 成品直接放进 `++` 链，无需再手工抽取 `.config`。
  */
case object NpcCoreConfigKey extends Field[NpcConfig](NpcConfig())

/** 用已完成的 `NpcConfig` 覆盖 CDE 图中的核心。
  *
  * 完整的 L1 `ConstructionConfig` 已经自动提供相同的键。此类只保留给确实需要
  * 在 Scala 中动态构造裸 `NpcConfig` 的低层调用者。
  */
class WithNpcCoreConfig(npcConfig: NpcConfig) extends CDEConfig((_, _, _) => {
  case NpcCoreConfigKey => npcConfig
})

/** 无依赖 NPC 参数的组合片段。
  *
  * `left ++ right` 先应用右侧，再由左侧覆盖同一参数。
  */
trait ConfigFragment {
  private[scpu] def applyTo(base: NpcConfig): NpcConfig

  final def ++(base: ConfigFragment): ConfigFragment =
    new ConfigComposition(this, base)

  final def build: NpcConfig = applyTo(NpcConfig())
}

private final class ConfigComposition(
  overrideFragment: ConfigFragment,
  baseFragment: ConfigFragment
) extends ConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig =
    overrideFragment.applyTo(baseFragment.applyTo(base))
}

/** 可复用的 NPC 组合成品；可继续置入更高层的 `++` 链。 */
abstract class ConfigBundle(layers: ConfigFragment) extends ConfigFragment {
  override final private[scpu] def applyTo(base: NpcConfig): NpcConfig = layers.applyTo(base)
}

/** 将若干 L1 片段封装为可直接 `build`，也可直接叠加到 CDE 图的命名 NPC 构造。
  *
  * L1 只依赖 CDE 参数库；不依赖 Rocket、Diplomacy、ysyxSoC 或任何板卡实现。
  * CDE 与 L1 片段的 `++` 都保持左侧优先。
  */
abstract class ConstructionConfig(
  layers: ConfigFragment
) extends CDEConfig((_, _, _) => {
  case NpcCoreConfigKey => layers.build
}) with ConfigFragment {
  override final private[scpu] def applyTo(base: NpcConfig): NpcConfig = layers.applyTo(base)
  final lazy val config: NpcConfig = build
}

/** NPC 参数的起点，不修改 `NpcConfig()` 的默认值。 */
class BaseConfig extends ConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig = base
}
