// 只用于 Verilator 的 U55C 物理/IP blackbox 声明；综合仍使用 Xilinx 实现。
module MMCME4_BASE #(
  parameter BANDWIDTH = "OPTIMIZED",
  parameter real CLKFBOUT_MULT_F = 1.0,
  parameter real CLKIN1_PERIOD = 1.0,
  parameter real CLKOUT0_DIVIDE_F = 1.0,
  parameter real CLKOUT0_DUTY_CYCLE = 0.5,
  parameter real CLKOUT0_PHASE = 0.0,
  parameter integer DIVCLK_DIVIDE = 1,
  parameter real REF_JITTER1 = 0.0,
  parameter STARTUP_WAIT = "FALSE"
) (
  input wire CLKIN1,
  input wire CLKFBIN,
  input wire RST,
  input wire PWRDWN,
  output wire CLKFBOUT,
  output wire CLKOUT0,
  output wire LOCKED
);
  assign CLKFBOUT = CLKIN1;
  assign CLKOUT0 = CLKIN1;
  assign LOCKED = !RST && !PWRDWN;
endmodule

module BUFG (input wire I, output wire O);
  assign O = I;
endmodule

module xpm_fifo_async #(
  parameter integer CDC_SYNC_STAGES = 2,
  parameter DOUT_RESET_VALUE = "0",
  parameter ECC_MODE = "no_ecc",
  parameter FIFO_MEMORY_TYPE = "auto",
  parameter integer FIFO_READ_LATENCY = 0,
  parameter integer FIFO_WRITE_DEPTH = 16,
  parameter integer FULL_RESET_VALUE = 0,
  parameter integer PROG_EMPTY_THRESH = 10,
  parameter integer PROG_FULL_THRESH = 10,
  parameter integer RD_DATA_COUNT_WIDTH = 1,
  parameter integer READ_DATA_WIDTH = 8,
  parameter READ_MODE = "fwft",
  parameter integer RELATED_CLOCKS = 0,
  parameter integer SIM_ASSERT_CHK = 0,
  parameter USE_ADV_FEATURES = "0000",
  parameter integer WAKEUP_TIME = 0,
  parameter integer WRITE_DATA_WIDTH = 8,
  parameter integer WR_DATA_COUNT_WIDTH = 1
) (
  input wire rst,
  input wire wr_clk,
  input wire wr_en,
  input wire [WRITE_DATA_WIDTH-1:0] din,
  output wire full,
  output wire wr_ack,
  output wire overflow,
  output wire prog_full,
  output wire [WR_DATA_COUNT_WIDTH-1:0] wr_data_count,
  output wire almost_full,
  output wire wr_rst_busy,
  input wire rd_clk,
  input wire rd_en,
  output wire [READ_DATA_WIDTH-1:0] dout,
  output wire empty,
  output wire underflow,
  output wire prog_empty,
  output wire [RD_DATA_COUNT_WIDTH-1:0] rd_data_count,
  output wire almost_empty,
  output wire data_valid,
  output wire rd_rst_busy,
  input wire sleep,
  input wire injectsbiterr,
  input wire injectdbiterr,
  output wire sbiterr,
  output wire dbiterr
);
  assign full = 1'b0;
  assign wr_ack = wr_en;
  assign overflow = 1'b0;
  assign prog_full = 1'b0;
  assign wr_data_count = {WR_DATA_COUNT_WIDTH{1'b0}};
  assign almost_full = 1'b0;
  assign wr_rst_busy = rst;
  assign dout = {READ_DATA_WIDTH{1'b0}};
  assign empty = 1'b1;
  assign underflow = 1'b0;
  assign prog_empty = 1'b1;
  assign rd_data_count = {RD_DATA_COUNT_WIDTH{1'b0}};
  assign almost_empty = 1'b1;
  assign data_valid = 1'b0;
  assign rd_rst_busy = rst;
  assign sbiterr = 1'b0;
  assign dbiterr = 1'b0;
endmodule

module SpmvFp64MulXilinxCore (
  input wire aclk,
  input wire s_axis_a_tvalid,
  input wire [63:0] s_axis_a_tdata,
  input wire s_axis_b_tvalid,
  input wire [63:0] s_axis_b_tdata,
  output wire m_axis_result_tvalid,
  output wire [63:0] m_axis_result_tdata
);
  assign m_axis_result_tvalid = 1'b0;
  assign m_axis_result_tdata = 64'b0;
endmodule
