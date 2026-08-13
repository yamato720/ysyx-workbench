# L3 通用 CDE 适配

`base/FpgaConfigParameters.scala` 与 `base/FpgaConfigFragments.scala` 是 FPGA 的板卡无关
CDE 底层。L1 完整 NPC Config 已经自行将 `NpcCoreConfigKey` 放入 `Parameters`；本目录只提供
硬件需要的时钟、板卡和平台键。M 算子 provider、路由和时序由
`../../common/base/FpgaIpAttachmentTraits.scala` 的 `FpgaIpAttachmentKey` 统一提供。器件与工具链是终端的 `FpgaToolchainConfig`，不进入 CDE。
板卡目录的 `core/*BoardConfig.scala` 负责把这些原子片段组合为终端可直接调用的物理策略。
`../CdeConfigResolver.scala` 是终端解析入口，不属于 Config 组合层。

## 可复用成品与覆盖点

| 类 | 作用 | 上层如何使用或覆盖 |
| --- | --- | --- |
| `NpcCoreConfigKey` | 已完成 L1 NPC 的 CDE 核心键 | 定义于 `npc/base/ConfigBase.scala`，完整 L1 Config 自动提供 |
| `WithNpcCoreConfig` | 将动态构造的裸 `NpcConfig` 写入 CDE 图 | 定义于 `npc/base/ConfigBase.scala`；通常应优先使用完整 L1 Config |
| `WithFpgaPlatformConfig` | 固定平台地址和时钟 | 由 L4 `...BoardConfig` 提供 |
| `WithFpgaBoardConfig`、`WithFpgaClockMHzConfig` | 选择板卡并固定频率 | 由 L4 `...BoardConfig` 提供 |
| `WithFpgaIpAttachmentConfig` | 选择同一份 provider、M 路由与时序合同 | 定义于 `common/base/FpgaIpAttachmentTraits.scala`，由 L4 板卡层提供 |

完整 L1 Config 可直接作为 CDE `++` 链的一层；`With...` 类是局部输入或覆盖片段，而不是独立构造目标。

## 可增加的特性

| 特性 | 可直接复制到 `++` 链的名称 | 添加位置 | 是否可选 |
| --- | --- | --- | --- |
| 默认裸 FPGA 核心 | `new FpgaConfig` | `npc/core/IntegrationCore.scala` | 裸核 FPGA 必需 |
| 默认 SoC 外部 AXI 核心 | `new ExternalAxiConfig` | `npc/core/IntegrationCore.scala` | 通用 SoC 必需，且可覆盖 |
| 已完成 NPC 的替换 | `new Rv64PipelineDualForwardingFpgaConfig` 等完整 L1 Config | `npc/core/IntegrationCore.scala` | 是；仅 IM_Zicsr |
| 平台地址与时钟 | `new WithFpgaPlatformConfig(platform)` | `base/FpgaConfigFragments.scala` | FPGA 生成必需 |
| Vivado/Vitis 实现策略 | `FpgaToolchainConfig.U55cBase` 或 `Zcu102Base` | 根部 U55C/ZCU102 终端预设 trait | FPGA 生成必需；不进入 CDE |
| M 算子 IP | `new WithFpgaIpAttachmentConfig(attachment)` | `common/base/FpgaIpAttachmentTraits.scala` | FPGA 生成必需；profile 固化 provider、模块名、位宽、latency 与 II |
| 板卡选择 | `new WithFpgaBoardConfig(FpgaBoard.U55c)` | `base/FpgaConfigFragments.scala` | L4 物理板卡生成必需 |
| 频率覆盖 | `new WithFpgaClockMHzConfig(<board MHz>)` | `base/FpgaConfigFragments.scala` | L4 物理板卡生成必需 |
| 新 CDE 键或核心策略 | `class WithMyFpgaFeatureConfig`（命名模板，需先实现） | `base/` | 是 |
| 器件型号、pin、XDC、vendor IP | 无；不在本文件添加 | `npc/fpga/<board>/` 与 `npc/fpga-ip-generator/` | 不适用 |
