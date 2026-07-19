# L4：ZCU102 板卡构造

本目录是架构第 4 层：在 L3 FPGA 参数图上绑定 ZCU102 的物理板卡策略。它仅在目标为 ZCU102 时启用，
并且必须建立在 L3 FPGA 构造之上；非 FPGA 目标和其他板卡均不使用它。

| 文件 | 职责 | 是否可被更高层复用或覆盖 |
| --- | --- | --- |
| `Zcu102BoardConfig.scala` | 板卡标识与 `300 MHz` 时钟策略 | 是；`Zcu102BoardConfig` 是可叠加的 L4 板卡层 |
| `Zcu102NpcConfig.scala` | ZCU102 裸 NPC 终端构造 | 是；可作为更高层自定义 CDE 链的右侧基类 |
| `Zcu102SocConfig.scala` | ZCU102 ysyxSoC FPGA 终端构造 | 是；可作为更高层自定义 CDE 链的右侧基类 |

`Zcu102NpcFpgaConfig` 组合 `Zcu102BoardConfig ++ NpcFpgaCdeConfig`。
`Zcu102YsyxSocFpgaConfig` 再以 `Zcu102NpcFpgaConfig ++ YsyxSocFpgaConfig` 覆盖通用 SoC 的默认
NPC。这两个 `...Config.scala` 文件是本目录中可直接被生成入口选用的完整成品。

## 可增加的特性

| 特性 | 可直接复制到 `++` 链的名称 | 添加位置 | 是否可选 |
| --- | --- | --- | --- |
| ZCU102 板卡层 | `new Zcu102BoardConfig` | `Zcu102BoardConfig.scala` | ZCU102 目标必需 |
| ZCU102 裸 NPC 终端 | `new Zcu102NpcFpgaConfig` | `Zcu102NpcConfig.scala` | 是 |
| ZCU102 SoC 终端 | `new Zcu102YsyxSocFpgaConfig` | `Zcu102SocConfig.scala` | 是 |
| ZCU102 板卡标识 | `new WithFpgaBoardConfig(FpgaBoard.Zcu102)` | L3 `FpgaConfigs.scala` | ZCU102 目标必需 |
| ZCU102 时钟 | `new WithFpgaClockMHzConfig(300)` | L3 `FpgaConfigs.scala` | ZCU102 目标必需 |
| ZCU102 地址与 IP 时序 | `new WithFpgaPlatformConfig(FpgaPlatformSettings(...))` | `Zcu102BoardConfig.scala` | ZCU102 目标必需 |
| ZCU102 器件与实现策略 | `new WithFpgaToolConfig(FpgaToolSettings(...))` | `Zcu102BoardConfig.scala` | ZCU102 目标必需 |
| 默认 NPC 覆盖 | `new WithNpcCoreConfig(customNpc)` | L3 `FpgaConfigs.scala` | 是 |
| 新 ZCU102 CDE 特性 | `class WithMyZcu102FeatureConfig`（命名模板，需先实现） | 新的 ZCU102 Config | 是 |
| wrapper、约束、Vivado block design 和 vendor IP 文件 | 无；不在 Scala Config 添加 | `npc/fpga/boards/zcu102/` | ZCU102 bitstream 必需 |
