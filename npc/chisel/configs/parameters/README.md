# 构造输入数据

本目录不是独立架构层，而是 L1-L4 共用的无依赖数据定义。它始终随使用者编译，但不单独生成硬件。

## 可增加的特性

| 特性 | 可直接引用的名称 | 添加位置 | 是否可选 |
| --- | --- | --- | --- |
| ISA 或算术实现参数 | `ISAConfig`、`OperatorConfig` | `IsaParameters.scala` | 是 |
| 流水线、内存、调试、AXI 参数 | `PipelineConfig`、`MemoryConfig`、`DebugConfig`、`AxiConfig` | `PlatformParameters.scala` | 是 |
| 完整核心参数值 | `NpcConfig` | `PlatformParameters.scala` | L1 必需 |
| 命名 ABI 预设 | `NpcBuildSettings.NpcStandalone64`、`NpcBuildSettings.NpcPipeline64`、`NpcBuildSettings.YsyxSimulation32`、`NpcBuildSettings.FpgaNpc32`、`NpcBuildSettings.FpgaSoc32` | `BuildSettings.scala` | 完整终端 Config 必需 |
| 新固定 ABI | `val MyConstruction = NpcBuildSettings(...)`（命名模板） | `BuildSettings.scala` | 是 |
| 规范化构造描述 | `ConstructionProfile.values(...)` | `ConstructionProfile.scala` | Make 可用 Config 必需 |
| CDE 键、Rocket 参数、板卡物理信息 | 无；不在本目录添加 | `platform/`、L2/L3 或 L4 | 不适用 |

`NpcBuildSettings` 不读取命令行、JVM property 或环境变量。完整终端 Config 选择一个命名预设，
再通过 L1 的 `WithNpcElaborationSettings` 固定到参数图；公开 Make 不能覆盖其中字段。
