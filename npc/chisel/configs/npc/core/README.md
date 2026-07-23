# NPC Core 成品

本目录保存 L1 NPC 的非终端完整成品。它们可以直接放入 SoC、FPGA 或板卡的 CDE `++` 链，
但没有挂载 terminal 层 trait，不能被 `make config=` 单独选择。

| 文件 | 层级 | 内容 | 上层使用方式 |
| --- | --- | --- | --- |
| `ArchitectureCore.scala` | 核心架构 | 完整 XLEN 与 ISA 扩展组合 | `new Rv64IMFZicsrConfig` |
| `PerformCore.scala` | 核心性能 | 完整流水线、互锁与前递组合 | `new PipelineDualFwdPerformConfig` |
| `SimulationCore.scala` | 本地终端硬件 | 每个 NPC 运行终端对应一个完整、具名的硬件组合 | `new SimulationCoreConfig` |
| `IntegrationCore.scala` | 集成接口 | 为 SoC、FPGA 与板卡准备的完整 L1 核 | `new ExternalAxiConfig`、`new FpgaConfig` |
| `ConstructionConfig.scala` | L1 成品协议 | 把完整 core 发布到 CDE 图 | 终端和集成 core 继承 |
| `CheckCore.scala` | 检查构造 | Scala/RTL 检查使用，不进入 Make 目录 | `new PipelineCheckConfig` |

新增分支预测、乱序等性能能力时，先在 `../base/` 定义局部片段，再在
`PerformCore.scala` 定义完整性能策略，并在 `SimulationCore.scala` 形成终端可直接调用的完整组合。
终端 `Configs.scala` 只选择这个硬件 core、挂载一个 terminal 层 trait，并提供其运行配方。
