# U55C 板卡层

- `config.mk`：U55C 核心/平台时钟合同、HBM 地址映射和算术 IP 适配时序；器件、XRT 平台与
  Vivado/Vitis 流程来自终端 `FpgaToolchainConfig`。
- `rtl/`：将 `NpcFpgaTop` 封装为 Vitis RTL kernel；`rtl/spmv/` 保存独立的 32-PC 资源探针 wrapper。
- `tcl/`：打包 XO 的 Vivado IP packager 流程；`tcl/spmv/` 执行资源探针 OOC 综合与报告。
- `constraints/`：独立内核检查使用的时钟约束。
- `link.cfg`：仅保存 kernel 到 HBM bank 的连接关系。时钟和 `[vivado]` 设置由
  构建流程从冻结的 Scala profile 生成，避免同一策略在两个文件中漂移。

U55C RTL kernel 使用显式 `ip_c`、`ap_ctrl_hs`、4 KiB `s_axi_control` 元数据和匹配 XLEN 的 AXI
数据宽度，让 XRT 可以打开 CU context；打包时会将 XLEN define 固化进 wrapper 副本，防止 IP packager
重新导入 RTL 时退回 32-bit。NPC 的启动、复位和停止仍由该窗口上的 mailbox 寄存器处理，不使用 AP 控制寄存器传递运行状态。

普通 U55C wrapper 只暴露 `m_axi_gmem`，对应 v11。`U55cRv64Npc{100,125,150,200,250,300}MHzPerformanceMonitorFpgaConfig`
在打包时定义 `NPC_FPGA_RUNTIME_TRACE`，额外暴露固定 256-bit 的 `m_axi_trace`，并在构造生成的
Vitis link 配置中绑定 `HBM[1]`；guest memory 始终独占 `HBM[0]`。这些 v13 终端只支持
`run-bat`，并以 `FPGA_RUNTIME_SDB=0` 移除交互 halt/step 与宽架构快照路径。

`xilinx_u55c_gen3x16_xdma_3_202210_1` 对这类 HBM-connected RTL kernel 提供固定 300 MHz 的
`DATA_CLK`（另有 500 MHz kernel clock 和不可选作 kernel `ap_clk` 的固定 100 MHz freerun clock）。
`FPGA_PLATFORM_CLOCK_MHZ` 因而始终为 300，Vitis link 和 `verify-data-clock.sh` 只校验这一接口事实。
`FPGA_CLOCK_MHZ` 则是核心目标，允许 100/125/150/200/225/250/300 MHz。低频 profile 由 wrapper 的 MMCM
生成核心时钟，并通过 `xpm_fifo_async` 缓冲每个 AXI4/AXI-Lite 通道；这不是仅修改 `freqHz` 的性能节流。

`FpgaToolchainConfig.U55cBase.flow` 的综合/实现 jobs 是宿主 Vivado/Vitis 的 worker 上限，不是
FPGA 内 CPU 核数。策略搜索设为 `true` 时，Vitis 会保留默认 run，并为所选实现策略额外启动 run，
显著增加内存占用；自定义终端可通过分组 `copy(...)` 重载这些值。

终端 `reports` 还固定时序路径深度和六个 `FPGA_REPORT_*` 开关。Vitis 的每个 implementation run
在 post-route 时先执行自身默认 hook，再在该 run
目录下写入 `npc-implementation-reports/`；多策略并行时报告不会互相覆盖。

## SPMV 32-PC 资源探针

`U55cSpmv32PcFp32X8192UramResourceProbeConfig` 和
`U55cSpmv32PcFp64X8192UramBitstreamConfig` 都不复用 NPC mailbox、单 `m_axi_gmem` 或 NEMU host。
两者由独立 wrapper 暴露 `m_axi_pc00..m_axi_pc31` 和 4 KiB `s_axi_control`；每个 master 固定
64-bit address、512-bit data、4-bit ID，只使用读通道，完整写通道在 wrapper 内合法绑死。每路 X 基地址
位于 `0x010 + 8*i`，必须按 4096-byte burst 对齐；聚合 checksum、lane done mask、lane error mask
分别位于 `0x110`、`0x114`、`0x118`。

FP32 版本把每路返回 beat 拆成 16 个 element，写入一个 `8192 x 32` UltraRAM，再按 1 element/cycle
扫描 XOR，只执行 `elaborate -> ooc-synth` 并发布 XO/DCP/报告。FP64 版本把每路 8192 个 X 拆成四个
`2048 x 64` 双端口 UltraRAM bank；每个 512-bit beat 的 8 个 FP64 同拍写入四个 bank 的 A/B 端口，
加载后每拍从每个 bank 读两个地址，八个 X 同时进入 XOR 汇总。它的阶段为
`elaborate -> ooc-synth -> Vitis link`，只发布 XO、DCP、报告、xclbin 和 SHA-256；不实例化 FP add/mul/FMA。

公开入口：

```bash
make -C npc build config=U55cSpmv32PcFp32X8192UramResourceProbeConfig
make -C npc rebuild config=U55cSpmv32PcFp32X8192UramResourceProbeConfig
make -C npc build config=U55cSpmv32PcFp64X8192UramBitstreamConfig
make -C npc rebuild config=U55cSpmv32PcFp64X8192UramBitstreamConfig
make -C npc version
```

FP64/8-lane 版本的真实压力实验（U55C `xcu55c-fsvh2892-2L-e`，Vivado/Vitis 2022.2，225 MHz）如下：

- OOC：128 URAM288、15619 LUT、22279 FF、288 CARRY8、0 DSP；setup WNS +1.705 ns，hold WHS -0.074 ns。
- kernel routed：128/960 URAM（13.33%）、15622 LUT（1.47%）、22279 FF（0.98%）。
- 含平台互连：254284 LUT（19.51%）、350924 FF（13.46%）、200 Block RAM Tile（9.92%）、128 URAM（13.33%）；
  HBM 互连另占 197 RAMB36/FIFO，URAM 在单个 SLR 达到 40%。
- routed timing：`clk_out1_ulp_clk_wiz_0 -> ap_clk` 最差 setup -1.516 ns，`ap_clk` 内部高扇出路径 -0.928 ns，
  HBM `hbm_aclk` -0.130 ns。Vitis 已完成 synthesis、placement、routing，但因 WNS 为负没有接受最终 xclbin。

这些数据用于衡量 32-PC/8-lane 的资源、扇出和 SLR/布线压力，不宣称 225 MHz 已经完成 bitstream closure。
