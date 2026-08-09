# L4：U55C 板卡构造

本目录是架构第 4 层：在 L3 FPGA 参数图上绑定 U55C 的物理板卡策略。它仅在目标为 U55C 时启用，
并且必须建立在 L3 FPGA 构造之上；非 FPGA 目标和其他板卡均不使用它。

| 文件 | 职责 | 是否可被更高层复用或覆盖 |
| --- | --- | --- |
| `core/U55cBoardConfig.scala` | 板卡标识、核心/平台时钟与 U55C Xilinx IP 合同 | 是；`U55cBoardConfig`、`U55c300MHzBoardConfig` 与 `U55cPerformanceMonitorBoardConfig` 是可叠加的 L4 板卡策略 |
| `Configs.scala` | U55C 裸 NPC、RV64IM 裸 NPC、性能监测频点、SPMV 资源探针与 ysyxSoC 的所有终端构造 | 是；根部只放终端 |

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
| U55C RV64IM 性能监测终端 | `new U55cRv64Npc{100,125,150,200,250,300}MHzPerformanceMonitorFpgaConfig` | `Configs.scala` | 是；仅 `run-bat`，v13 HBM trace ABI，SDB 硬件关闭 |
| U55C RV64 缓存性能监测终端 | `new U55cRv64CacheNpc{150,300}MHzPerformanceMonitorFpgaConfig` | `Configs.scala` | 是；仅 `run-bat`，教学 I$/D$，v13 trace 加 mailbox cache 状态 |
| U55C SPMV FP32 资源探针 | `new U55cSpmv32PcFp32X8192UramResourceProbeConfig` | `Configs.scala` | 是；`synthesize-only` 资产，另挂载软件 golden host |
| U55C SPMV FP64/8-lane bitstream 探针 | `new U55cSpmv32PcFp64X8192UramBitstreamConfig` | `Configs.scala` | 是；`bitstream-only` 资产，另挂载软件 golden host |
| U55C SoC 终端 | `new U55cYsyxSocFpgaConfig` | `Configs.scala` | 是 |
| U55C 板卡标识 | `new WithFpgaBoardConfig(FpgaBoard.U55c)` | L3 `common/base/FpgaConfigFragments.scala` | U55C 目标必需 |
| U55C 时钟 | `new U55cBoardConfig(coreClockMHz = ...)` | `core/U55cBoardConfig.scala` | U55C 目标必需；核心允许 100/125/150/200/225/250/300 MHz，platform HBM data kernel 固定为 300 MHz |
| U55C 地址与时钟 | `new WithFpgaPlatformConfig(FpgaPlatformSettings(...))` | `core/U55cBoardConfig.scala` | U55C 目标必需 |
| U55C 整数 IP | `U55cXilinxIpAttachment(...)` | `core/U55cBoardConfig.scala` | U55C 目标必需；同一 attachment 同时挂接 NPC 与 SoC |
| U55C 器件与实现策略 | `FpgaToolchainConfig.U55cBase` | 根部 U55C terminal 预设 | U55C 目标必需；不进入 CDE |
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
RV32/RV64 的整数 IP 路由。`U55c300MHzBoardConfig` 把 300 MHz 核心时钟、乘法 6 拍与 non-blocking Divider
attachment 封装为命名板卡策略，仍保持 `II=1`。`U55cRv64Npc300MHzFpgaConfig` 显式组合该板卡策略与
普通整数路径两拍、串行控制路径三拍、首拍取指请求寄存器化、串行整数 ALU 分离、串行结果 ID 前递关闭的 RV64 核心。这些均是
频率对应的独立开关。所有 U55C monitor 终端将 HBM/control shell 固定在 300 MHz；低于 300 MHz 的 Config
在 wrapper 内以 MMCM 生成精确核心时钟，并经每条 AXI 通道的异步 FIFO 跨域，因此核心频率不会超过 Config 后缀。

普通 U55C v11 终端固定 `FPGA_RUNTIME_TRACE=0`、`NEMU_PERFORMANCE_HTML=0` 与
`NEMU_PIPELINE_HTML=0`，不会生成第二个 HBM master、trace BO 或 URAM FIFO，且仍支持 SDB 的单步、继续执行、
寄存器和内存调试。`U55cRv64Npc{100,125,150,200,250,300}MHzPerformanceMonitorFpgaConfig` 与
`U55cRv64CacheNpc{150,300}MHzPerformanceMonitorFpgaConfig` 是独立 v13 batch-only 终端：
它在 HBM[1] 分配 8 MiB trace BO，写入前 200000 条 32-byte 记录，并使用 2048-record URAM FIFO 和
16-record 256-bit AXI bursts。缓存版本还将当前 I$/D$ 配置、instruction buffer 深度和五类计数映射到 4 KiB
mailbox 的只读寄存器，保持 trace record 不变。它支持上述核心频点，`FPGA_RUNTIME_SDB=0` 与 trace 互斥；每个频点要求独立完整 `rebuild`。
