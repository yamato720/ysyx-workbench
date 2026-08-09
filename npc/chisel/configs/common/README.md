# 公共数据与终端 trait

本目录放置不属于 NPC、SoC 或具体板卡层的共享描述，并遵守 `base -> core -> 根部终端文件` 的
分层。`base/` 保存跨目标的底层数据模型和构造接口，不能由终端直接挂载；`core/` 保存可复用的检查
行为和工具链配方；Make 终端预设和计算单元 IP 终端 trait 分别独立为根部
`TerminalTraits.scala` 与 `IpTerminalTraits.scala`。本目录本身不定义 Make 终端 Config，因此没有
`Configs.scala`。

| 目录 | 职责 | 终端使用方式 |
| --- | --- | --- |
| `base/OperatorIpConfigs.scala` | 与具体 CPU/SoC/板卡无关的算子路由与时序数据 | 经各领域 `core/` 组合间接使用 |
| `base/IpComputeSelectionTraits.scala` | 计算单元后端和时序的非终端组合合同 | 由 FPGA attachment 与根部 IP terminal trait 复用 |
| `base/FpgaIpAttachmentTraits.scala` | 可挂接到 NPC/SoC 的 FPGA IP 合同与 Xilinx 整数实现 | 由 L4 板卡 Config 选择，经同一 CDE 键供 NPC/SoC 消费 |
| `base/FpgaToolchainConfigModels.scala` | FPGA device/flow/report/runtime 底层字段模型 | 经 `FpgaToolchainConfig` 间接使用 |
| `base/ConstructionTraits.scala` | NEMU host、accelerator host、计算 IP、FPGA 与 Make 终端的底层接口和校验 | 不直接使用；只由 terminal 层 trait 组合 |
| `core/FpgaToolchainConfig.scala` | 可复制的完整 U55C/ZCU102 工具链配方 | 根部终端预设内部绑定 `U55cBase` 或 `Zcu102Base` |
| `core/CheckTraits.scala` | 非 Make 的检查构造行为 | 检查 Config 直接挂载 `CheckOnlyConstruction` |
| `core/TerminalCoreTraits.scala` | 本地、U55C、ZCU102 终端直接需要的运行子项集群 | 根部 Make terminal trait 只继承对应集群 |
| `core/IpTerminalCoreTraits.scala` | FPGA/NEMU 计算单元终端直接需要的子项集群 | 根部 IP terminal trait 只继承对应集群 |
| `TerminalTraits.scala` | 十一种提供 scope 和 target 的 Make 终端预设 | 根部终端协议；每个终端只挂载其中一个 trait |
| `IpTerminalTraits.scala` | `NemuSimulationIpTerminal` 与 `FpgaIpTerminal` 两种计算单元终端 | 由运行 Config 显式混入；不是 Make terminal |

| 名称 | 用途 | 终端可否直接挂载 |
| --- | --- | --- |
| `HostConstruction`、`AcceleratorHostConstruction`、`SpmvHostConstruction`、`NemuSimulationConstruction`、`FpgaConstruction`、`MakeTerminal` | 底层运行接口与约束；SPMV host 额外提供 HTML report 开关 | 否；base trait 只允许 core 组合 |
| `CheckOnlyConstruction` | 仅检查硬件 | 不适用；由检查 Config 直接挂载 |
| `LocalNpcTerminal`、`LocalSocTerminal` | 完整的本地 NPC/SoC 终端预设；默认 `LocalPipelineTrace` | 是；对应终端只挂载其中一个 |
| `U55cNpcTerminal`、`U55cSocTerminal` | 完整的 U55C NPC/SoC 终端预设；默认 U55C NEMU 与工具链配方 | 是；对应终端只挂载其中一个 |
| `U55cNpcPerformanceMonitorTerminal` | U55C 裸 NPC 的 batch-only 性能监测终端预设 | 是；只供 v13 监测 Config 挂载 |
| `U55cSpmvSynthesisTerminal` | U55C SPMV 的 synthesize-only 资产与软件 golden host 终端预设 | 是；只供 FP32 资源探针挂载 |
| `U55cSpmvBitstreamTerminal` | U55C SPMV 的 bitstream-only 资产与软件 golden host 终端预设 | 是；只供 FP64/8-lane 压力探针挂载 |
| `LocalSpmvPerformanceMonitorTerminal` | 单 HBM CSR5 paired-X 的性能监测终端，首个子配置为乘加流水线 HTML | 是；只供 SPMV report Config 挂载 |
| `Zcu102NpcTerminal`、`Zcu102SocTerminal` | 完整的 ZCU102 NPC/SoC 终端预设；默认 ZCU102 NEMU 与工具链配方 | 是；对应终端只挂载其中一个 |

