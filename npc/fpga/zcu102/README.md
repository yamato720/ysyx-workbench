# ZCU102 板卡层

- `config.mk`：ZCU102 允许频率、PS DDR 地址映射和算术 IP 适配时序；器件与 Vivado
  流程来自终端 `FpgaToolchainConfig`。
- `rtl/`：连接 `NpcFpgaTop` 与 ZynqMP PS AXI 接口的 PL wrapper。
- `tcl/`：裸核综合以及 PS/PL block design 链接流程。
- `constraints/`：独立时钟检查使用的板卡约束。
- `scripts/`：生成 reserved-memory 设备树片段和 NEMU 运行环境。

实现后由 Config 固定时序报告深度及 `FPGA_REPORT_*` 诊断开关。`link.tcl` 在 `impl_1` 打开后写入
`<implementation-run>/npc-implementation-reports/`，并保留邻近 bitstream 的 `npc.timing.rpt` 兼容既有
分析流程。
