# L1：NPC 核心构造

本目录是架构第 1 层：无 CDE、Rocket 或板卡依赖的 NPC 核心参数构造。所有目标都从本层取得
`NpcConfig`，因此本层不是可选项；但某个构造可以在本层结束，例如独立 NPC 或 DPI 仿真。

## 文件职责

| 文件 | 职责 | 是否是上层可复用成品 |
| --- | --- | --- |
| `NpcConfigBase.scala` | `++` 组合协议、`NpcConstructionConfig` 和零修改的 `BaseNpcConfig` | 否；仅为其他配置提供基础机制 |
| `NpcConfigFragments.scala` | `With...Config` 局部覆盖层 | 否；供成品或调用者叠加 |
| `NpcConfigs.scala` | 完整的无参 `Npc...Config` 构造 | 是；这是上层选择、复用或覆盖核心的入口 |

## 可增加的特性

| 特性 | 可直接复制到 `++` 链的名称 | 添加位置 | 是否可选 |
| --- | --- | --- | --- |
| M 扩展 | `new WithMExtensionConfig` | `NpcConfigFragments.scala` | 是 |
| F 扩展 | `new WithFloatingPointConfig` | `NpcConfigFragments.scala` | 是 |
| 启用 Zicsr | `new WithZicsrConfig` | `NpcConfigFragments.scala` | 是（默认开启） |
| 关闭 Zicsr | `new WithoutZicsrConfig` | `NpcConfigFragments.scala` | 是；同时关闭 F，CSR 指令变为非法指令 |
| 流水线 | `new WithPipelineConfig` | `NpcConfigFragments.scala` | 是 |
| XLEN | `new WithNpcXlenConfig(32)` 或 `new WithNpcXlenConfig(64)` | `NpcConfigFragments.scala` | 是 |
| 外部 AXI | `new WithExternalAxiConfig` | `NpcConfigFragments.scala` | 是 |
| 顶层调试 IO | `new WithTopDebugConfig` | `NpcConfigFragments.scala` | 是 |
| FPGA 派发控制 | `new WithDispatchControlConfig` | `NpcConfigFragments.scala` | 是 |
| 乘除法完成延迟 | `new WithMulDivCompletionConfig(37)` | `NpcConfigFragments.scala` | 是 |
| 固定 ABI 预设 | `new WithNpcElaborationSettings(NpcBuildSettings.NpcStandalone64)` | `NpcConfigFragments.scala` | 完整终端 Config 必需 |
| 完整 FPGA NPC 预设 | `new NpcFpgaConfig` | `NpcConfigs.scala` | 是 |
| 完整外部 AXI NPC 预设 | `new NpcExternalAxiConfig` | `NpcConfigs.scala` | 是 |
| RV64IMF 无流水线性能构造 | `new NpcFullIsa64NoPipelineDpiConfig` | `NpcConfigs.scala` | 是 |
| RV64IMF 流水线无前递性能构造 | `new NpcFullIsa64PipelineNoForwardingDpiConfig` | `NpcConfigs.scala` | 是 |
| RV64IMF 流水线双路径前递性能构造 | `new NpcFullIsa64PipelineDualForwardingDpiConfig` | `NpcConfigs.scala` | 是 |
| RV64IMF FPGA 双路径前递成品 | `new NpcFullIsa64PipelineDualForwardingFpgaConfig` | `NpcConfigs.scala` | 是 |
| 新特性 | `class WithMyFeatureConfig`（命名模板，需先实现） | `NpcConfigFragments.scala` | 是 |
| CDE 键、Rocket 外设、板卡引脚 | 无；不在本层添加 | 分别转入 L2/L3/L4 | 不适用 |

新增片段后，只有需要稳定复用的组合才应提升为 `NpcConfigs.scala` 中的完整成品。

## 供上层使用的成品

`NpcConfigs.scala` 是本层专门放置完整成品的文件。FPGA 默认选择 `NpcFpgaConfig`；通用 SoC
默认选择 `NpcExternalAxiConfig`。更高层可将一个已完成的 NPC 通过 L3 的 `WithNpcCoreConfig`
放在 CDE 链最左侧，从而覆盖板卡或 SoC 默认核心。

`WithNpcElaborationSettings` 把命名 Scala 预设中的 XLEN、F、流水线、内存和算术时序写入构造。
它只应出现在完整 Config 的可追踪组合链中，不从 Make 或环境读取参数。
