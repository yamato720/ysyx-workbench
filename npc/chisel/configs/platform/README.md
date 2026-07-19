# ysyxSoC 平台键

本目录不是独立架构层，而是 L2 ysyxSoC 与 L3 FPGA 共享的 Rocket/CDE 参数接口。它只由 Mill
`ysyxsoc` 编译，不进入裸 NPC 的 SBT root。

`YsyxParameters.scala` 定义平台模式、ChipLink、AXI SDRAM 等 CDE 键，并通过
`YsyxPlatformParameters` 提供统一查询接口。

## 可增加的特性

| 特性 | 可直接引用的名称 | 添加位置 | 是否可选 |
| --- | --- | --- | --- |
| ysyxSoC 平台模式 | `YsyxPlatform.Standalone`、`YsyxPlatform.Simulation`、`YsyxPlatform.Fpga` | `YsyxParameters.scala` | L2 构造必需，三选一 |
| 平台模式键 | `YsyxPlatformKey`、`YsyxPlatformParameters.platform` | `YsyxParameters.scala` | L2 构造必需 |
| ChipLink | `YsyxChipLinkKey`、`YsyxPlatformParameters.hasChipLink` | `YsyxParameters.scala` | 是 |
| AXI SDRAM | `YsyxAxiSdramKey`、`YsyxPlatformParameters.useAxiSdram` | `YsyxParameters.scala` | 是 |
| 新 ysyxSoC CDE 键 | `case object MyFeatureKey extends Field[...]`（模板，需先实现） | `YsyxParameters.scala` | 是 |
| FPGA 板卡、时钟和平台地址 | 无；不在本目录添加 | L3 `fpga/common/` 或 L4 | 不适用 |
