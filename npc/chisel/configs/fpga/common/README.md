# L3 通用 CDE 适配

`FpgaConfigs.scala` 是 FPGA 的板卡无关 CDE 适配层。它将 L1 已完成的 `NpcConfig` 作为
`NpcCoreConfigKey` 放进 `Parameters`，并提供板卡层需要的时钟、板卡和平台设置键。

## 可复用成品与覆盖点

| 类 | 作用 | 上层如何使用或覆盖 |
| --- | --- | --- |
| `NpcFpgaCdeConfig` | 用 L1 `NpcFpgaConfig` 生成裸核 FPGA 默认核心 | 被 L4 `...NpcFpgaConfig` 复用；最左侧 `WithNpcCoreConfig` 可覆盖 |
| `NpcExternalAxiCdeConfig` | 用 L1 `NpcExternalAxiConfig` 生成通用 SoC 默认核心 | 被 L2 `YsyxSocConfig` 复用；L4 可提供新的核心键覆盖 |
| `WithNpcCoreConfig` | 将已 `build` 的 `NpcConfig` 写入 CDE 图 | 必须置于希望覆盖对象的左侧 |
| `WithFpgaPlatformConfig` | 固定平台地址与 IP 适配时序 | 由 L4 `...BoardConfig` 提供 |
| `WithFpgaToolConfig` | 固定器件、平台、工具版本、并行度和实现策略 | 由 L4 `...BoardConfig` 提供 |
| `WithFpgaBoardConfig`、`WithFpgaClockMHzConfig` | 选择板卡并固定频率 | 由 L4 `...BoardConfig` 提供 |

因此，`NpcFpgaCdeConfig` 与 `NpcExternalAxiCdeConfig` 是本文件专门提供给更高层复用的完成策略；
`With...` 类是局部输入或覆盖片段，而不是独立构造目标。

## 可增加的特性

| 特性 | 可直接复制到 `++` 链的名称 | 添加位置 | 是否可选 |
| --- | --- | --- | --- |
| 默认裸 FPGA 核心 | `new NpcFpgaCdeConfig` | `FpgaConfigs.scala` | 裸核 FPGA 必需 |
| 默认 SoC 外部 AXI 核心 | `new NpcExternalAxiCdeConfig` | `FpgaConfigs.scala` | 通用 SoC 必需，且可覆盖 |
| 已完成 NPC 的替换 | `new WithNpcCoreConfig(customNpc)` | `FpgaConfigs.scala` | 是 |
| 平台地址/IP 时序 | `new WithFpgaPlatformConfig(platform)` | `FpgaConfigs.scala` | FPGA 生成必需 |
| Vivado/Vitis 实现策略 | `new WithFpgaToolConfig(settings)` | `FpgaConfigs.scala` | FPGA 生成必需 |
| 严格 F 回退策略 | `new WithFpgaToolConfig(FpgaToolSettings(..., floatingFallback = "host-mailbox"))` | L4 `...BoardConfig.scala` | 当前 FPGA F 扩展必需 |
| 板卡选择 | `new WithFpgaBoardConfig(FpgaBoard.U55c)` | `FpgaConfigs.scala` | L4 物理板卡生成必需 |
| 频率覆盖 | `new WithFpgaClockMHzConfig(300)` | `FpgaConfigs.scala` | L4 物理板卡生成必需 |
| 新 CDE 键或核心策略 | `class WithMyFpgaFeatureConfig`（命名模板，需先实现） | `FpgaConfigs.scala` | 是 |
| 器件型号、pin、XDC、vendor IP | 无；不在本文件添加 | `npc/fpga/boards/<board>/` | 不适用 |
