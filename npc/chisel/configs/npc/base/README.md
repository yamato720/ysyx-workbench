# NPC Base 底层片段

本目录只放 `core/` 依赖的参数键、组合协议和局部覆盖片段；终端不得在自己的组合体内直接选择
这些原子片段。所有类保持
`package scpu`，因此移动目录不改变 Scala FQCN、CDE 键或 Make 反射规则。

| 文件 | 内容 | 可直接用于 `++` |
| --- | --- | --- |
| `ConfigBase.scala` | L1 组合协议与 CDE 核心键 | `ConfigFragment`、`ConfigBundle`、`BaseConfig` |
| `ArchitectureConfigs.scala` | XLEN/M/F/Zicsr 的局部片段 | `WithXlenConfig`、`WithMExtensionConfig`、`WithFloatingPointConfig` |
| `PerformConfigs.scala` | 流水线/互锁/前递局部片段 | `WithPipelineConfig`、`WithNpcIdForwardingConfig`、`WithNpcExecuteForwardingConfig` |
| `MemoryConfigs.scala` | 主存窗口与复位向量 | `WithBareMainMemoryConfig`、`WithSoCMainMemoryConfig`、`WithFpgaMainMemoryConfig` |
| `OperatorConfigs.scala` | 算术后端与 IP 时序 | `WithModelComputeConfig`、`WithFpgaComputeConfig`、`WithDefaultArithmeticTimingConfig` |
| `InterfaceConfigs.scala` | 顶层调试、派发控制和 AXI | `WithTopDebugConfig`、`WithDispatchControlConfig`、`WithExternalAxiConfig` |

底层片段只能由 `core/` 组合。仅用于 Make 的终端无参 Config 必须定义在上一级
`npc/Configs.scala`，且只引用 `core/` 的完整成品与构造协议。
