# NPC-ZCU102 Debugger RTL Interface

本文件记录第一版 RTL 接口，不直接实现 CPU 或 SoC。

## 1. NPC core export interface

NPC 侧后续应提供一个可综合顶层，形态类似：

```systemverilog
module NPCFPGACore (
  input  logic         clock,
  input  logic         reset,

  input  logic         dbg_run,
  input  logic         dbg_halt_req,
  input  logic         dbg_single_step,
  input  logic         dbg_trace_enable,
  output logic         dbg_running,
  output logic         dbg_halted,
  output logic         dbg_busy,

  output logic         commit_valid,
  output logic [63:0]  commit_pc,
  output logic [31:0]  commit_inst,
  output logic [4:0]   commit_rd,
  output logic         commit_rd_wen,
  output logic [63:0]  commit_rd_wdata,

  output logic         mem_valid,
  output logic         mem_write,
  output logic [63:0]  mem_addr,
  output logic [63:0]  mem_wdata,
  output logic [63:0]  mem_rdata,
  output logic [7:0]   mem_wstrb,

  // AXI4-Lite instruction master
  output logic         i_arvalid,
  input  logic         i_arready,
  output logic [31:0]  i_araddr,
  input  logic         i_rvalid,
  output logic         i_rready,
  input  logic [63:0]  i_rdata,
  input  logic [1:0]   i_rresp,

  // AXI4-Lite data master
  output logic         d_awvalid,
  input  logic         d_awready,
  output logic [31:0]  d_awaddr,
  output logic         d_wvalid,
  input  logic         d_wready,
  output logic [63:0]  d_wdata,
  output logic [7:0]   d_wstrb,
  input  logic         d_bvalid,
  output logic         d_bready,
  input  logic [1:0]   d_bresp,
  output logic         d_arvalid,
  input  logic         d_arready,
  output logic [31:0]  d_araddr,
  input  logic         d_rvalid,
  output logic         d_rready,
  input  logic [63:0]  d_rdata,
  input  logic [1:0]   d_rresp
);
```

实际 Chisel 里可以继续用 `AxiLiteMasterIO` Bundle，不必手写扁平端口；Vivado wrapper 生成时再展开。

## 2. Debugger top interface

ZCU102 runtime 侧建议：

```systemverilog
module ZCU102NPCDebugger (
  input  logic        pl_clk,
  input  logic        pl_resetn,

  // PS AXI-Lite slave for control/status
  input  logic        s_axil_awvalid,
  output logic        s_axil_awready,
  input  logic [31:0] s_axil_awaddr,
  input  logic        s_axil_wvalid,
  output logic        s_axil_wready,
  input  logic [31:0] s_axil_wdata,
  input  logic [3:0]  s_axil_wstrb,
  output logic        s_axil_bvalid,
  input  logic        s_axil_bready,
  output logic [1:0]  s_axil_bresp,
  input  logic        s_axil_arvalid,
  output logic        s_axil_arready,
  input  logic [31:0] s_axil_araddr,
  output logic        s_axil_rvalid,
  input  logic        s_axil_rready,
  output logic [31:0] s_axil_rdata,
  output logic [1:0]  s_axil_rresp,

  output logic        irq_to_ps
);
```

内部实例化：

```text
NPCFPGACore
AXI guest memory
simple MMIO slave
trace buffer
control/status regs
```

## 3. 第一版实现约束

- 单 clock domain：`pl_clk`。
- AXI-Lite data width：PS control 32-bit，NPC memory 64-bit。
- guest memory 先 BRAM。
- no cache coherency。
- no PS software in CPU normal memory path。
