# 公共 Config 与 Host 行为

本目录放置不属于 NPC、SoC 或具体板卡层的共享描述，并遵守统一的 `base -> core -> terminal`
依赖方向。`base/` 只保存跨目标的底层数据模型；`core/` 保存终端可直接挂载、名称能够表达完整
运行含义的宿主行为和工具链配方。本目录本身不定义 Make 终端，因此没有 `Configs.scala`。

| 目录 | 职责 | 终端使用方式 |
| --- | --- | --- |
| `base/OperatorIpConfigs.scala` | 与具体 CPU/SoC/板卡无关的算子路由与时序数据 | 经各领域 `core/` 组合间接使用 |
| `base/FpgaToolchainConfigModels.scala` | FPGA device/flow/report/runtime 底层字段模型 | 经 `FpgaToolchainConfig` 间接使用 |
| `core/FpgaToolchainConfig.scala` | 可复制的完整 U55C/ZCU102 工具链配方 | 终端挂载 `U55cBase` 或 `Zcu102Base` |
| `core/HostConstructionConfigs.scala` | 本地仿真、FPGA 构造和终端 marker 协议 | 终端直接混入对应 trait |

| 名称 | 用途 | 是否可直接 `config=` |
| --- | --- | --- |
| `CheckOnlyConstructionConfigBase` | 仅检查硬件 | 否；由检查 Config 混入 |
| `NemuSimulationConstructionConfig` | 本地 NPC/SoC 仿真；要求终端提供 local `NemuHostConfig` | 否；由 NPC/SoC 终端混入 |
| `FpgaConstructionConfig` | FPGA 构造；要求终端提供匹配板卡的 `NemuHostConfig` 与 `FpgaToolchainConfig` | 否；由 FPGA 终端混入 |
| `NpcTerminalConfig`、`SocTerminalConfig` | 声明 Make 可选终端的作用域和目标 | 否；由终端混入 |
| `FpgaNpcTerminalConfig`、`FpgaSocTerminalConfig` | 声明 FPGA 终端目标 | 否；由终端混入 |

终端 marker 只用于自动目录和反射校验；实际硬件参数仍由 L1-L4 的 CDE 或 NPC `++` 链固定。
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
