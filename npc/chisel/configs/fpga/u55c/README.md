# L4：U55C 板卡构造

本目录是架构第 4 层：在 L3 FPGA 参数图上绑定 U55C 的物理板卡策略。它仅在目标为 U55C 时启用，
并且必须建立在 L3 FPGA 构造之上；非 FPGA 目标和其他板卡均不使用它。

| 文件 | 职责 | 是否可被更高层复用或覆盖 |
| --- | --- | --- |
| `U55cBoardConfig.scala` | 板卡标识与 `300 MHz` 时钟策略 | 是；`U55cBoardConfig` 是可叠加的 L4 板卡层 |
| `U55cNpcConfig.scala` | U55C 裸 NPC 终端构造，以及 RV64IMF mailbox 回退终端 | 是；可作为更高层自定义 CDE 链的右侧基类 |
| `U55cSocConfig.scala` | U55C ysyxSoC FPGA 终端构造 | 是；可作为更高层自定义 CDE 链的右侧基类 |

`U55cNpcFpgaConfig` 组合 `U55cBoardConfig ++ NpcFpgaCdeConfig`。`U55cYsyxSocFpgaConfig` 再以
`U55cNpcFpgaConfig ++ YsyxSocFpgaConfig` 覆盖通用 SoC 的默认 NPC。这两个 `...Config.scala`
文件是本目录中可直接被生成入口选用的完整成品。

## 可增加的特性

| 特性 | 可直接复制到 `++` 链的名称 | 添加位置 | 是否可选 |
| --- | --- | --- | --- |
| U55C 板卡层 | `new U55cBoardConfig` | `U55cBoardConfig.scala` | U55C 目标必需 |
| U55C 裸 NPC 终端 | `new U55cNpcFpgaConfig` | `U55cNpcConfig.scala` | 是 |
| U55C RV64IMF 裸 NPC 终端 | `new U55cFullIsa64NpcFpgaConfig` | `U55cNpcConfig.scala` | 是 |
| U55C SoC 终端 | `new U55cYsyxSocFpgaConfig` | `U55cSocConfig.scala` | 是 |
| U55C 板卡标识 | `new WithFpgaBoardConfig(FpgaBoard.U55c)` | L3 `FpgaConfigs.scala` | U55C 目标必需 |
| U55C 时钟 | `new WithFpgaClockMHzConfig(300)` | L3 `FpgaConfigs.scala` | U55C 目标必需 |
| U55C 地址与 IP 时序 | `new WithFpgaPlatformConfig(FpgaPlatformSettings(...))` | `U55cBoardConfig.scala` | U55C 目标必需 |
| U55C 器件与实现策略 | `new WithFpgaToolConfig(FpgaToolSettings(...))` | `U55cBoardConfig.scala` | U55C 目标必需 |
| U55C 构造并行度和策略搜索 | `new WithFpgaBuildSettingsConfig(FpgaBuildSettings(synthesisParallelJobs = 4, implementationParallelJobs = 8, implementationStrategySearch = false))` | `U55cBoardConfig.scala` | 是；均为宿主工具 worker jobs，策略搜索默认关闭 |
| 浮点宿主回退 | `floatingFallback = "host-mailbox"` | `U55cBoardConfig.scala` | 当前 U55C FPGA F 扩展必需 |
| 默认 NPC 覆盖 | `new WithNpcCoreConfig(customNpc)` | L3 `FpgaConfigs.scala` | 是 |
| 新 U55C CDE 特性 | `class WithMyU55cFeatureConfig`（命名模板，需先实现） | 新的 U55C Config | 是 |
| wrapper、约束和 vendor IP 文件 | 无；不在 Scala Config 添加 | `npc/fpga/boards/u55c/` | U55C bitstream 必需 |

`U55cFullIsa64NpcFpgaConfig` 是面向完整 `RV64IMF_Zicsr` 的可运行终端。PL 中的比较、搬移和分类
直接完成；需要严格 IEEE-754 舍入、异常旗标或转换的操作写入 mailbox，并拉高板卡的 host interrupt。
NEMU 收到该通知后以 Berkeley SoftFloat 完成请求，再以相同 sequence 回写响应。该中断是 PL 到主机的
服务通知，不是送入 RISC-V 核的 `MEIP`。
