package npc

import org.chipsalliance.cde.config.{Config => CDEConfig, Field}

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

/** 把最终终端主动挂载的计算 IP 应用到一个完整 NPC 核心。
  *
  * 该 CDE 桥不携带 IP 选择；它只从顶层 `IpConstruction` trait 读取选择，因此 SoC
  * 与 FPGA 图不会在各自的 `++` 链里再次指定 NEMU 或 FPGA 计算后端。
  */
class WithTerminalIpCoreConfig(layers: ConfigFragment) extends CDEConfig((site, _, _) => {
  case NpcCoreConfigKey =>
    (IpConstruction.selection(site).computeUnitConfig ++ layers).build
})

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
