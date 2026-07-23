# NPC Core 依赖片段

本目录只放 `core/` 与 NPC 终端构造共同依赖的基类、参数键和局部覆盖片段。所有类保持
`package scpu`，因此移动目录不改变 Scala FQCN、CDE 键或 Make 反射规则。

| 文件 | 内容 | 可直接用于 `++` |
| --- | --- | --- |
| `ConfigBase.scala` | L1 组合协议、CDE 核心键与终端构造基类 | `ConstructionConfig`、`BaseConfig` |
| `ArchitectureConfigs.scala` | XLEN/M/F/Zicsr 的局部片段 | `WithXlenConfig`、`WithMExtensionConfig`、`WithFloatingPointConfig` |
| `PerformConfigs.scala` | 流水线/互锁/前递局部片段 | `WithPipelineConfig`、`WithNpcIdForwardingConfig`、`WithNpcExecuteForwardingConfig` |
| `MemoryConfigs.scala` | 主存窗口与复位向量 | `WithBareMainMemoryConfig`、`WithSoCMainMemoryConfig`、`WithFpgaMainMemoryConfig` |
| `OperatorConfigs.scala` | 算术后端与 IP 时序 | `WithModelComputeConfig`、`WithFpgaComputeConfig`、`WithDefaultArithmeticTimingConfig` |
| `InterfaceConfigs.scala` | 顶层调试、派发控制和 AXI | `WithTopDebugConfig`、`WithDispatchControlConfig`、`WithExternalAxiConfig` |

仅用于 Make 的终端无参 `Npc...Config` 必须定义在上一级 `npc/Configs.scala`，避免自动目录把局部
组合或检查构造误识别成公开构造。
