# L4：U55C 板卡构造

本目录是架构第 4 层：在 L3 FPGA 参数图上绑定 U55C 的物理板卡策略。它仅在目标为 U55C 时启用，
并且必须建立在 L3 FPGA 构造之上；非 FPGA 目标和其他板卡均不使用它。

| 文件 | 职责 | 是否可被更高层复用或覆盖 |
| --- | --- | --- |
| `core/U55cBoardConfig.scala` | 板卡标识、频率、U55C Xilinx IP 合同与 Debug trace 策略 | 是；`U55cBoardConfig`、`U55c300MHzBoardConfig` 与 `U55c300MHzDebugBoardConfig` 是可叠加的 L4 板卡策略 |
| `Configs.scala` | U55C 裸 NPC、RV64IM 裸 NPC、300 MHz 时序实验与 ysyxSoC 的所有终端构造 | 是；根部只放终端 |

`U55cNpcFpgaConfig` 直接组合 `U55cBoardConfig ++ FpgaConfig`。
`U55cYsyxSocFpgaConfig` 以 `U55cBoardConfig ++ FpgaConfig ++ YsyxElaborateConfig` 覆盖通用 SoC
的默认 NPC；板卡键自动选择 SoC 的 FPGA 分支。所有完整终端均在 `Configs.scala`，以统一的
`fpga` 作用域发现，再由 `TARGET=NPC|SOC` 选择对应生成入口。

## 可增加的特性

| 特性 | 可直接复制到 `++` 链的名称 | 添加位置 | 是否可选 |
| --- | --- | --- | --- |
| U55C 板卡层 | `new U55cBoardConfig` | `core/U55cBoardConfig.scala` | U55C 目标必需 |
| U55C 300 MHz 板卡层 | `new U55c300MHzBoardConfig` | `core/U55cBoardConfig.scala` | 是；乘法延迟 6 拍，Divider 使用 non-blocking 固定时延接口，II=1 |
| U55C 裸 NPC 终端 | `new U55cNpcFpgaConfig` | `Configs.scala` | 是 |
| U55C RV64IM 裸 NPC 终端 | `new U55cRv64NpcFpgaConfig` | `Configs.scala` | 是；F/D 禁用 |
| U55C RV64IM 300 MHz 时序实验终端 | `new U55cRv64Npc300MHzFpgaConfig` | `Configs.scala` | 是；F/D 禁用 |
| U55C RV64IM 300 MHz Debug 终端 | `new U55cRv64Npc300MHzDebugFpgaConfig` | `Configs.scala` | 是；仅此终端启用 v12 trace |
| U55C Debug trace 策略 | `new U55c300MHzDebugBoardConfig` | `core/U55cBoardConfig.scala` | 是；HBM[1]、16 MiB、200000 条，URAM FIFO 默认 4096 条 |
| U55C SoC 终端 | `new U55cYsyxSocFpgaConfig` | `Configs.scala` | 是 |
| U55C 板卡标识 | `new WithFpgaBoardConfig(FpgaBoard.U55c)` | L3 `common/base/FpgaConfigFragments.scala` | U55C 目标必需 |
| U55C 时钟 | `new U55cBoardConfig(clockMHz = 125)` | `core/U55cBoardConfig.scala` | U55C 目标必需；允许频率由 `npc/fpga/u55c/config.mk` 的物理能力表限制 |
| U55C 地址与时钟 | `new WithFpgaPlatformConfig(FpgaPlatformSettings(...))` | `core/U55cBoardConfig.scala` | U55C 目标必需 |
| U55C 整数 IP | `U55cXilinxIpAttachment(...)` | `core/U55cBoardConfig.scala` | U55C 目标必需；同一 attachment 同时挂接 NPC 与 SoC |
| U55C 器件与实现策略 | `FpgaToolchainConfig.U55cBase` | 根部 `U55cNpcTerminal`/`U55cSocTerminal` 预设 | U55C 目标必需；不进入 CDE |
| Vitis XRT 环境策略 | `U55cBase.flow.vitisXrtMode = "unset"` | `FpgaToolchainConfig.scala` | U55C 构造必需；只为 `v++` 选择 Vitis 自带的封装工具 |
| U55C 构造并行度、策略搜索和实现后报告 | `U55cBase.flow`、`U55cBase.reports` | `FpgaToolchainConfig.scala` | 是；worker jobs、策略搜索、时序路径深度和诊断报告开关均由终端冻结 |
| 默认 NPC 覆盖 | `new Rv64PipelineDualForwardingFpgaConfig` 等完整 L1 Config | L1 `core/IntegrationCore.scala` | 是；仅 IM_Zicsr |
| 新 U55C CDE 特性 | `class WithMyU55cFeatureConfig`（命名模板，需先实现） | 新的 U55C Config | 是 |
| wrapper、约束和 vendor IP 文件 | 无；不在 Scala Config 添加 | `npc/fpga/u55c/` 与 `npc/fpga-ip-generator/` | U55C bitstream 必需 |

`U55cRv64NpcFpgaConfig` 是 `RV64IM_Zicsr` 的可运行终端。所有公开 FPGA Config 固定
`F=0`、`D=0`；PL 不生成硬件 FPR、本地 FPU、浮点 IP 或 NEMU 指令代执行服务。浮点学习应使用
本地 Verilator/NEMU 仿真 Config。当前 XRT 没有可由宿主消费的 IRQ
文件描述符，因此 `FPGA_NOTIFICATION_MODE=xrt-poll` 仍用于调试与运行控制，且不会送入 RISC-V 核的
`MEIP`。

`U55cBoardConfig` 不接收 XLEN：attachment 通过右侧完成的 `NpcCoreConfigKey` 自动生成匹配
RV32/RV64 的整数 IP 路由。`U55c300MHzBoardConfig` 把物理时钟、乘法 6 拍与 non-blocking Divider
attachment 封装为命名板卡策略，仍保持 `II=1`。`U55cRv64Npc300MHzFpgaConfig` 显式组合该板卡策略与
普通整数路径两拍、串行控制路径三拍、首拍取指请求寄存器化、串行整数 ALU 分离、串行结果 ID 前递关闭的 RV64 核心。这些均是
频率对应的独立开关，不会由 `clockMHz` 自动推导，因此未来 250 MHz 终端可逐项选择。

`U55cRv64Npc300MHzDebugFpgaConfig` 在同一 RV64 300 MHz 核上叠加 `U55c300MHzDebugBoardConfig`。
它通过 `RuntimeTraceProfile.U55cDebug` 冻结 v12 trace ABI；如需调整片上 FIFO 深度，应在 Config 中用
`RuntimeTraceProfile.U55cDebug.copy(cacheRecords = <2 的幂>)` 构造新的 profile，再传给
`U55c300MHzDebugBoardConfig`。这会改变 URAM 数量和生成 RTL，不能以 `host-build` 替代 `rebuild`。
