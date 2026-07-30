# U55C 板卡层

- `config.mk`：U55C 核心/平台时钟合同、HBM 地址映射和算术 IP 适配时序；器件、XRT 平台与
  Vivado/Vitis 流程来自终端 `FpgaToolchainConfig`。
- `rtl/`：将 `NpcFpgaTop` 封装为 Vitis RTL kernel。
- `tcl/`：打包 XO 的 Vivado IP packager 流程。
- `constraints/`：独立内核检查使用的时钟约束。
- `link.cfg`：仅保存 kernel 到 HBM bank 的连接关系。时钟和 `[vivado]` 设置由
  构建流程从冻结的 Scala profile 生成，避免同一策略在两个文件中漂移。

U55C RTL kernel 使用显式 `ip_c`、`ap_ctrl_hs`、4 KiB `s_axi_control` 元数据和匹配 XLEN 的 AXI
数据宽度，让 XRT 可以打开 CU context；打包时会将 XLEN define 固化进 wrapper 副本，防止 IP packager
重新导入 RTL 时退回 32-bit。NPC 的启动、复位和停止仍由该窗口上的 mailbox 寄存器处理，不使用 AP 控制寄存器传递运行状态。

普通 U55C wrapper 只暴露 `m_axi_gmem`，对应 v11。`U55cRv64Npc{100,125,150,200,250,300}MHzPerformanceMonitorFpgaConfig`
和 `U55cRv64CacheNpc{150,300}MHzPerformanceMonitorFpgaConfig`
在打包时定义 `NPC_FPGA_RUNTIME_TRACE`，额外暴露固定 256-bit 的 `m_axi_trace`，并在构造生成的
Vitis link 配置中绑定 `HBM[1]`；guest memory 始终独占 `HBM[0]`。这些 v13 终端只支持
`run-bat`，并以 `FPGA_RUNTIME_SDB=0` 移除交互 halt/step 与宽架构快照路径。

缓存版 monitor 不增加 wrapper 端口或 HBM 写通道。它通过现有 4 KiB `s_axi_control` mailbox 的只读区导出
I$/D$ 启用状态、容量、line、sets/ways、映射/替换/写策略、instruction buffer 深度及 hits、misses、refills、
writebacks、evictions。`mtestexit` 会在 D$ drain 后、core reset 前把这些状态快照到 mailbox，NEMU 性能页
据此显示硬件实际 cache 配置和命中率。

`xilinx_u55c_gen3x16_xdma_3_202210_1` 对这类 HBM-connected RTL kernel 提供固定 300 MHz 的
`DATA_CLK`（另有 500 MHz kernel clock 和不可选作 kernel `ap_clk` 的固定 100 MHz freerun clock）。
`FPGA_PLATFORM_CLOCK_MHZ` 因而始终为 300，Vitis link 和 `verify-data-clock.sh` 只校验这一接口事实。
`FPGA_CLOCK_MHZ` 则是核心目标，允许 100/125/150/200/250/300 MHz。低频 profile 由 wrapper 的 MMCM
生成核心时钟，并通过 `xpm_fifo_async` 缓冲每个 AXI4/AXI-Lite 通道；这不是仅修改 `freqHz` 的性能节流。

`FpgaToolchainConfig.U55cBase.flow` 的综合/实现 jobs 是宿主 Vivado/Vitis 的 worker 上限，不是
FPGA 内 CPU 核数。策略搜索设为 `true` 时，Vitis 会保留默认 run，并为所选实现策略额外启动 run，
显著增加内存占用；自定义终端可通过分组 `copy(...)` 重载这些值。

终端 `reports` 还固定时序路径深度和六个 `FPGA_REPORT_*` 开关。Vitis 的每个 implementation run
在 post-route 时先执行自身默认 hook，再在该 run
目录下写入 `npc-implementation-reports/`；多策略并行时报告不会互相覆盖。
