# L2：ysyxSoC 构造

本目录负责架构第 2 层：将 NPC 放入 Rocket/ysyxSoC 的 CDE 图。它只在需要 ysyxSoC 时启用；
裸 NPC、本地仿真核心和裸核 FPGA 均可跳过本层。

## 文件职责

| 文件 | 职责 | 是否是上层可复用成品 |
| --- | --- | --- |
| `base/YsyxBaseConfig.scala` | Rocket RV32 与边缘总线底层 | 否；只由 core 组合 |
| `base/YsyxParameters.scala` | ysyxSoC 平台、ChipLink、AXI SDRAM 的 CDE 键和查询接口 | 否；供 L2/L3 读取参数 |
| `core/YsyxCore.scala` | 默认 NPC 与可复用 SoC 图 | 是；终端或板卡直接使用 `YsyxSocConfig`/`YsyxElaborateConfig` |
| `Configs.scala` | 本地仿真终端 | 是；只包含带 `SocTerminalConfig` 的无参终端 |

`YsyxSocConfig` 的默认核心是完整 L1 成品 `ExternalAxiConfig`。把另一个完整 L1 NPC Config
置于其左侧，即可替换 SoC 内的 NPC，而 Rocket/外设配置保持不变。

## 可增加的特性

| 特性 | 可直接复制到 `++` 链的名称 | 添加位置 | 是否可选 |
| --- | --- | --- | --- |
| Rocket RV32/边缘总线基础 | `new BaseYsyxConfig` | `base/YsyxBaseConfig.scala` | L2 core 构造必需；终端不直接调用 |
| 默认 SoC | `new YsyxSocConfig` | `core/YsyxCore.scala` | L2 构造必需 |
| 可复用 SoC 图 | `new YsyxElaborateConfig` | `core/YsyxCore.scala` | 是；供板卡或直接 Scala elaboration 叠加，不是 Make 终端 |
| 本地仿真 SoC 终端 | `new YsyxSimulationConfig` | `Configs.scala` | 是；未绑定 FPGA 板卡时使用本地 Verilator host |
| 平台参数查询 | `YsyxPlatformParameters.isDpiSimulation`、`YsyxPlatformParameters.isFpga` | `base/YsyxParameters.scala` | 模块内部使用 |
| 默认 NPC 覆盖 | `new FpgaConfig` 或其他完整 L1 NPC Config | `npc/core/IntegrationCore.scala` | 是 |
| 新 SoC 外设/地址映射 | `class WithMySocFeatureConfig`（命名模板，需先实现） | 新的 ysyx CDE Config | 是 |
| ISA、流水线和算术策略 | 无；回到 L1 `npc/` | L1 | 不适用 |
| 板卡时钟、引脚、厂商平台 | 无；转入 L3/L4 | L3/L4 | 不适用 |

`YsyxElaborateConfig` 与 `YsyxSimulationConfig` 的硬件组合相同；前者只是可复用 CDE 图，后者混入
`NemuSimulationConstructionConfig` 并提供 `NemuHostConfig.LocalPipelineTrace`，成为可运行终端。
没有 `FpgaBoardKey` 的 SoC CDE 图自动使用本地
Verilator 分支；L4 板卡终端只要叠加自身 `...BoardConfig` 即自动转入 FPGA 分支，无需额外的平台
Config。
