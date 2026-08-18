// U55C RTL kernel 共用 300 MHz 的 Vitis/HBM shell。计算核心可以采用受支持的
// 更低频率；时钟与协议跨域集中在这里，产品 wrapper 不再各自复制这些逻辑。
module u55c_reset_sync (
  input wire clock,
  input wire async_reset_n,
  output wire reset_n
);
  reg [1:0] reset_pipe;
  always @(posedge clock or negedge async_reset_n) begin
    if (!async_reset_n) reset_pipe <= 2'b00;
    else reset_pipe <= {reset_pipe[0], 1'b1};
  end
  assign reset_n = reset_pipe[1];
endmodule

module u55c_core_clock #(
  parameter integer PLATFORM_CLOCK_MHZ = 300,
  parameter integer CORE_CLOCK_MHZ = 300
) (
  input wire platform_clock,
  input wire platform_reset,
  output wire core_clock,
  output wire core_reset_n
);
  localparam integer MMCM_MULTIPLY =
    (CORE_CLOCK_MHZ == 125 || CORE_CLOCK_MHZ == 250) ? 5 : 4;
  localparam integer MMCM_DIVIDE =
    (CORE_CLOCK_MHZ == 100 || CORE_CLOCK_MHZ == 125) ? 12 :
    (CORE_CLOCK_MHZ == 150) ? 8 : 6;

  generate
    if (CORE_CLOCK_MHZ == PLATFORM_CLOCK_MHZ) begin : g_native_clock
      assign core_clock = platform_clock;
      assign core_reset_n = !platform_reset;
    end else begin : g_slow_clock
      wire clkfb;
      wire clkfb_buf;
      wire clkout;
      wire locked;
      MMCME4_BASE #(
        .BANDWIDTH("OPTIMIZED"),
        .CLKFBOUT_MULT_F(MMCM_MULTIPLY),
        .CLKIN1_PERIOD(3.333),
        .CLKOUT0_DIVIDE_F(MMCM_DIVIDE),
        .CLKOUT0_DUTY_CYCLE(0.5),
        .CLKOUT0_PHASE(0.0),
        .DIVCLK_DIVIDE(1),
        .REF_JITTER1(0.010),
        .STARTUP_WAIT("FALSE")
      ) mmcm (
        .CLKIN1(platform_clock),
        .CLKFBIN(clkfb_buf),
        .RST(platform_reset),
        .PWRDWN(1'b0),
        .CLKFBOUT(clkfb),
        .CLKOUT0(clkout),
        .LOCKED(locked)
      );
      BUFG feedback_buffer (.I(clkfb), .O(clkfb_buf));
      BUFG core_clock_buffer (.I(clkout), .O(core_clock));
      u55c_reset_sync reset_sync (
        .clock(core_clock),
        .async_reset_n(!platform_reset && locked),
        .reset_n(core_reset_n)
      );
    end
  endgenerate
endmodule

module u55c_async_fifo #(
  parameter integer WIDTH = 16,
  parameter integer DEPTH = 16
) (
  input wire wr_clk,
  input wire rd_clk,
  input wire rst,
  input wire [WIDTH-1:0] din,
  input wire wr_en,
  output wire full,
  output wire [WIDTH-1:0] dout,
  input wire rd_en,
  output wire empty
);
  xpm_fifo_async #(
    .CDC_SYNC_STAGES(2),
    .DOUT_RESET_VALUE("0"),
    .ECC_MODE("no_ecc"),
    .FIFO_MEMORY_TYPE("auto"),
    .FIFO_READ_LATENCY(0),
    .FIFO_WRITE_DEPTH(DEPTH),
    .FULL_RESET_VALUE(0),
    .PROG_EMPTY_THRESH(10),
    .PROG_FULL_THRESH(10),
    .RD_DATA_COUNT_WIDTH(1),
    .READ_DATA_WIDTH(WIDTH),
    .READ_MODE("fwft"),
    .RELATED_CLOCKS(0),
    .SIM_ASSERT_CHK(0),
    .USE_ADV_FEATURES("0000"),
    .WAKEUP_TIME(0),
    .WRITE_DATA_WIDTH(WIDTH),
    .WR_DATA_COUNT_WIDTH(1)
  ) impl (
    .rst(rst),
    .wr_clk(wr_clk),
    .wr_en(wr_en),
    .din(din),
    .full(full),
    .wr_ack(),
    .overflow(),
    .prog_full(),
    .wr_data_count(),
    .almost_full(),
    .wr_rst_busy(),
    .rd_clk(rd_clk),
    .rd_en(rd_en),
    .dout(dout),
    .empty(empty),
    .underflow(),
    .prog_empty(),
    .rd_data_count(),
    .almost_empty(),
    .data_valid(),
    .rd_rst_busy(),
    .sleep(1'b0),
    .injectsbiterr(1'b0),
    .injectdbiterr(1'b0),
    .sbiterr(),
    .dbiterr()
  );
endmodule

// 单向 Decoupled/valid-ready 通道。同频时直通以保持周期等价；异频核心则与
// 下方 AXI transport 采用相同的 FIFO 合同。
module u55c_decoupled_cdc #(
  parameter integer WIDTH = 1,
  parameter integer DEPTH = 16,
  parameter integer SAME_CLOCK = 0
) (
  input wire src_clock,
  input wire dst_clock,
  input wire reset,
  input wire [WIDTH-1:0] src_bits,
  input wire src_valid,
  output wire src_ready,
  output wire [WIDTH-1:0] dst_bits,
  output wire dst_valid,
  input wire dst_ready
);
  generate
    if (SAME_CLOCK != 0) begin : g_bypass
      assign src_ready = dst_ready;
      assign dst_bits = src_bits;
      assign dst_valid = src_valid;
    end else begin : g_async
      wire full;
      wire empty;
      u55c_async_fifo #(.WIDTH(WIDTH), .DEPTH(DEPTH)) fifo (
        .wr_clk(src_clock),
        .rd_clk(dst_clock),
        .rst(reset),
        .din(src_bits),
        .wr_en(src_valid && !full),
        .full(full),
        .dout(dst_bits),
        .rd_en(dst_ready && !empty),
        .empty(empty)
      );
      assign src_ready = !full;
      assign dst_valid = !empty;
    end
  endgenerate
endmodule

// 稳定的控制/状态电平。多 bit 使用者必须保持 src_bits，直到接收协议观察到
// 对应的电平变化。
module u55c_cdc_bus #(
  parameter integer WIDTH = 1,
  parameter integer SAME_CLOCK = 0
) (
  input wire src_clock,
  input wire dst_clock,
  input wire reset,
  input wire [WIDTH-1:0] src_bits,
  output wire [WIDTH-1:0] dst_bits
);
  generate
    if (SAME_CLOCK != 0) begin : g_bypass
      assign dst_bits = src_bits;
    end else begin : g_async
      reg [WIDTH-1:0] meta;
      reg [WIDTH-1:0] synced;
      always @(posedge dst_clock or posedge reset) begin
        if (reset) begin
          meta <= {WIDTH{1'b0}};
          synced <= {WIDTH{1'b0}};
        end else begin
          meta <= src_bits;
          synced <= meta;
        end
      end
      assign dst_bits = synced;
    end
  endgenerate
endmodule

// 面向 U55C HBM 的 AXI4 read transport。它只覆盖 AR/R：加速器输入路径不需要
// 写通道或 NPC 的地址策略。
module u55c_axi4_read_cdc #(
  parameter integer ADDR_WIDTH = 64,
  parameter integer DATA_WIDTH = 512,
  parameter integer ID_WIDTH = 4,
  parameter integer FIFO_DEPTH = 16,
  parameter integer SAME_CLOCK = 0
) (
  input wire core_clock,
  input wire shell_clock,
  input wire reset,

  input wire core_ar_valid,
  output wire core_ar_ready,
  input wire [ID_WIDTH-1:0] core_ar_id,
  input wire [ADDR_WIDTH-1:0] core_ar_addr,
  input wire [7:0] core_ar_len,
  input wire [2:0] core_ar_size,
  input wire [1:0] core_ar_burst,
  input wire core_ar_lock,
  input wire [3:0] core_ar_cache,
  input wire [2:0] core_ar_prot,
  input wire [3:0] core_ar_qos,
  output wire core_r_valid,
  input wire core_r_ready,
  output wire [ID_WIDTH-1:0] core_r_id,
  output wire [DATA_WIDTH-1:0] core_r_data,
  output wire [1:0] core_r_resp,
  output wire core_r_last,

  output wire shell_ar_valid,
  input wire shell_ar_ready,
  output wire [ID_WIDTH-1:0] shell_ar_id,
  output wire [ADDR_WIDTH-1:0] shell_ar_addr,
  output wire [7:0] shell_ar_len,
  output wire [2:0] shell_ar_size,
  output wire [1:0] shell_ar_burst,
  output wire shell_ar_lock,
  output wire [3:0] shell_ar_cache,
  output wire [2:0] shell_ar_prot,
  output wire [3:0] shell_ar_qos,
  input wire shell_r_valid,
  output wire shell_r_ready,
  input wire [ID_WIDTH-1:0] shell_r_id,
  input wire [DATA_WIDTH-1:0] shell_r_data,
  input wire [1:0] shell_r_resp,
  input wire shell_r_last
);
  localparam integer AR_WIDTH = ID_WIDTH + ADDR_WIDTH + 8 + 3 + 2 + 1 + 4 + 3 + 4;
  localparam integer R_WIDTH = ID_WIDTH + DATA_WIDTH + 2 + 1;
  wire [AR_WIDTH-1:0] core_ar_payload = {
    core_ar_id, core_ar_addr, core_ar_len, core_ar_size, core_ar_burst,
    core_ar_lock, core_ar_cache, core_ar_prot, core_ar_qos
  };
  wire [AR_WIDTH-1:0] shell_ar_payload;
  wire [R_WIDTH-1:0] shell_r_payload = {
    shell_r_id, shell_r_data, shell_r_resp, shell_r_last
  };
  wire [R_WIDTH-1:0] core_r_payload;

  u55c_decoupled_cdc #(
    .WIDTH(AR_WIDTH),
    .DEPTH(FIFO_DEPTH),
    .SAME_CLOCK(SAME_CLOCK)
  ) ar_cdc (
    .src_clock(core_clock),
    .dst_clock(shell_clock),
    .reset(reset),
    .src_bits(core_ar_payload),
    .src_valid(core_ar_valid),
    .src_ready(core_ar_ready),
    .dst_bits(shell_ar_payload),
    .dst_valid(shell_ar_valid),
    .dst_ready(shell_ar_ready)
  );
  assign {
    shell_ar_id, shell_ar_addr, shell_ar_len, shell_ar_size, shell_ar_burst,
    shell_ar_lock, shell_ar_cache, shell_ar_prot, shell_ar_qos
  } = shell_ar_payload;

  u55c_decoupled_cdc #(
    .WIDTH(R_WIDTH),
    .DEPTH(FIFO_DEPTH),
    .SAME_CLOCK(SAME_CLOCK)
  ) r_cdc (
    .src_clock(shell_clock),
    .dst_clock(core_clock),
    .reset(reset),
    .src_bits(shell_r_payload),
    .src_valid(shell_r_valid),
    .src_ready(shell_r_ready),
    .dst_bits(core_r_payload),
    .dst_valid(core_r_valid),
    .dst_ready(core_r_ready)
  );
  assign {core_r_id, core_r_data, core_r_resp, core_r_last} = core_r_payload;
endmodule