十一种 terminal 层预设 trait 提供运行或综合所需的 FPGA/NEMU/独立加速器配方、自动目录身份、scope 和 target；一个终端只挂载一个，且
不得越过 terminal 层直接混入 base 构造 trait。公共构造 trait 名称不加 `Trait` 后缀，承载这些 trait 的
文件统一使用 `*Traits.scala`。除计算 IP 选择外，实际硬件参数仍由 L1-L4 的 CDE 或 NPC `++` 链固定。
`NemuHostConfig` 与 `FpgaToolchainConfig` 是普通 case class，不进入 CDE 图。内置终端与普通示例只选择预设：

```scala
class U55cNpcFpgaConfig extends CDEConfig(
  new U55cBoardConfig ++
    new FpgaConfig
) with U55cNpcTerminal with FpgaIpTerminal
```

显式自定义终端仍可通过 `configuredNemu` 或 `configuredFpga` 与分组 `copy(...)` 局部重载；重复使用
或需要进入普通示例的配方应先提升为具名 `core/` preset，必要时再新增根部终端预设。

根部终端文件只声明可直接挂载的终端 trait。终端直接需要的子项和子项集群必须位于 `core/`，每个
子项的基础依赖、数据模型和原子片段才属于 `base/`；终端不得直接组合多个 base trait。

## 计算单元 IP 终端

根部 `IpTerminalTraits.scala` 只定义两种具有终端意义的 trait：`FpgaIpTerminal` 选择 FPGA M
backend，`NemuSimulationIpTerminal` 选择周期精确的内建 M/F 功能模型。两者共用
`core/IpTerminalCoreTraits.scala` 的直接子项集群；通用时序、后端与写入 `NpcConfig` 的逻辑位于
`base/IpComputeSelectionTraits.scala`，不留在终端文件。

`NemuSimulationIpTerminal.from(attachment)` 可用 FPGA attachment 的同一份时序构造本地功能模型，
因此延迟、II 和响应 FIFO 深度只有一份来源。

这些 trait 只服务硬件组合，既不提供 scope/target，也不参与 Make 目录发现。公开运行 Config 在自身
显式混入它们以提供 `IpConstruction`，和 `HostConstruction` 提供 NEMU 配方的方式相同；
`ConstructionConfig` 与 `WithTerminalIpCoreConfig` 只能读取已挂载的选择，不能接收 IP 构造参数或在
CDE `++` 链中选择后端。公开终端还必须混入 `TerminalTraits.scala` 的十一种终端预设之一。

## 算子 IP 配置

`base/OperatorIpConfigs.scala` 不定义 CPU、SoC 或板卡的构造。它只放可由 NPC、外设或专用加速器共同
消费的算子 IP 描述；这里没有可由 Make 直接选择的 Config，也没有写入 `NpcConfig` 的
`With...Config` 片段。

| 文件 | 职责 | 可供各硬件模块复用的成品 |
| --- | --- | --- |
| `base/OperatorIpConfigs.scala` | 乘、除、浮点加减乘除、FMA、开方、转换与比较 IP 的延迟、启动间隔和响应 FIFO 深度 | `OperatorIpTimingConfig`、`OperatorIpTimingConfig.Default` |

`OperatorIpTimingConfig` 是与具体硬件无关的数据描述；每个 IP 的 `latency` 与
`initiationInterval` 分组在同一个不可变对象中。NPC 通过
`npc/base/OperatorConfigs.scala` 中的 `WithArithmeticTimingConfig` 将它写入核心；将来
的加速器应在自己的目录定义对应的 `With...Config`，而不是依赖 NPC 片段：

```scala
new WithArithmeticTimingConfig(
  OperatorIpTimingConfig.Default.copy(
    multiply = OperatorIpTimingConfig.Default.multiply.copy(latency = 4),
    divide = OperatorIpTimingConfig.Default.divide.copy(latency = 24)
  )
)
```

## FPGA IP Attachment

`FpgaIpAttachment` 是 NPC 与 ysyxSoC 共用的不可变挂接合同，并继承 base 中的
`FpgaIpComputeSelection`。它选择算术 provider、将 M 指令路由和端到端时序写入完成的 `NpcConfig`，
并提供 FPGA IP 生成所需的 profile 字段。
`XilinxIntegerIpAttachment` 还校验乘除法 II 均为 1，且总除法延迟等于 `div_gen` 内部
延迟与 adapter 延迟之和。

L4 板卡 Config 只选择 attachment；FPGA 顶层从 `FpgaIpAttachmentKey` 读取同一对象并分别
注入裸 NPC 或 ysyxSoC。`FpgaPlatformSettings` 因而只保存板卡地址和时钟，不再重复保存 IP
时序。`base/` 表示低层组合职责，并非“只放 trait”的目录，因此保持该名称；含公共构造 trait 的
文件统一使用 `*Traits.scala`。
