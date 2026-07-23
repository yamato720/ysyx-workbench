# L3：通用 FPGA 构造

本目录是架构第 3 层：为已组合的 L1 NPC 或 L2 ysyxSoC 提供 FPGA 特有的 CDE 设置。它仅在生成
FPGA 时启用；独立 NPC、DPI 和非 FPGA SoC 均跳过本层。L3 不决定物理引脚或具体时钟约束，这些
属于 L4 板卡层。

| 目录 | 职责 | 可供下一层使用的成品 |
| --- | --- | --- |
| `common/base/` | FPGA 平台、板卡 CDE 键与原子片段 | `WithFpgaPlatformConfig`、`WithFpgaBoardConfig` |
| `u55c/` | U55C 的 L4 板卡 core 和统一终端文件 | `U55cBoardConfig`、`Configs.scala` 中的完整终端 |
| `zcu102/` | ZCU102 的 L4 板卡 core 和统一终端文件 | `Zcu102BoardConfig`、`Configs.scala` 中的完整终端 |

每块板卡只维护一个根部 `Configs.scala`，且其中只放挂载一个 terminal 层 trait 的无参类。所有 FPGA 终端使用同一个 `fpga` 作用域；`TARGET=NPC|SOC`
在构造时选择裸核或 ysyxSoC elaborator。SoC FPGA 终端直接组合 L4 板卡、完整 L1 NPC 和
`YsyxElaborateConfig`；板卡键同时选择 FPGA 硬件分支。

## 可增加的特性

| 特性 | 可直接复制到 `++` 链的名称 | 添加位置 | 是否可选 |
| --- | --- | --- | --- |
| 默认裸核 FPGA 核心 | `new FpgaConfig` | `npc/core/IntegrationCore.scala` | 裸核 FPGA 必需 |
| 默认 SoC 外部 AXI 核心 | `new ExternalAxiConfig` | `npc/core/IntegrationCore.scala` | 通用 SoC 必需，且可覆盖 |
| 已完成 NPC 覆盖 | `new FullIsa64PipelineDualForwardingFpgaConfig` 等完整 L1 Config | `npc/core/IntegrationCore.scala` | 是 |
| 平台地址/IP 时序 | `new WithFpgaPlatformConfig(platform)` | `common/base/FpgaConfigFragments.scala` | FPGA 生成必需 |
| Vivado/Vitis 策略 | `FpgaToolchainConfig.U55cBase` 或 `Zcu102Base` | 根部 U55C/ZCU102 终端预设 trait | FPGA 生成必需；不进入 CDE |
| SoC FPGA 分支 | `new U55cBoardConfig` 或 `new Zcu102BoardConfig` | L4 `...BoardConfig.scala` | SoC FPGA 必需；板卡键自动选择 |
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
