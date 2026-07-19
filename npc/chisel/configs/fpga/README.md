# L3：通用 FPGA 构造

本目录是架构第 3 层：把 L1 NPC 或 L2 ysyxSoC 接入 FPGA 的 CDE 参数图。它仅在生成 FPGA 时启用；
独立 NPC、DPI 和非 FPGA SoC 均跳过本层。L3 不决定物理引脚或具体时钟约束，这些属于 L4 板卡层。

| 目录 | 职责 | 可供下一层使用的成品 |
| --- | --- | --- |
| `common/` | FPGA CDE 键、入口设置和默认 NPC 适配策略 | `NpcFpgaCdeConfig`、`NpcExternalAxiCdeConfig`、`WithNpcCoreConfig` |
| `u55c/` | U55C 的 L4 板卡策略和最终构造 | `U55cBoardConfig`、`U55cNpcFpgaConfig`、`U55cYsyxSocFpgaConfig` |
| `zcu102/` | ZCU102 的 L4 板卡策略和最终构造 | `Zcu102BoardConfig`、`Zcu102NpcFpgaConfig`、`Zcu102YsyxSocFpgaConfig` |

通用 SoC 的 FPGA 平台层 `YsyxSocFpgaConfig` 位于 `../ysyx/SocConfig.scala`，因为它依赖
Rocket/ysyxSoC；其架构职责仍属于 L3，板卡最终构造会在左侧叠加 L4 的 NPC 和板卡策略。

## 可增加的特性

| 特性 | 可直接复制到 `++` 链的名称 | 添加位置 | 是否可选 |
| --- | --- | --- | --- |
| 默认裸核 FPGA 核心 | `new NpcFpgaCdeConfig` | `common/FpgaConfigs.scala` | 裸核 FPGA 必需 |
| 默认 SoC 外部 AXI 核心 | `new NpcExternalAxiCdeConfig` | `common/FpgaConfigs.scala` | 通用 SoC 必需，且可覆盖 |
| 已完成 NPC 覆盖 | `new WithNpcCoreConfig(customNpc)` | `common/FpgaConfigs.scala` | 是 |
| 平台地址/IP 时序 | `new WithFpgaPlatformConfig(platform)` | `common/FpgaConfigs.scala` | FPGA 生成必需 |
| Vivado/Vitis 策略 | `new WithFpgaToolConfig(settings)` | `common/FpgaConfigs.scala` | FPGA 生成必需 |
| 通用 SoC FPGA 平台 | `new YsyxSocFpgaConfig` | `ysyx/SocConfig.scala` | 仅 SoC FPGA 需要 |
| 新物理板卡 | `class MyBoardConfig`（命名模板，需先实现） | 新建 `fpga/<board>/` L4 目录 | 是 |
| part、引脚、DDR、XDC、vendor IP | 无；不在 L3 添加 | `npc/fpga/boards/<board>/` | 不适用 |

L3 本身不提供可直接布线的物理顶层；选择 FPGA 后必须继续选择一个 L4 板卡终端 Config。

板级 Make 构造应直接选择 L4 终端，而不是把板卡和 SoC 分别猜测出来：

```make
make -C npc build config=U55cNpcFpgaConfig
make -C npc build config=U55cYsyxSocFpgaConfig
```

终端 Config 的 `FpgaBoardKey` 是 shell 的最终来源，`FpgaClockMHzKey` 固定主频。Make 在启动 Scala
前从自动目录取得同一板卡，用于选择 Tcl、IP 和 Vitis 平台；不再提供独立板卡或 SoC 选择变量。
