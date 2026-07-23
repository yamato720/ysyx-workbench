package scpu

import org.chipsalliance.cde.config.{Config => CDEConfig}

/** 将若干 L1 片段封装为可直接 `build`，也可直接叠加到 CDE 图的命名 NPC 构造。
  *
  * 终端与更高层集成只引用本层；底层 `ConfigFragment` 组合细节保留在 `base/`。
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
