# L2：ysyxSoC 构造

本目录负责架构第 2 层：将 NPC 放入 Rocket/ysyxSoC 的 CDE 图。它只在需要 ysyxSoC 时启用；
裸 NPC、DPI 核心和裸核 FPGA 均可跳过本层。

## 文件职责

| 文件 | 职责 | 是否是上层可复用成品 |
| --- | --- | --- |
| `YsyxBaseConfig.scala` | Rocket RV32 和边缘总线基础层，以及平台标识片段 | `BaseYsyxConfig` 可复用，但通常经 SoC 成品间接使用 |
| `SocConfig.scala` | 默认 NPC、SoC 平台模式与通用 SoC 构造 | 是；`YsyxSocConfig` 是 L2 基类 |

`YsyxSocConfig` 的默认核心是 `NpcExternalAxiCdeConfig`。把一个提供 `NpcCoreConfigKey` 的层置于
其左侧，即可替换 SoC 内的 NPC，而 Rocket/外设配置保持不变。

## 可增加的特性

| 特性 | 可直接复制到 `++` 链的名称 | 添加位置 | 是否可选 |
| --- | --- | --- | --- |
| Rocket RV32/边缘总线基础 | `new BaseYsyxConfig` | `YsyxBaseConfig.scala` | L2 构造必需 |
| 默认 SoC | `new YsyxSocConfig` | `SocConfig.scala` | L2 构造必需 |
| standalone 平台 | `new YsyxStandaloneConfig` | `SocConfig.scala` | 是；与其他平台模式互斥 |
| 仿真平台 | `new YsyxSimulationConfig` | `SocConfig.scala` | 是；与其他平台模式互斥 |
| 通用 FPGA SoC 平台 | `new YsyxSocFpgaConfig` | `SocConfig.scala` | 仅 SoC FPGA 需要 |
| 显式平台标识 | `new WithYsyxPlatformConfig(YsyxPlatform.Fpga)` | `YsyxBaseConfig.scala` | 是 |
| 默认 NPC 覆盖 | `new WithNpcCoreConfig(customNpc)` | 由 L3 提供 | 是 |
| 新 SoC 外设/地址映射 | `class WithMySocFeatureConfig`（命名模板，需先实现） | 新的 ysyx CDE Config | 是 |
| ISA、流水线和算术策略 | 无；回到 L1 `npc/` | L1 | 不适用 |
| 板卡时钟、引脚、厂商平台 | 无；转入 L3/L4 | L3/L4 | 不适用 |

## L3 的例外位置

`YsyxSocFpgaConfig` 定义在 `SocConfig.scala`，但逻辑上属于 L3：它仅在 L2 SoC 上写入
`YsyxPlatform.Fpga`。该类没有搬到 `fpga/common/`，因为它直接依赖 Rocket/ysyxSoC 配置，必须由
Mill `ysyxsoc` 编译。L3 板卡 Config 可直接复用并覆盖它。

`YsyxStandaloneConfig` 和 `YsyxSimulationConfig` 是 L2 的终端模式；`YsyxSocConfig` 与
`YsyxSocFpgaConfig` 则是为更高层组合保留的成品。
