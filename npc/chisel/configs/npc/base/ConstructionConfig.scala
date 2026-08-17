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

/** 把最终终端主动挂载的计算后端应用到一个完整 NPC 核心。
  *
  * 该 CDE 桥不携带 IP 选择或算子时序；它只从顶层 `IpConstruction` trait 读取
  * Builtin/FPGA 后端，默认时序作为最右侧 Config 基线，可由 `++` 或板卡
  * attachment 覆盖。
  */
class WithTerminalIpCoreConfig(layers: ConfigFragment) extends CDEConfig((site, _, _) => {
  case NpcCoreConfigKey =>
    (IpConstruction.selection(site).computeUnitConfig ++
      layers ++
      new WithDefaultArithmeticTimingConfig).build
})

/** 把 NPC 参数片段接入 CDE 图，并发布完整的 `NpcCoreConfigKey`。
  *
  * 终端与更高层集成只引用本层；底层 `ConfigFragment` 组合细节保留在 `base/`。
  * CDE 与 L1 片段的 `++` 都保持左侧优先。该桥可直接实例化，终端只需在外层
  * 混入对应的 scope、host 和 IP trait。
  */
class ConstructionConfig(
  layers: ConfigFragment
) extends CDEConfig((site, _, _) => {
  case NpcCoreConfigKey =>
    (IpConstruction.selection(site).computeUnitConfig ++
      layers ++
      new WithDefaultArithmeticTimingConfig).build
}) with ConfigFragment {
  private lazy val mountedLayers: ConfigFragment =
    IpConstruction.selection(this).computeUnitConfig ++
      layers ++
      new WithDefaultArithmeticTimingConfig

  override final private[npc] def applyTo(base: NpcConfig): NpcConfig = mountedLayers.applyTo(base)
  final lazy val config: NpcConfig = build
}
