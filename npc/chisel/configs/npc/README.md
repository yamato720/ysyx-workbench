# L1：NPC 核心构造

本目录是架构第 1 层：仅依赖 CDE 参数库、但不依赖 Rocket 或板卡的 NPC 核心参数构造。所有目标
都从本层取得 `NpcConfig`，因此本层不是可选项；某个构造也可以在本层结束，例如独立 NPC 的本地
仿真。

本目录只保留可由 Make 反射的 NPC 终端成品与解析器。核心形态位于 `core/`，它的基础片段统一放入
`core/dependencies/`。`common/` 只提供可跨 CPU/加速器复用的算子 IP 描述，不包含 NPC 的 ISA、流水线、
存储或接口策略。

## 文件职责

| 文件 | 职责 | 是否是上层可复用成品 |
| --- | --- | --- |
| `Configs.scala` | 完整的无参 NPC 运行终端与检查构造 | 运行终端可由 Make 选择；检查构造仅供测试 |
| `ConfigResolver.scala` | 已登记 NPC Config 的反射解析和类型校验 | 否；仅供构造入口使用 |
| `core/ArchitectureCore.scala` | 完整 XLEN/ISA 架构成品 | 是；`NpcRv...Config` 架构层成品 |
| `core/PerformCore.scala` | 完整流水线性能成品 | 是；`Npc...PerformConfig` 性能层成品 |
| `core/IntegrationCore.scala` | 供 SoC、FPGA 与板卡复用的完整 L1 集成核 | 是；只能放入更高层 CDE `++` 链 |
| `core/dependencies/` | 上述 core 与终端构造共用的基类和局部覆盖片段 | 是；仅用于 Scala `++` 链，不可由 Make 直接选择 |

## 可增加的特性

| 特性 | 可直接复制到 `++` 链的名称 | 添加位置 | 是否可选 |
| --- | --- | --- | --- |
| I 基础 ISA | `new Rv32IConfig` 或 `new Rv64IConfig` | `core/ArchitectureCore.scala` | 是；显式关闭 M/F/Zicsr |
| I_Zicsr ISA | `new Rv32IZicsrConfig` 或 `new Rv64IZicsrConfig` | `core/ArchitectureCore.scala` | 是；与其他完整 ISA 预设二选一 |
| IM_Zicsr ISA | `new Rv32IMZicsrConfig` 或 `new Rv64IMZicsrConfig` | `core/ArchitectureCore.scala` | 是；与其他完整 ISA 预设二选一 |
| IMF_Zicsr ISA | `new Rv32IMFZicsrConfig` 或 `new Rv64IMFZicsrConfig` | `core/ArchitectureCore.scala` | 是；与其他完整 ISA 预设二选一 |
| 单项 ISA 覆盖 | `new WithXlenConfig(32)`、`new WithMExtensionConfig`、`new WithoutMExtensionConfig`、`new WithFloatingPointConfig`、`new WithoutFloatingPointConfig`、`new WithZicsrConfig`、`new WithoutZicsrConfig` | `core/dependencies/ArchitectureConfigs.scala` | 是；F 需要 Zicsr |
| 无流水线性能基线 | `new ScalarPerformConfig` | `core/PerformCore.scala` | 是；与其他完整性能预设二选一 |
| 流水线无前递 | `new PipelinePerformConfig` | `core/PerformCore.scala` | 是；与其他完整性能预设二选一 |
| 流水线仅 ID 前递 | `new PipelineIdFwdPerformConfig` | `core/PerformCore.scala` | 是；与其他完整性能预设二选一 |
| 流水线仅 EX 前递 | `new PipelineExFwdPerformConfig` | `core/PerformCore.scala` | 是；与其他完整性能预设二选一 |
| 流水线 ID/EX 双前递 | `new PipelineDualFwdPerformConfig` | `core/PerformCore.scala` | 是；与其他完整性能预设二选一 |
| 单项性能覆盖 | `new WithPipelineConfig`、`new WithoutPipelineConfig`、`new WithInterlockConfig`、`new WithoutInterlockConfig`、`new WithNpcIdForwardingConfig`、`new WithNpcExecuteForwardingConfig` | `core/dependencies/PerformConfigs.scala` | 是；用于在完整预设上精确覆盖 |
| 主存窗口 | `new WithBareMainMemoryConfig`、`new WithSoCMainMemoryConfig`、`new WithFpgaMainMemoryConfig` | `core/dependencies/MemoryConfigs.scala` | 必需且按目标选择 |
| 算术后端/时序 | `new WithModelComputeConfig`、`new WithFpgaComputeConfig`、`new WithDefaultArithmeticTimingConfig` | `core/dependencies/OperatorConfigs.scala` | 必需且按目标选择 |
| 可复用算子 IP 时序数据 | `OperatorIpTimingConfig(...)`、`OperatorIpTimingConfig.Default` | `../common/OperatorIpConfigs.scala` | 是；由本文件的算子片段消费 |
| 外部 AXI 与调试 | `new WithExternalAxiConfig`、`new WithTopDebugConfig`、`new WithDispatchControlConfig` | `core/dependencies/InterfaceConfigs.scala` | 是 |
| 乘除法完成延迟 | `new WithMulDivCompletionConfig(37)` | `core/dependencies/OperatorConfigs.scala` | 是 |
| 完整 FPGA NPC 预设 | `new FpgaConfig` | `core/IntegrationCore.scala` | 是 |
| 完整外部 AXI NPC 预设 | `new ExternalAxiConfig` | `core/IntegrationCore.scala` | 是 |
| RV64IMF 无流水线本地仿真构造 | `new FullIsa64NoPipelineSimulationConfig` | `Configs.scala` | 是 |
| RV64IMF 流水线无前递本地仿真构造 | `new FullIsa64PipelineNoForwardingSimulationConfig` | `Configs.scala` | 是 |
| RV64IMF 流水线双路径前递本地仿真构造 | `new FullIsa64PipelineDualForwardingSimulationConfig` | `Configs.scala` | 是 |
| RV64IMF FPGA 双路径前递成品 | `new FullIsa64PipelineDualForwardingFpgaConfig` | `core/IntegrationCore.scala` | 是 |
| 新 NPC 特性 | `class WithMyFeatureConfig`（命名模板，需先实现） | `core/dependencies/` 的对应领域文件 | 是 |
| Rocket 外设、板卡引脚 | 无；不在本层添加 | 分别转入 L2/L3/L4 | 不适用 |

新增局部片段后，稳定的架构/性能组合应提升到 `core/`；供上层复用的完整集成核放入
`core/IntegrationCore.scala`。只有 `Configs.scala` 中混入终端 marker 的运行类会进入
`make config-list`。

## 供上层使用的成品

`core/IntegrationCore.scala` 是本层专门放置供上层复用的完整集成核的文件。FPGA 默认选择
`FpgaConfig`；通用 SoC 默认选择 `ExternalAxiConfig`。完整 L1 Config 自身提供 `NpcCoreConfigKey`，因此更高层可直接
把它放在 CDE 链最左侧，从而覆盖板卡或 SoC 默认核心。

完整 Config 直接并列选择 `core/ArchitectureCore.scala` 的架构成品和
`core/PerformCore.scala` 的性能成品，再按目标补充存储、计算和接口。未来加入分支预测或乱序时，
终端仍可直观看到实际选择的 ISA 与性能策略；这些片段均不读取 Make、JVM property 或环境变量。
