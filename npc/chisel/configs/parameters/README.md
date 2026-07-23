# 构造输入数据

本目录不是独立架构层，而是 L1-L4 共用的无依赖数据定义。它始终随使用者编译，但不单独生成硬件。

## 可增加的特性

| 特性 | 可直接引用的名称 | 添加位置 | 是否可选 |
| --- | --- | --- | --- |
| ISA 或算术实现参数 | `ISAConfig`、`OperatorConfig` | `IsaParameters.scala` | 是 |
| 流水线、内存、调试、AXI 参数 | `PipelineConfig`、`MemoryConfig`、`DebugConfig`、`AxiConfig` | `PlatformParameters.scala` | 是 |
| 完整核心参数值 | `NpcConfig` | `PlatformParameters.scala` | L1 必需 |
| 构造 profile 数据 | `ConstructionProfile.values(...)` | `ConstructionProfile.scala` | Make 可用 Config 必需 |
| 终端布局与自动目录 | `ConfigCatalogGenerator` | `ConfigCatalogGenerator.scala` | 只接受领域根部 `Configs.scala` 中的终端 |
| 底层构造 trait | 无；见 `common/base/ConstructionTraits.scala` | `../common/base/` | 只供 core trait 组合 |
| 可直挂终端 trait | 无；见 `common/core/TerminalTraits.scala` | `../common/core/` | 每个完整终端 Config 挂载一个 |
| CDE 键、Rocket 参数、板卡物理信息 | 无；不在本目录添加 | `ysyx/`、L3 或 L4 | 不适用 |

完整 NPC Config 的底层片段和具名成品分别位于同级 `npc/base/` 与 `npc/core/`；跨领域数据位于
`common/base/`。本目录不再持有 NPC 专属构造预设。公开 Make 不能覆盖这些 Scala 固定字段。
