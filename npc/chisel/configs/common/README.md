# 公共数据与终端 trait

本目录放置不属于 NPC、SoC 或具体板卡层的共享描述，并遵守 `base -> core -> 根部终端文件` 的
分层。`base/` 保存跨目标的底层数据模型和构造接口，不能由终端直接挂载；`core/` 保存可复用的检查
行为和工具链配方；Make 终端预设位于根部 `TerminalTraits.scala`。本目录本身不定义 Make 终端 Config，因此没有
`Configs.scala`。

| 目录 | 职责 | 终端使用方式 |
| --- | --- | --- |
| `base/OperatorIpConfigs.scala` | 与具体 CPU/SoC/板卡无关的算子路由与时序数据 | 经各领域 `core/` 组合间接使用 |
| `base/IpComputeSelectionTraits.scala` | 计算单元后端的非终端组合合同 | 由 FPGA attachment 与本地/FPGA 构造复用 |
| `base/FpgaIpAttachmentTraits.scala` | 可挂接到 NPC/SoC 的 FPGA IP 合同与 Xilinx 整数实现 | 由 L4 板卡 Config 选择，经同一 CDE 键供 NPC/SoC 消费 |
| `base/FpgaToolchainConfigModels.scala` | FPGA device/flow/report/runtime 底层字段模型 | 经 `FpgaToolchainConfig` 间接使用 |
| `base/NemuBackend.scala` | NEMU host 后端枚举 | 经 `NemuHostConfig` 间接使用 |
| `base/ConstructionTraits.scala` | NEMU host、accelerator host、计算 IP、FPGA 与 Make 终端的底层接口和校验 | 不直接使用；只由 terminal 层 trait 组合 |
| `core/NemuHostConfig.scala` | 可复制的完整 NEMU host 配方 | 根部终端预设内部绑定 `LocalPipelineTrace`、`U55cBase` 等 |
| `core/FpgaToolchainConfig.scala` | 可复制的完整 U55C/ZCU102 工具链配方 | 根部终端预设内部绑定 `U55cBase` 或 `Zcu102Base` |
| `core/CheckTraits.scala` | 非 Make 的检查构造行为 | 检查 Config 直接挂载 `CheckOnlyConstruction` |
| `base/NemuConfigCatalog.scala` | `host-config-list` 入口 | 不是终端 Config |
| `TerminalTraits.scala` | 通用 NPC/SoC 终端预设：scope、target、默认 NEMU/FPGA 配方与乘除法后端 | 根部终端协议；每个 CPU 终端只挂载其中一个 trait |

| 名称 | 用途 | 终端可否直接挂载 |
| --- | --- | --- |
| `HostConstruction`、`AcceleratorHostConstruction`、`NemuSimulationConstruction`、`FpgaConstruction`、`MakeTerminal` | 底层运行接口与约束；具体 accelerator host 配方由加速器领域定义 | 否；只由 terminal 层 trait 组合 |
| `CheckOnlyConstruction` | 仅检查硬件 | 不适用；由检查 Config 直接挂载 |
| `LocalNpcTerminal`、`LocalSocTerminal` | 完整的本地 NPC/SoC 终端预设；默认 `LocalPipelineTrace` | 是；对应终端只挂载其中一个 |
| `U55cNpcTerminal`、`U55cSocTerminal` | 完整的 U55C NPC/SoC 终端预设；默认 U55C NEMU 与工具链配方 | 是；对应终端只挂载其中一个 |
| `U55cNpcPerformanceMonitorTerminal` | U55C 裸 NPC 的 batch-only 性能监测终端预设 | 是；只供 v13 监测 Config 挂载 |
| `Zcu102NpcTerminal`、`Zcu102SocTerminal` | 完整的 ZCU102 NPC/SoC 终端预设；默认 ZCU102 NEMU 与工具链配方 | 是；对应终端只挂载其中一个 |

通用 terminal 层预设提供 NPC/SoC 所需的 FPGA/NEMU 配方、自动目录身份、scope 和 target；
`LocalSpmvInputTerminal` 与 U55C SPMV 两种终端位于 `../accelerators/spmv/TerminalTraits.scala`。
一个终端只挂载一个 terminal trait，且
不得越过 terminal 层直接混入 base 构造 trait。公共构造 trait 名称不加 `Trait` 后缀，承载这些 trait 的
文件统一使用 `*Traits.scala`。实际硬件参数仍由 L1-L4 的 CDE 或 NPC `++` 链固定。
`NemuHostConfig` 与 `FpgaToolchainConfig` 是普通 case class，不进入 CDE 图。内置终端与普通示例只选择预设：

```scala
class U55cNpcFpgaConfig extends CDEConfig(
  new _root_.fpga.u55c.U55cBoardConfig ++
    new FpgaConfig
) with U55cNpcTerminal
```

显式自定义终端仍可通过 `configuredNemu` 或 `configuredFpga` 与分组 `copy(...)` 局部重载；重复使用
或需要进入普通示例的配方应先提升为具名 `core/` preset，必要时再新增根部终端预设。

根部终端文件只声明可直接挂载的终端 trait。该 trait 自己绑定 scope、target 和默认 host/FPGA
配方；硬件成品仍在各领域 `core/`，原子片段在 `base/`。终端 Config 不得直接组合多个 base 构造 trait。

## 计算单元后端

本地仿真构造自带 `BuiltinCompute`，FPGA 运行构造自带 `FpgaCompute`。乘除法时序由 Config 片段或
板卡 attachment 写入。本地模型若要复现 FPGA attachment 的时序，在 `++` 链中写
`new WithArithmeticTimingConfig(attachment.timing)`。

`ConstructionConfig` 与 `WithTerminalIpCoreConfig` 只读取已挂载的后端，不能接收构造参数或在
CDE `++` 链中选择后端。公开 CPU 终端只挂载 `TerminalTraits.scala` 中的一个终端预设。

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
