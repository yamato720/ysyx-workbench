# L4：ZCU102 板卡构造

本目录是架构第 4 层：在 L3 FPGA 参数图上绑定 ZCU102 的物理板卡策略。它仅在目标为 ZCU102 时启用，
并且必须建立在 L3 FPGA 构造之上；非 FPGA 目标和其他板卡均不使用它。

| 文件 | 职责 | 是否可被更高层复用或覆盖 |
| --- | --- | --- |
| `core/Zcu102BoardConfig.scala` | 板卡标识与 `300 MHz` 时钟策略 | 是；`Zcu102BoardConfig` 是可叠加的 L4 板卡层 |
| `Zcu102Configs.scala` | ZCU102 裸 NPC 与 ysyxSoC 的所有终端构造 | 是；可作为更高层自定义 CDE 链的右侧基类 |

`Zcu102NpcFpgaConfig` 直接组合 `Zcu102BoardConfig ++ FpgaConfig`。
`Zcu102YsyxSocFpgaConfig` 以 `Zcu102BoardConfig ++ FpgaConfig ++ YsyxElaborateConfig` 覆盖通用 SoC
的默认 NPC；板卡键自动选择 SoC 的 FPGA 分支。所有完整成品均在 `Zcu102Configs.scala`，以统一的
`fpga` 作用域发现，再由 `TARGET=NPC|SOC` 选择对应生成入口。

严格 RVF 的数值算子经 ABI v3 mailbox 回退到主机 SoftFloat；符号、比较、搬移和分类仍直接在 PL
完成。`FPGA_NOTIFICATION_MODE=ps-uio-irq` 表示 PL mailbox 中断通知 ZCU102 PS 的 UIO 主机，主机在
`poll/read` 后重使能 UIO。该通知不接入 NPC 的 RISC-V 外部中断或陷阱路径。

## 可增加的特性

| 特性 | 可直接复制到 `++` 链的名称 | 添加位置 | 是否可选 |
| --- | --- | --- | --- |
| ZCU102 板卡层 | `new Zcu102BoardConfig` | `core/Zcu102BoardConfig.scala` | ZCU102 目标必需 |
| ZCU102 裸 NPC 终端 | `new Zcu102NpcFpgaConfig` | `Zcu102Configs.scala` | 是 |
| ZCU102 SoC 终端 | `new Zcu102YsyxSocFpgaConfig` | `Zcu102Configs.scala` | 是 |
| ZCU102 板卡标识 | `new WithFpgaBoardConfig(FpgaBoard.Zcu102)` | L3 `core/dependencies/FpgaConfigFragments.scala` | ZCU102 目标必需 |
| ZCU102 时钟 | `new WithFpgaClockMHzConfig(300)` | L3 `core/dependencies/FpgaConfigFragments.scala` | ZCU102 目标必需 |
| ZCU102 地址与 IP 时序 | `new WithFpgaPlatformConfig(FpgaPlatformSettings(...))` | `core/Zcu102BoardConfig.scala` | ZCU102 目标必需 |
| ZCU102 器件与实现策略 | `FpgaToolchainConfig.Zcu102Base` | `Zcu102Configs.scala` 的终端 `configuredFpga` | ZCU102 目标必需；不进入 CDE |
| ZCU102 构造并行度、策略搜索和实现后报告 | `Zcu102Base.flow`、`Zcu102Base.reports` | `FpgaToolchainConfig.scala` | 是；worker jobs、策略搜索、时序路径深度和诊断报告开关均由终端冻结 |
| 默认 NPC 覆盖 | `new FullIsa64PipelineDualForwardingFpgaConfig` 等完整 L1 Config | L1 `core/IntegrationCore.scala` | 是 |
| 新 ZCU102 CDE 特性 | `class WithMyZcu102FeatureConfig`（命名模板，需先实现） | 新的 ZCU102 Config | 是 |
| wrapper、约束、Vivado block design 和 vendor IP 文件 | 无；不在 Scala Config 添加 | `npc/fpga/boards/zcu102/` | ZCU102 bitstream 必需 |
