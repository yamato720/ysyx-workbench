# NPC Core 成品

本目录保存 L1 NPC 的非终端完整成品。它们可以直接放入 SoC、FPGA 或板卡的 CDE `++` 链，
但没有 `MakeTerminalConfig` 行为，不能被 `make config=` 单独选择。

| 文件 | 层级 | 内容 | 上层使用方式 |
| --- | --- | --- | --- |
| `ArchitectureCore.scala` | 核心架构 | 完整 XLEN 与 ISA 扩展组合 | `new Rv64IMFZicsrConfig` |
| `PerformCore.scala` | 核心性能 | 完整流水线、互锁与前递组合 | `new PipelineDualFwdPerformConfig` |
| `IntegrationCore.scala` | 集成接口 | 为 SoC、FPGA 与板卡准备的完整 L1 核 | `new ExternalAxiConfig`、`new FpgaConfig` |

新增分支预测、乱序等性能能力时，先在 `dependencies/` 定义局部片段，再在
`PerformCore.scala` 定义完整性能策略。终端构造直接并列选择架构与性能成品，避免中间组合层
遮蔽实际 ISA 或流水线策略。
