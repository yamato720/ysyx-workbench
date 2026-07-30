`ifndef NPC_FPGA_XLEN
`define NPC_FPGA_XLEN 32
`endif

`ifndef NPC_FPGA_PLATFORM_CLOCK_MHZ
`define NPC_FPGA_PLATFORM_CLOCK_MHZ 300
`endif

`ifndef NPC_FPGA_CORE_CLOCK_MHZ
`define NPC_FPGA_CORE_CLOCK_MHZ `NPC_FPGA_PLATFORM_CLOCK_MHZ
`endif

module NpcFpgaKernel #(
  parameter [31:0] GUEST_MEMORY_BASE = 32'h8000_0000
) (
  input  wire                         ap_clk,
  input  wire                         ap_rst_n,
  output wire                         interrupt,

  output wire                         m_axi_gmem_awvalid,
  input  wire                         m_axi_gmem_awready,
  output wire [63:0]                  m_axi_gmem_awaddr,
  output wire [3:0]                   m_axi_gmem_awid,
  output wire [7:0]                   m_axi_gmem_awlen,
  output wire [2:0]                   m_axi_gmem_awsize,
  output wire [1:0]                   m_axi_gmem_awburst,
  output wire                         m_axi_gmem_awlock,
  output wire [3:0]                   m_axi_gmem_awcache,
  output wire [2:0]                   m_axi_gmem_awprot,
  output wire [3:0]                   m_axi_gmem_awqos,
  output wire                         m_axi_gmem_wvalid,
  input  wire                         m_axi_gmem_wready,
  output wire [`NPC_FPGA_XLEN-1:0]   m_axi_gmem_wdata,
  output wire [`NPC_FPGA_XLEN/8-1:0] m_axi_gmem_wstrb,
  output wire                         m_axi_gmem_wlast,
  input  wire                         m_axi_gmem_bvalid,
  output wire                         m_axi_gmem_bready,
  input  wire [3:0]                   m_axi_gmem_bid,
  input  wire [1:0]                   m_axi_gmem_bresp,
  output wire                         m_axi_gmem_arvalid,
  input  wire                         m_axi_gmem_arready,
  output wire [63:0]                  m_axi_gmem_araddr,
  output wire [3:0]                   m_axi_gmem_arid,
  output wire [7:0]                   m_axi_gmem_arlen,
  output wire [2:0]                   m_axi_gmem_arsize,
  output wire [1:0]                   m_axi_gmem_arburst,
  output wire                         m_axi_gmem_arlock,
  output wire [3:0]                   m_axi_gmem_arcache,
  output wire [2:0]                   m_axi_gmem_arprot,
  output wire [3:0]                   m_axi_gmem_arqos,
  input  wire                         m_axi_gmem_rvalid,
  output wire                         m_axi_gmem_rready,
  input  wire [3:0]                   m_axi_gmem_rid,
  input  wire [`NPC_FPGA_XLEN-1:0]   m_axi_gmem_rdata,
  input  wire [1:0]                   m_axi_gmem_rresp,
  input  wire                         m_axi_gmem_rlast,

`ifdef NPC_FPGA_RUNTIME_TRACE
  output wire                         m_axi_trace_awvalid,
  input  wire                         m_axi_trace_awready,
  output wire [63:0]                  m_axi_trace_awaddr,
  output wire [3:0]                   m_axi_trace_awid,
  output wire [7:0]                   m_axi_trace_awlen,
  output wire [2:0]                   m_axi_trace_awsize,
  output wire [1:0]                   m_axi_trace_awburst,
  output wire                         m_axi_trace_awlock,
  output wire [3:0]                   m_axi_trace_awcache,
  output wire [2:0]                   m_axi_trace_awprot,
  output wire [3:0]                   m_axi_trace_awqos,
  output wire                         m_axi_trace_wvalid,
  input  wire                         m_axi_trace_wready,
  output wire [255:0]                m_axi_trace_wdata,
  output wire [31:0]                 m_axi_trace_wstrb,
  output wire                         m_axi_trace_wlast,
  input  wire                         m_axi_trace_bvalid,
  output wire                         m_axi_trace_bready,
  input  wire [3:0]                   m_axi_trace_bid,
  input  wire [1:0]                   m_axi_trace_bresp,
  output wire                         m_axi_trace_arvalid,
  input  wire                         m_axi_trace_arready,
  output wire [63:0]                  m_axi_trace_araddr,
  output wire [3:0]                   m_axi_trace_arid,
  output wire [7:0]                   m_axi_trace_arlen,
  output wire [2:0]                   m_axi_trace_arsize,
  output wire [1:0]                   m_axi_trace_arburst,
  output wire                         m_axi_trace_arlock,
  output wire [3:0]                   m_axi_trace_arcache,
  output wire [2:0]                   m_axi_trace_arprot,
  output wire [3:0]                   m_axi_trace_arqos,
  input  wire                         m_axi_trace_rvalid,
  output wire                         m_axi_trace_rready,
  input  wire [3:0]                   m_axi_trace_rid,
  input  wire [255:0]                m_axi_trace_rdata,
  input  wire [1:0]                   m_axi_trace_rresp,
  input  wire                         m_axi_trace_rlast,
`endif

  input  wire                         s_axi_control_awvalid,
  output wire                         s_axi_control_awready,
  input  wire [11:0]                  s_axi_control_awaddr,
  input  wire                         s_axi_control_wvalid,
  output wire                         s_axi_control_wready,
  input  wire [31:0]                  s_axi_control_wdata,
  input  wire [3:0]                   s_axi_control_wstrb,
  output wire                         s_axi_control_bvalid,
  input  wire                         s_axi_control_bready,
  output wire [1:0]                   s_axi_control_bresp,
  input  wire                         s_axi_control_arvalid,
  output wire                         s_axi_control_arready,
  input  wire [11:0]                  s_axi_control_araddr,
  output wire                         s_axi_control_rvalid,
  input  wire                         s_axi_control_rready,
  output wire [31:0]                  s_axi_control_rdata,
  output wire [1:0]                   s_axi_control_rresp
);
  wire [63:0] core_awaddr;
  wire [63:0] core_araddr;
  wire [63:0] memory_host_base;

`ifdef NPC_FPGA_CLOCKED_CORE
  assign m_axi_gmem_awaddr = core_awaddr;
  assign m_axi_gmem_araddr = core_araddr;
`else
  assign m_axi_gmem_awaddr = memory_host_base + {32'b0, core_awaddr[31:0] - GUEST_MEMORY_BASE};
  assign m_axi_gmem_araddr = memory_host_base + {32'b0, core_araddr[31:0] - GUEST_MEMORY_BASE};
`endif

`ifdef NPC_FPGA_CLOCKED_CORE
  npc_u55c_clocked_top #(
    .GUEST_MEMORY_BASE(GUEST_MEMORY_BASE),
    .PLATFORM_CLOCK_MHZ(`NPC_FPGA_PLATFORM_CLOCK_MHZ),
    .CORE_CLOCK_MHZ(`NPC_FPGA_CORE_CLOCK_MHZ)
  ) core (
`else
  NpcFpgaTop core (
`endif
    .clock(ap_clk),
    .reset(!ap_rst_n),
    .io_interrupt(1'b0),
    .io_master_aw_ready(m_axi_gmem_awready),
    .io_master_aw_valid(m_axi_gmem_awvalid),
    .io_master_aw_bits_id(m_axi_gmem_awid),
    .io_master_aw_bits_addr(core_awaddr),
    .io_master_aw_bits_len(m_axi_gmem_awlen),
    .io_master_aw_bits_size(m_axi_gmem_awsize),
    .io_master_aw_bits_burst(m_axi_gmem_awburst),
    .io_master_aw_bits_lock(m_axi_gmem_awlock),
    .io_master_aw_bits_cache(m_axi_gmem_awcache),
    .io_master_aw_bits_prot(m_axi_gmem_awprot),
    .io_master_aw_bits_qos(m_axi_gmem_awqos),
    .io_master_w_ready(m_axi_gmem_wready),
    .io_master_w_valid(m_axi_gmem_wvalid),
    .io_master_w_bits_data(m_axi_gmem_wdata),
    .io_master_w_bits_strb(m_axi_gmem_wstrb),
    .io_master_w_bits_last(m_axi_gmem_wlast),
    .io_master_b_ready(m_axi_gmem_bready),
    .io_master_b_valid(m_axi_gmem_bvalid),
    .io_master_b_bits_id(m_axi_gmem_bid),
    .io_master_b_bits_resp(m_axi_gmem_bresp),
    .io_master_ar_ready(m_axi_gmem_arready),
    .io_master_ar_valid(m_axi_gmem_arvalid),
    .io_master_ar_bits_id(m_axi_gmem_arid),
    .io_master_ar_bits_addr(core_araddr),
    .io_master_ar_bits_len(m_axi_gmem_arlen),
    .io_master_ar_bits_size(m_axi_gmem_arsize),
    .io_master_ar_bits_burst(m_axi_gmem_arburst),
    .io_master_ar_bits_lock(m_axi_gmem_arlock),
    .io_master_ar_bits_cache(m_axi_gmem_arcache),
    .io_master_ar_bits_prot(m_axi_gmem_arprot),
    .io_master_ar_bits_qos(m_axi_gmem_arqos),
    .io_master_r_ready(m_axi_gmem_rready),
    .io_master_r_valid(m_axi_gmem_rvalid),
    .io_master_r_bits_id(m_axi_gmem_rid),
    .io_master_r_bits_data(m_axi_gmem_rdata),
    .io_master_r_bits_resp(m_axi_gmem_rresp),
    .io_master_r_bits_last(m_axi_gmem_rlast),
`ifdef NPC_FPGA_RUNTIME_TRACE
    .io_trace_aw_ready(m_axi_trace_awready),
    .io_trace_aw_valid(m_axi_trace_awvalid),
    .io_trace_aw_bits_id(m_axi_trace_awid),
    .io_trace_aw_bits_addr(m_axi_trace_awaddr),
    .io_trace_aw_bits_len(m_axi_trace_awlen),
    .io_trace_aw_bits_size(m_axi_trace_awsize),
    .io_trace_aw_bits_burst(m_axi_trace_awburst),
    .io_trace_aw_bits_lock(m_axi_trace_awlock),
    .io_trace_aw_bits_cache(m_axi_trace_awcache),
    .io_trace_aw_bits_prot(m_axi_trace_awprot),
    .io_trace_aw_bits_qos(m_axi_trace_awqos),
    .io_trace_w_ready(m_axi_trace_wready),
    .io_trace_w_valid(m_axi_trace_wvalid),
    .io_trace_w_bits_data(m_axi_trace_wdata),
    .io_trace_w_bits_strb(m_axi_trace_wstrb),
    .io_trace_w_bits_last(m_axi_trace_wlast),
    .io_trace_b_ready(m_axi_trace_bready),
    .io_trace_b_valid(m_axi_trace_bvalid),
    .io_trace_b_bits_id(m_axi_trace_bid),
    .io_trace_b_bits_resp(m_axi_trace_bresp),
    .io_trace_ar_ready(m_axi_trace_arready),
    .io_trace_ar_valid(m_axi_trace_arvalid),
    .io_trace_ar_bits_id(m_axi_trace_arid),
    .io_trace_ar_bits_addr(m_axi_trace_araddr),
    .io_trace_ar_bits_len(m_axi_trace_arlen),
    .io_trace_ar_bits_size(m_axi_trace_arsize),
    .io_trace_ar_bits_burst(m_axi_trace_arburst),
    .io_trace_ar_bits_lock(m_axi_trace_arlock),
    .io_trace_ar_bits_cache(m_axi_trace_arcache),
    .io_trace_ar_bits_prot(m_axi_trace_arprot),
    .io_trace_ar_bits_qos(m_axi_trace_arqos),
    .io_trace_r_ready(m_axi_trace_rready),
    .io_trace_r_valid(m_axi_trace_rvalid),
    .io_trace_r_bits_id(m_axi_trace_rid),
    .io_trace_r_bits_data(m_axi_trace_rdata),
    .io_trace_r_bits_resp(m_axi_trace_rresp),
    .io_trace_r_bits_last(m_axi_trace_rlast),
`endif
    .io_control_aw_ready(s_axi_control_awready),
    .io_control_aw_valid(s_axi_control_awvalid),
    .io_control_aw_bits_addr({20'b0, s_axi_control_awaddr}),
    .io_control_aw_bits_size(3'd2),
    .io_control_aw_bits_prot(3'd0),
    .io_control_w_ready(s_axi_control_wready),
    .io_control_w_valid(s_axi_control_wvalid),
    .io_control_w_bits_data(s_axi_control_wdata),
    .io_control_w_bits_strb(s_axi_control_wstrb),
    .io_control_b_ready(s_axi_control_bready),
    .io_control_b_valid(s_axi_control_bvalid),
    .io_control_b_bits_resp(s_axi_control_bresp),
    .io_control_ar_ready(s_axi_control_arready),
    .io_control_ar_valid(s_axi_control_arvalid),
    .io_control_ar_bits_addr({20'b0, s_axi_control_araddr}),
    .io_control_ar_bits_size(3'd2),
    .io_control_ar_bits_prot(3'd0),
    .io_control_r_ready(s_axi_control_rready),
    .io_control_r_valid(s_axi_control_rvalid),
    .io_control_r_bits_data(s_axi_control_rdata),
    .io_control_r_bits_resp(s_axi_control_rresp),
    .io_mailboxInterrupt(interrupt),
    .io_memoryHostBase(memory_host_base)
  );
endmodule

// The Vitis RTL-kernel interface always runs at the U55C platform DATA_CLK.
// Slower profiles create a derived core clock and cross each AXI channel with
// a bounded async FIFO.  AXI valid is held until the corresponding FIFO slot
// is accepted, preserving channel ordering without sampling any handshake in
// the wrong clock domain.
module npc_u55c_clocked_top #(
  parameter [31:0] GUEST_MEMORY_BASE = 32'h8000_0000,
  parameter integer PLATFORM_CLOCK_MHZ = 300,
  parameter integer CORE_CLOCK_MHZ = 300
) (
  input wire clock, input wire reset, input wire io_interrupt,
  input wire io_master_aw_ready, output wire io_master_aw_valid,
  output wire [3:0] io_master_aw_bits_id, output wire [63:0] io_master_aw_bits_addr,
  output wire [7:0] io_master_aw_bits_len, output wire [2:0] io_master_aw_bits_size,
  output wire [1:0] io_master_aw_bits_burst, output wire io_master_aw_bits_lock,
  output wire [3:0] io_master_aw_bits_cache, output wire [2:0] io_master_aw_bits_prot,
  output wire [3:0] io_master_aw_bits_qos,
  input wire io_master_w_ready, output wire io_master_w_valid,
  output wire [`NPC_FPGA_XLEN-1:0] io_master_w_bits_data,
  output wire [`NPC_FPGA_XLEN/8-1:0] io_master_w_bits_strb, output wire io_master_w_bits_last,
  output wire io_master_b_ready, input wire io_master_b_valid,
  input wire [3:0] io_master_b_bits_id, input wire [1:0] io_master_b_bits_resp,
  input wire io_master_ar_ready, output wire io_master_ar_valid,
  output wire [3:0] io_master_ar_bits_id, output wire [63:0] io_master_ar_bits_addr,
  output wire [7:0] io_master_ar_bits_len, output wire [2:0] io_master_ar_bits_size,
  output wire [1:0] io_master_ar_bits_burst, output wire io_master_ar_bits_lock,
  output wire [3:0] io_master_ar_bits_cache, output wire [2:0] io_master_ar_bits_prot,
  output wire [3:0] io_master_ar_bits_qos,
  output wire io_master_r_ready, input wire io_master_r_valid,
  input wire [3:0] io_master_r_bits_id, input wire [`NPC_FPGA_XLEN-1:0] io_master_r_bits_data,
  input wire [1:0] io_master_r_bits_resp, input wire io_master_r_bits_last,
`ifdef NPC_FPGA_RUNTIME_TRACE
  input wire io_trace_aw_ready, output wire io_trace_aw_valid,
  output wire [3:0] io_trace_aw_bits_id, output wire [63:0] io_trace_aw_bits_addr,
  output wire [7:0] io_trace_aw_bits_len, output wire [2:0] io_trace_aw_bits_size,
  output wire [1:0] io_trace_aw_bits_burst, output wire io_trace_aw_bits_lock,
  output wire [3:0] io_trace_aw_bits_cache, output wire [2:0] io_trace_aw_bits_prot,
  output wire [3:0] io_trace_aw_bits_qos,
  input wire io_trace_w_ready, output wire io_trace_w_valid,
  output wire [255:0] io_trace_w_bits_data, output wire [31:0] io_trace_w_bits_strb,
  output wire io_trace_w_bits_last, output wire io_trace_b_ready, input wire io_trace_b_valid,
  input wire [3:0] io_trace_b_bits_id, input wire [1:0] io_trace_b_bits_resp,
  input wire io_trace_ar_ready, output wire io_trace_ar_valid,
  output wire [3:0] io_trace_ar_bits_id, output wire [63:0] io_trace_ar_bits_addr,
  output wire [7:0] io_trace_ar_bits_len, output wire [2:0] io_trace_ar_bits_size,
  output wire [1:0] io_trace_ar_bits_burst, output wire io_trace_ar_bits_lock,
  output wire [3:0] io_trace_ar_bits_cache, output wire [2:0] io_trace_ar_bits_prot,
  output wire [3:0] io_trace_ar_bits_qos, output wire io_trace_r_ready,
  input wire io_trace_r_valid, input wire [3:0] io_trace_r_bits_id,
  input wire [255:0] io_trace_r_bits_data, input wire [1:0] io_trace_r_bits_resp,
  input wire io_trace_r_bits_last,
`endif
  output wire io_control_aw_ready, input wire io_control_aw_valid,
  input wire [31:0] io_control_aw_bits_addr, input wire [2:0] io_control_aw_bits_size,
  input wire [2:0] io_control_aw_bits_prot, output wire io_control_w_ready,
  input wire io_control_w_valid, input wire [31:0] io_control_w_bits_data,
  input wire [3:0] io_control_w_bits_strb, input wire io_control_b_ready,
  output wire io_control_b_valid, output wire [1:0] io_control_b_bits_resp,
  output wire io_control_ar_ready, input wire io_control_ar_valid,
  input wire [31:0] io_control_ar_bits_addr, input wire [2:0] io_control_ar_bits_size,
  input wire [2:0] io_control_ar_bits_prot, input wire io_control_r_ready,
  output wire io_control_r_valid, output wire [31:0] io_control_r_bits_data,
  output wire [1:0] io_control_r_bits_resp, output wire io_mailboxInterrupt,
  output wire [63:0] io_memoryHostBase
);
  localparam integer MMCM_MULTIPLY = (CORE_CLOCK_MHZ == 125 || CORE_CLOCK_MHZ == 250) ? 5 : 4;
  localparam integer MMCM_DIVIDE = (CORE_CLOCK_MHZ == 100 || CORE_CLOCK_MHZ == 125) ? 12 :
                                   (CORE_CLOCK_MHZ == 150) ? 8 : 6;
  wire core_clock, core_reset_n, fifo_reset, core_mailbox_interrupt;
  wire [63:0] core_memory_host_base;
  assign io_memoryHostBase = core_memory_host_base;
  assign fifo_reset = reset || !core_reset_n;

  generate
    if (CORE_CLOCK_MHZ == PLATFORM_CLOCK_MHZ) begin : g_native_clock
      assign core_clock = clock;
      assign core_reset_n = !reset;
      assign io_mailboxInterrupt = core_mailbox_interrupt;
    end else begin : g_slow_clock
      wire clkfb, clkfb_buf, clkout, locked;
      reg interrupt_meta, interrupt_sync;
      MMCME4_BASE #(.BANDWIDTH("OPTIMIZED"), .CLKFBOUT_MULT_F(MMCM_MULTIPLY),
        .CLKIN1_PERIOD(3.333), .CLKOUT0_DIVIDE_F(MMCM_DIVIDE), .CLKOUT0_DUTY_CYCLE(0.5),
        .CLKOUT0_PHASE(0.0), .DIVCLK_DIVIDE(1), .REF_JITTER1(0.010), .STARTUP_WAIT("FALSE")) mmcm (
        .CLKIN1(clock), .CLKFBIN(clkfb_buf), .RST(reset), .PWRDWN(1'b0),
        .CLKFBOUT(clkfb), .CLKOUT0(clkout), .LOCKED(locked));
      BUFG feedback_buffer (.I(clkfb), .O(clkfb_buf));
      BUFG core_clock_buffer (.I(clkout), .O(core_clock));
      npc_u55c_reset_sync reset_sync (.clock(core_clock), .async_reset_n(!reset && locked), .reset_n(core_reset_n));
      always @(posedge clock or posedge reset) begin
        if (reset) begin interrupt_meta <= 1'b0; interrupt_sync <= 1'b0; end
        else begin interrupt_meta <= core_mailbox_interrupt; interrupt_sync <= interrupt_meta; end
      end
      assign io_mailboxInterrupt = interrupt_sync;
    end
  endgenerate

  wire core_aw_ready, core_aw_valid; wire [3:0] core_aw_id; wire [31:0] core_aw_addr;
  wire [7:0] core_aw_len; wire [2:0] core_aw_size; wire [1:0] core_aw_burst; wire core_aw_lock;
  wire [3:0] core_aw_cache; wire [2:0] core_aw_prot; wire [3:0] core_aw_qos;
  wire core_w_ready, core_w_valid; wire [`NPC_FPGA_XLEN-1:0] core_w_data;
  wire [`NPC_FPGA_XLEN/8-1:0] core_w_strb; wire core_w_last;
  wire core_b_ready, core_b_valid; wire [3:0] core_b_id; wire [1:0] core_b_resp;
  wire core_ar_ready, core_ar_valid; wire [3:0] core_ar_id; wire [31:0] core_ar_addr;
  wire [7:0] core_ar_len; wire [2:0] core_ar_size; wire [1:0] core_ar_burst; wire core_ar_lock;
  wire [3:0] core_ar_cache; wire [2:0] core_ar_prot; wire [3:0] core_ar_qos;
  wire core_r_ready, core_r_valid; wire [3:0] core_r_id; wire [`NPC_FPGA_XLEN-1:0] core_r_data;
  wire [1:0] core_r_resp; wire core_r_last;
  wire [63:0] core_aw_host_addr = core_memory_host_base + {32'b0, core_aw_addr - GUEST_MEMORY_BASE};
  wire [63:0] core_ar_host_addr = core_memory_host_base + {32'b0, core_ar_addr - GUEST_MEMORY_BASE};

  wire [95:0] aw_dout, ar_dout; wire aw_full, aw_empty, ar_full, ar_empty;
  wire [`NPC_FPGA_XLEN + `NPC_FPGA_XLEN/8:0] w_dout; wire w_full, w_empty;
  wire [15:0] b_dout; wire b_full, b_empty;
  wire [`NPC_FPGA_XLEN + 6:0] r_dout; wire r_full, r_empty;
  npc_u55c_async_fifo #(.WIDTH(96)) aw_fifo (.wr_clk(core_clock), .rd_clk(clock), .rst(fifo_reset),
    .din({3'b0, core_aw_id, core_aw_host_addr, core_aw_len, core_aw_size, core_aw_burst, core_aw_lock, core_aw_cache, core_aw_prot, core_aw_qos}),
    .wr_en(core_aw_valid), .full(aw_full), .dout(aw_dout), .rd_en(io_master_aw_ready && !aw_empty), .empty(aw_empty));
  assign core_aw_ready = !aw_full; assign io_master_aw_valid = !aw_empty;
  assign {io_master_aw_bits_id, io_master_aw_bits_addr, io_master_aw_bits_len, io_master_aw_bits_size, io_master_aw_bits_burst, io_master_aw_bits_lock, io_master_aw_bits_cache, io_master_aw_bits_prot, io_master_aw_bits_qos} = aw_dout[92:0];
  npc_u55c_async_fifo #(.WIDTH(`NPC_FPGA_XLEN + `NPC_FPGA_XLEN/8 + 1)) w_fifo (.wr_clk(core_clock), .rd_clk(clock), .rst(fifo_reset),
    .din({core_w_data, core_w_strb, core_w_last}), .wr_en(core_w_valid), .full(w_full), .dout(w_dout), .rd_en(io_master_w_ready && !w_empty), .empty(w_empty));
  assign core_w_ready = !w_full; assign io_master_w_valid = !w_empty;
  assign {io_master_w_bits_data, io_master_w_bits_strb, io_master_w_bits_last} = w_dout;
  npc_u55c_async_fifo #(.WIDTH(16)) b_fifo (.wr_clk(clock), .rd_clk(core_clock), .rst(fifo_reset),
    .din({10'b0, io_master_b_bits_id, io_master_b_bits_resp}), .wr_en(io_master_b_valid), .full(b_full), .dout(b_dout), .rd_en(core_b_ready && !b_empty), .empty(b_empty));
  assign io_master_b_ready = !b_full; assign core_b_valid = !b_empty; assign {core_b_id, core_b_resp} = b_dout[5:0];
  npc_u55c_async_fifo #(.WIDTH(96)) ar_fifo (.wr_clk(core_clock), .rd_clk(clock), .rst(fifo_reset),
    .din({3'b0, core_ar_id, core_ar_host_addr, core_ar_len, core_ar_size, core_ar_burst, core_ar_lock, core_ar_cache, core_ar_prot, core_ar_qos}),
    .wr_en(core_ar_valid), .full(ar_full), .dout(ar_dout), .rd_en(io_master_ar_ready && !ar_empty), .empty(ar_empty));
  assign core_ar_ready = !ar_full; assign io_master_ar_valid = !ar_empty;
  assign {io_master_ar_bits_id, io_master_ar_bits_addr, io_master_ar_bits_len, io_master_ar_bits_size, io_master_ar_bits_burst, io_master_ar_bits_lock, io_master_ar_bits_cache, io_master_ar_bits_prot, io_master_ar_bits_qos} = ar_dout[92:0];
  npc_u55c_async_fifo #(.WIDTH(`NPC_FPGA_XLEN + 7)) r_fifo (.wr_clk(clock), .rd_clk(core_clock), .rst(fifo_reset),
    .din({io_master_r_bits_id, io_master_r_bits_data, io_master_r_bits_resp, io_master_r_bits_last}), .wr_en(io_master_r_valid), .full(r_full), .dout(r_dout), .rd_en(core_r_ready && !r_empty), .empty(r_empty));
  assign io_master_r_ready = !r_full; assign core_r_valid = !r_empty;
  assign {core_r_id, core_r_data, core_r_resp, core_r_last} = r_dout;

  wire core_caw_ready, core_caw_valid; wire [31:0] core_caw_addr;
  wire core_cw_ready, core_cw_valid; wire [31:0] core_cw_data; wire [3:0] core_cw_strb;
  wire core_cb_ready, core_cb_valid; wire [1:0] core_cb_resp;
  wire core_car_ready, core_car_valid; wire [31:0] core_car_addr;
  wire core_cr_ready, core_cr_valid; wire [31:0] core_cr_data; wire [1:0] core_cr_resp;
  wire [31:0] caw_dout; wire [35:0] cw_dout; wire caw_full, caw_empty, cw_full, cw_empty;
  wire [15:0] cb_dout, car_dout; wire cb_full, cb_empty, car_full, car_empty;
  wire [33:0] cr_dout; wire cr_full, cr_empty;
  npc_u55c_async_fifo #(.WIDTH(32)) caw_fifo (.wr_clk(clock), .rd_clk(core_clock), .rst(fifo_reset), .din(io_control_aw_bits_addr), .wr_en(io_control_aw_valid), .full(caw_full), .dout(caw_dout), .rd_en(core_caw_ready && !caw_empty), .empty(caw_empty));
  assign io_control_aw_ready = !caw_full; assign core_caw_valid = !caw_empty; assign core_caw_addr = caw_dout;
  npc_u55c_async_fifo #(.WIDTH(36)) cw_fifo (.wr_clk(clock), .rd_clk(core_clock), .rst(fifo_reset), .din({io_control_w_bits_data, io_control_w_bits_strb}), .wr_en(io_control_w_valid), .full(cw_full), .dout(cw_dout), .rd_en(core_cw_ready && !cw_empty), .empty(cw_empty));
  assign io_control_w_ready = !cw_full; assign core_cw_valid = !cw_empty; assign {core_cw_data, core_cw_strb} = cw_dout;
  npc_u55c_async_fifo #(.WIDTH(16)) cb_fifo (.wr_clk(core_clock), .rd_clk(clock), .rst(fifo_reset), .din({14'b0, core_cb_resp}), .wr_en(core_cb_valid), .full(cb_full), .dout(cb_dout), .rd_en(io_control_b_ready && !cb_empty), .empty(cb_empty));
  assign core_cb_ready = !cb_full; assign io_control_b_valid = !cb_empty; assign io_control_b_bits_resp = cb_dout[1:0];
  npc_u55c_async_fifo #(.WIDTH(16)) car_fifo (.wr_clk(clock), .rd_clk(core_clock), .rst(fifo_reset), .din({4'b0, io_control_ar_bits_addr[11:0]}), .wr_en(io_control_ar_valid), .full(car_full), .dout(car_dout), .rd_en(core_car_ready && !car_empty), .empty(car_empty));
  assign io_control_ar_ready = !car_full; assign core_car_valid = !car_empty; assign core_car_addr = {20'b0, car_dout[11:0]};
  npc_u55c_async_fifo #(.WIDTH(34)) cr_fifo (.wr_clk(core_clock), .rd_clk(clock), .rst(fifo_reset), .din({core_cr_data, core_cr_resp}), .wr_en(core_cr_valid), .full(cr_full), .dout(cr_dout), .rd_en(io_control_r_ready && !cr_empty), .empty(cr_empty));
  assign core_cr_ready = !cr_full; assign io_control_r_valid = !cr_empty; assign {io_control_r_bits_data, io_control_r_bits_resp} = cr_dout;

`ifdef NPC_FPGA_RUNTIME_TRACE
  wire core_taw_ready, core_taw_valid; wire [3:0] core_taw_id; wire [63:0] core_taw_addr; wire [7:0] core_taw_len; wire [2:0] core_taw_size; wire [1:0] core_taw_burst; wire core_taw_lock; wire [3:0] core_taw_cache; wire [2:0] core_taw_prot; wire [3:0] core_taw_qos;
  wire core_tw_ready, core_tw_valid; wire [255:0] core_tw_data; wire [31:0] core_tw_strb; wire core_tw_last;
  wire core_tb_ready, core_tb_valid; wire [3:0] core_tb_id; wire [1:0] core_tb_resp;
  wire core_tar_ready, core_tar_valid; wire [3:0] core_tar_id; wire [63:0] core_tar_addr; wire [7:0] core_tar_len; wire [2:0] core_tar_size; wire [1:0] core_tar_burst; wire core_tar_lock; wire [3:0] core_tar_cache; wire [2:0] core_tar_prot; wire [3:0] core_tar_qos;
  wire core_tr_ready, core_tr_valid; wire [3:0] core_tr_id; wire [255:0] core_tr_data; wire [1:0] core_tr_resp; wire core_tr_last;
  wire [95:0] taw_dout, tar_dout; wire taw_full, taw_empty, tar_full, tar_empty;
  wire [288:0] tw_dout; wire tw_full, tw_empty; wire [15:0] tb_dout; wire tb_full, tb_empty; wire [262:0] tr_dout; wire tr_full, tr_empty;
  npc_u55c_async_fifo #(.WIDTH(96)) taw_fifo (.wr_clk(core_clock), .rd_clk(clock), .rst(fifo_reset), .din({3'b0, core_taw_id, core_taw_addr, core_taw_len, core_taw_size, core_taw_burst, core_taw_lock, core_taw_cache, core_taw_prot, core_taw_qos}), .wr_en(core_taw_valid), .full(taw_full), .dout(taw_dout), .rd_en(io_trace_aw_ready && !taw_empty), .empty(taw_empty));
  assign core_taw_ready = !taw_full; assign io_trace_aw_valid = !taw_empty; assign {io_trace_aw_bits_id, io_trace_aw_bits_addr, io_trace_aw_bits_len, io_trace_aw_bits_size, io_trace_aw_bits_burst, io_trace_aw_bits_lock, io_trace_aw_bits_cache, io_trace_aw_bits_prot, io_trace_aw_bits_qos} = taw_dout[92:0];
  npc_u55c_async_fifo #(.WIDTH(289)) tw_fifo (.wr_clk(core_clock), .rd_clk(clock), .rst(fifo_reset), .din({core_tw_data, core_tw_strb, core_tw_last}), .wr_en(core_tw_valid), .full(tw_full), .dout(tw_dout), .rd_en(io_trace_w_ready && !tw_empty), .empty(tw_empty));
  assign core_tw_ready = !tw_full; assign io_trace_w_valid = !tw_empty; assign {io_trace_w_bits_data, io_trace_w_bits_strb, io_trace_w_bits_last} = tw_dout;
  npc_u55c_async_fifo #(.WIDTH(16)) tb_fifo (.wr_clk(clock), .rd_clk(core_clock), .rst(fifo_reset), .din({10'b0, io_trace_b_bits_id, io_trace_b_bits_resp}), .wr_en(io_trace_b_valid), .full(tb_full), .dout(tb_dout), .rd_en(core_tb_ready && !tb_empty), .empty(tb_empty));
  assign io_trace_b_ready = !tb_full; assign core_tb_valid = !tb_empty; assign {core_tb_id, core_tb_resp} = tb_dout[5:0];
  npc_u55c_async_fifo #(.WIDTH(96)) tar_fifo (.wr_clk(core_clock), .rd_clk(clock), .rst(fifo_reset), .din({3'b0, core_tar_id, core_tar_addr, core_tar_len, core_tar_size, core_tar_burst, core_tar_lock, core_tar_cache, core_tar_prot, core_tar_qos}), .wr_en(core_tar_valid), .full(tar_full), .dout(tar_dout), .rd_en(io_trace_ar_ready && !tar_empty), .empty(tar_empty));
  assign core_tar_ready = !tar_full; assign io_trace_ar_valid = !tar_empty; assign {io_trace_ar_bits_id, io_trace_ar_bits_addr, io_trace_ar_bits_len, io_trace_ar_bits_size, io_trace_ar_bits_burst, io_trace_ar_bits_lock, io_trace_ar_bits_cache, io_trace_ar_bits_prot, io_trace_ar_bits_qos} = tar_dout[92:0];
  npc_u55c_async_fifo #(.WIDTH(263)) tr_fifo (.wr_clk(clock), .rd_clk(core_clock), .rst(fifo_reset), .din({io_trace_r_bits_id, io_trace_r_bits_data, io_trace_r_bits_resp, io_trace_r_bits_last}), .wr_en(io_trace_r_valid), .full(tr_full), .dout(tr_dout), .rd_en(core_tr_ready && !tr_empty), .empty(tr_empty));
  assign io_trace_r_ready = !tr_full; assign core_tr_valid = !tr_empty; assign {core_tr_id, core_tr_data, core_tr_resp, core_tr_last} = tr_dout;
`endif

  NpcFpgaTop inner (
    .clock(core_clock), .reset(!core_reset_n), .io_interrupt(io_interrupt),
    .io_master_aw_ready(core_aw_ready), .io_master_aw_valid(core_aw_valid), .io_master_aw_bits_id(core_aw_id), .io_master_aw_bits_addr(core_aw_addr), .io_master_aw_bits_len(core_aw_len), .io_master_aw_bits_size(core_aw_size), .io_master_aw_bits_burst(core_aw_burst), .io_master_aw_bits_lock(core_aw_lock), .io_master_aw_bits_cache(core_aw_cache), .io_master_aw_bits_prot(core_aw_prot), .io_master_aw_bits_qos(core_aw_qos),
    .io_master_w_ready(core_w_ready), .io_master_w_valid(core_w_valid), .io_master_w_bits_data(core_w_data), .io_master_w_bits_strb(core_w_strb), .io_master_w_bits_last(core_w_last), .io_master_b_ready(core_b_ready), .io_master_b_valid(core_b_valid), .io_master_b_bits_id(core_b_id), .io_master_b_bits_resp(core_b_resp),
    .io_master_ar_ready(core_ar_ready), .io_master_ar_valid(core_ar_valid), .io_master_ar_bits_id(core_ar_id), .io_master_ar_bits_addr(core_ar_addr), .io_master_ar_bits_len(core_ar_len), .io_master_ar_bits_size(core_ar_size), .io_master_ar_bits_burst(core_ar_burst), .io_master_ar_bits_lock(core_ar_lock), .io_master_ar_bits_cache(core_ar_cache), .io_master_ar_bits_prot(core_ar_prot), .io_master_ar_bits_qos(core_ar_qos),
    .io_master_r_ready(core_r_ready), .io_master_r_valid(core_r_valid), .io_master_r_bits_id(core_r_id), .io_master_r_bits_data(core_r_data), .io_master_r_bits_resp(core_r_resp), .io_master_r_bits_last(core_r_last),
`ifdef NPC_FPGA_RUNTIME_TRACE
    .io_trace_aw_ready(core_taw_ready), .io_trace_aw_valid(core_taw_valid), .io_trace_aw_bits_id(core_taw_id), .io_trace_aw_bits_addr(core_taw_addr), .io_trace_aw_bits_len(core_taw_len), .io_trace_aw_bits_size(core_taw_size), .io_trace_aw_bits_burst(core_taw_burst), .io_trace_aw_bits_lock(core_taw_lock), .io_trace_aw_bits_cache(core_taw_cache), .io_trace_aw_bits_prot(core_taw_prot), .io_trace_aw_bits_qos(core_taw_qos),
    .io_trace_w_ready(core_tw_ready), .io_trace_w_valid(core_tw_valid), .io_trace_w_bits_data(core_tw_data), .io_trace_w_bits_strb(core_tw_strb), .io_trace_w_bits_last(core_tw_last), .io_trace_b_ready(core_tb_ready), .io_trace_b_valid(core_tb_valid), .io_trace_b_bits_id(core_tb_id), .io_trace_b_bits_resp(core_tb_resp),
    .io_trace_ar_ready(core_tar_ready), .io_trace_ar_valid(core_tar_valid), .io_trace_ar_bits_id(core_tar_id), .io_trace_ar_bits_addr(core_tar_addr), .io_trace_ar_bits_len(core_tar_len), .io_trace_ar_bits_size(core_tar_size), .io_trace_ar_bits_burst(core_tar_burst), .io_trace_ar_bits_lock(core_tar_lock), .io_trace_ar_bits_cache(core_tar_cache), .io_trace_ar_bits_prot(core_tar_prot), .io_trace_ar_bits_qos(core_tar_qos),
    .io_trace_r_ready(core_tr_ready), .io_trace_r_valid(core_tr_valid), .io_trace_r_bits_id(core_tr_id), .io_trace_r_bits_data(core_tr_data), .io_trace_r_bits_resp(core_tr_resp), .io_trace_r_bits_last(core_tr_last),
`endif
    .io_control_aw_ready(core_caw_ready), .io_control_aw_valid(core_caw_valid), .io_control_aw_bits_addr(core_caw_addr), .io_control_aw_bits_size(3'd2), .io_control_aw_bits_prot(3'd0),
    .io_control_w_ready(core_cw_ready), .io_control_w_valid(core_cw_valid), .io_control_w_bits_data(core_cw_data), .io_control_w_bits_strb(core_cw_strb), .io_control_b_ready(core_cb_ready), .io_control_b_valid(core_cb_valid), .io_control_b_bits_resp(core_cb_resp),
    .io_control_ar_ready(core_car_ready), .io_control_ar_valid(core_car_valid), .io_control_ar_bits_addr(core_car_addr), .io_control_ar_bits_size(3'd2), .io_control_ar_bits_prot(3'd0), .io_control_r_ready(core_cr_ready), .io_control_r_valid(core_cr_valid), .io_control_r_bits_data(core_cr_data), .io_control_r_bits_resp(core_cr_resp),
    .io_mailboxInterrupt(core_mailbox_interrupt), .io_memoryHostBase(core_memory_host_base)
  );
endmodule

module npc_u55c_async_fifo #(parameter integer WIDTH = 16, parameter integer DEPTH = 16) (
  input wire wr_clk, input wire rd_clk, input wire rst, input wire [WIDTH-1:0] din,
  input wire wr_en, output wire full, output wire [WIDTH-1:0] dout, input wire rd_en, output wire empty
);
  xpm_fifo_async #(.CDC_SYNC_STAGES(2), .DOUT_RESET_VALUE("0"), .ECC_MODE("no_ecc"),
    .FIFO_MEMORY_TYPE("auto"), .FIFO_READ_LATENCY(0), .FIFO_WRITE_DEPTH(DEPTH),
    .FULL_RESET_VALUE(0), .PROG_EMPTY_THRESH(10), .PROG_FULL_THRESH(10),
    .RD_DATA_COUNT_WIDTH(1), .READ_DATA_WIDTH(WIDTH), .READ_MODE("fwft"),
    .RELATED_CLOCKS(0), .SIM_ASSERT_CHK(0), .USE_ADV_FEATURES("0000"),
    .WAKEUP_TIME(0), .WRITE_DATA_WIDTH(WIDTH), .WR_DATA_COUNT_WIDTH(1)) impl (
    .rst(rst), .wr_clk(wr_clk), .wr_en(wr_en), .din(din), .full(full), .wr_ack(),
    .overflow(), .prog_full(), .wr_data_count(), .almost_full(), .wr_rst_busy(),
    .rd_clk(rd_clk), .rd_en(rd_en), .dout(dout), .empty(empty), .underflow(),
    .prog_empty(), .rd_data_count(), .almost_empty(), .data_valid(), .rd_rst_busy(),
    .sleep(1'b0), .injectsbiterr(1'b0), .injectdbiterr(1'b0), .sbiterr(), .dbiterr()
  );
endmodule

module npc_u55c_reset_sync (input wire clock, input wire async_reset_n, output wire reset_n);
  reg [1:0] reset_pipe;
  always @(posedge clock or negedge async_reset_n) begin
    if (!async_reset_n) reset_pipe <= 2'b00;
    else reset_pipe <= {reset_pipe[0], 1'b1};
  end
  assign reset_n = reset_pipe[1];
endmodule
