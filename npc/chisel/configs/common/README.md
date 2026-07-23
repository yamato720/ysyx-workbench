# 公共数据与终端 trait

本目录放置不属于 NPC、SoC 或具体板卡层的共享描述，并遵守 `base -> core -> 根部终端文件` 的
分层。`base/` 保存跨目标的底层数据模型和构造接口，不能由终端直接挂载；`core/` 保存可复用的检查
行为和工具链配方；可由终端直接挂载的 trait 独立为根部 `TerminalTraits.scala`。本目录本身不定义
Make 终端 Config，因此没有 `Configs.scala`。

| 目录 | 职责 | 终端使用方式 |
| --- | --- | --- |
| `base/OperatorIpConfigs.scala` | 与具体 CPU/SoC/板卡无关的算子路由与时序数据 | 经各领域 `core/` 组合间接使用 |
| `base/FpgaToolchainConfigModels.scala` | FPGA device/flow/report/runtime 底层字段模型 | 经 `FpgaToolchainConfig` 间接使用 |
| `base/ConstructionTraits.scala` | Host、本地 NEMU、FPGA 与 Make 终端的底层接口和校验 | 不直接使用；只由 terminal 层 trait 组合 |
| `core/FpgaToolchainConfig.scala` | 可复制的完整 U55C/ZCU102 工具链配方 | 终端挂载 `U55cBase` 或 `Zcu102Base` |
| `core/CheckTraits.scala` | 非 Make 的检查构造行为 | 检查 Config 直接挂载 `CheckOnlyConstruction` |
| `TerminalTraits.scala` | 四种完整的 Make 终端行为、作用域和目标 | 根部终端协议；每个终端只挂载其中一个 trait |

| 名称 | 用途 | 终端可否直接挂载 |
| --- | --- | --- |
| `HostConstruction`、`NemuSimulationConstruction`、`FpgaConstruction`、`MakeTerminal` | 底层运行接口与约束 | 否；base trait 只允许 core 组合 |
| `CheckOnlyConstruction` | 仅检查硬件 | 不适用；由检查 Config 直接挂载 |
| `NpcTerminal`、`SocTerminal` | 完整的本地 NPC/SoC 终端行为；要求 local `NemuHostConfig` | 是；对应终端只挂载其中一个 |
| `FpgaNpcTerminal`、`FpgaSocTerminal` | 完整的 FPGA NPC/SoC 终端行为；要求匹配板卡的 NEMU 与工具链配方 | 是；对应终端只挂载其中一个 |

四种 terminal 层 trait 同时提供运行行为、自动目录身份、scope 和 target；一个终端只挂载一个，且
不得越过 terminal 层直接混入 base 构造 trait。公共构造 trait 名称不加 `Trait` 后缀，承载这些 trait 的
文件统一使用 `*Traits.scala`。实际硬件参数仍由 L1-L4 的 CDE 或 NPC `++` 链固定。
`NemuHostConfig` 与 `FpgaToolchainConfig` 是普通 case class，不进入 CDE 图。自定义终端可用分组
`copy(...)` 覆盖工具链局部字段，例如：

```scala
override protected val configuredFpga = FpgaToolchainConfig.U55cBase.copy(
  flow = FpgaToolchainConfig.U55cBase.flow.copy(implementationParallelJobs = 12)
)
```

## 算子 IP 配置

`base/OperatorIpConfigs.scala` 不定义 CPU、SoC 或板卡的构造。它只放可由 NPC、外设或专用加速器共同
消费的算子 IP 描述；这里没有可由 Make 直接选择的 Config，也没有写入 `NpcConfig` 的
`With...Config` 片段。

| 文件 | 职责 | 可供各硬件模块复用的成品 |
| --- | --- | --- |
| `base/OperatorIpConfigs.scala` | 乘、除、浮点加减乘除、FMA、开方、转换与比较 IP 的延迟、启动间隔和响应 FIFO 深度 | `OperatorIpTimingConfig`、`OperatorIpTimingConfig.Default` |

`OperatorIpTimingConfig` 是与具体硬件无关的数据描述。NPC 通过
`npc/base/OperatorConfigs.scala` 中的 `WithArithmeticTimingConfig` 将它写入核心；将来
的加速器应在自己的目录定义对应的 `With...Config`，而不是依赖 NPC 片段：

```scala
new WithArithmeticTimingConfig(
  OperatorIpTimingConfig(multiplyCycles = 4, divCycles = 24)
)
```
