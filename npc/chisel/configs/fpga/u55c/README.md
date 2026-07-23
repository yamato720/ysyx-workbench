# L4：U55C 板卡构造

本目录是架构第 4 层：在 L3 FPGA 参数图上绑定 U55C 的物理板卡策略。它仅在目标为 U55C 时启用，
并且必须建立在 L3 FPGA 构造之上；非 FPGA 目标和其他板卡均不使用它。

| 文件 | 职责 | 是否可被更高层复用或覆盖 |
| --- | --- | --- |
| `core/U55cBoardConfig.scala` | 板卡标识、可选频率与按核心 XLEN 自动选择的 Xilinx IP 路由 | 是；`U55cBoardConfig(clockMHz = 125)` 是可叠加的 L4 板卡层 |
| `Configs.scala` | U55C 裸 NPC、RV64IMF 裸 NPC、250 MHz 时序实验与 ysyxSoC 的所有终端构造 | 是；根部只放终端 |

`U55cNpcFpgaConfig` 直接组合 `U55cBoardConfig ++ FpgaConfig`。
`U55cYsyxSocFpgaConfig` 以 `U55cBoardConfig ++ FpgaConfig ++ YsyxElaborateConfig` 覆盖通用 SoC
的默认 NPC；板卡键自动选择 SoC 的 FPGA 分支。所有完整终端均在 `Configs.scala`，以统一的
`fpga` 作用域发现，再由 `TARGET=NPC|SOC` 选择对应生成入口。

## 可增加的特性

| 特性 | 可直接复制到 `++` 链的名称 | 添加位置 | 是否可选 |
| --- | --- | --- | --- |
| U55C 板卡层 | `new U55cBoardConfig` | `core/U55cBoardConfig.scala` | U55C 目标必需 |
| U55C 裸 NPC 终端 | `new U55cNpcFpgaConfig` | `Configs.scala` | 是 |
| U55C RV64IMF 裸 NPC 终端 | `new U55cFullIsa64NpcFpgaConfig` | `Configs.scala` | 是 |
| U55C RV64IMF 250 MHz 时序实验终端 | `new U55cFullIsa64Npc250MHzFpgaConfig` | `Configs.scala` | 是 |
| U55C SoC 终端 | `new U55cYsyxSocFpgaConfig` | `Configs.scala` | 是 |
| U55C 板卡标识 | `new WithFpgaBoardConfig(FpgaBoard.U55c)` | L3 `common/base/FpgaConfigFragments.scala` | U55C 目标必需 |
| U55C 时钟 | `new U55cBoardConfig(clockMHz = 125)` | `core/U55cBoardConfig.scala` | U55C 目标必需；允许频率由 `boards/u55c/config.mk` 的物理能力表限制 |
| U55C 地址与 IP 时序 | `new WithFpgaPlatformConfig(FpgaPlatformSettings(...))` | `core/U55cBoardConfig.scala` | U55C 目标必需 |
| U55C 器件与实现策略 | `FpgaToolchainConfig.U55cBase` | 根部 `U55cNpcTerminal`/`U55cSocTerminal` 预设 | U55C 目标必需；不进入 CDE |
| Vitis XRT 环境策略 | `U55cBase.flow.vitisXrtMode = "unset"` | `FpgaToolchainConfig.scala` | U55C 构造必需；只为 `v++` 选择 Vitis 自带的封装工具 |
| U55C 构造并行度、策略搜索和实现后报告 | `U55cBase.flow`、`U55cBase.reports` | `FpgaToolchainConfig.scala` | 是；worker jobs、策略搜索、时序路径深度和诊断报告开关均由终端冻结 |
| 浮点宿主回退 | `U55cBase.runtime.floatingFallback = "host-mailbox"` | `FpgaToolchainConfig.scala` | 当前 U55C FPGA F 扩展必需 |
| 默认 NPC 覆盖 | `new FullIsa64PipelineDualForwardingFpgaConfig` 等完整 L1 Config | L1 `core/IntegrationCore.scala` | 是 |
| 新 U55C CDE 特性 | `class WithMyU55cFeatureConfig`（命名模板，需先实现） | 新的 U55C Config | 是 |
| wrapper、约束和 vendor IP 文件 | 无；不在 Scala Config 添加 | `npc/fpga/boards/u55c/` | U55C bitstream 必需 |

`U55cFullIsa64NpcFpgaConfig` 是面向完整 `RV64IMF_Zicsr` 的可运行终端。PL 中的比较、搬移和分类
直接完成；需要严格 IEEE-754 舍入、异常旗标或转换的操作写入 ABI v3 mailbox。当前 XRT 没有可由
宿主消费的 IRQ 文件描述符，因此 `FPGA_NOTIFICATION_MODE=xrt-poll`，NEMU 以显式轮询发现请求，再用
Berkeley SoftFloat 以相同 sequence 回写响应。PL 的 mailbox interrupt 不会送入 RISC-V 核的 `MEIP`。

`U55cBoardConfig` 不接收 XLEN：它通过右侧完成的 `NpcCoreConfigKey` 自动生成匹配 RV32/RV64 的整数
IP 路由。`U55cFullIsa64Npc250MHzFpgaConfig` 仅以 `new U55cBoardConfig(250)` 覆盖物理时钟，核心 ABI
与 125 MHz RV64 终端相同，且默认保持单实现策略以便先观察真实时序缺口。
