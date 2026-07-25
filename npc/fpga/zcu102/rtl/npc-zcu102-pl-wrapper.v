`ifndef NPC_FPGA_XLEN
`define NPC_FPGA_XLEN 32
`endif

module NpcZcu102Pl #(
  parameter [31:0] GUEST_MEMORY_BASE = 32'h8000_0000,
  parameter [63:0] HOST_MEMORY_BASE = 64'h0000_0000_7000_0000
) (
  (* X_INTERFACE_INFO = "xilinx.com:signal:clock:1.0 ap_clk CLK",
     X_INTERFACE_PARAMETER = "XIL_INTERFACENAME ap_clk, ASSOCIATED_BUSIF M_AXI_MEMORY:S_AXI_CONTROL, ASSOCIATED_RESET ap_rst_n" *)
  input  wire                         ap_clk,
  (* X_INTERFACE_INFO = "xilinx.com:signal:reset:1.0 ap_rst_n RST",
     X_INTERFACE_PARAMETER = "XIL_INTERFACENAME ap_rst_n, POLARITY ACTIVE_LOW" *)
  input  wire                         ap_rst_n,
  (* X_INTERFACE_INFO = "xilinx.com:signal:interrupt:1.0 interrupt INTERRUPT",
     X_INTERFACE_PARAMETER = "XIL_INTERFACENAME interrupt, SENSITIVITY LEVEL_HIGH" *)
  output wire                         interrupt,

  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY AWVALID" *)
  output wire                         m_axi_memory_awvalid,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY AWREADY" *)
  input  wire                         m_axi_memory_awready,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY AWADDR" *)
  output wire [63:0]                  m_axi_memory_awaddr,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY AWID" *)
  output wire [3:0]                   m_axi_memory_awid,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY AWLEN" *)
  output wire [7:0]                   m_axi_memory_awlen,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY AWSIZE" *)
  output wire [2:0]                   m_axi_memory_awsize,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY AWBURST" *)
  output wire [1:0]                   m_axi_memory_awburst,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY AWLOCK" *)
  output wire                         m_axi_memory_awlock,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY AWCACHE" *)
  output wire [3:0]                   m_axi_memory_awcache,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY AWPROT" *)
  output wire [2:0]                   m_axi_memory_awprot,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY AWQOS" *)
  output wire [3:0]                   m_axi_memory_awqos,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY WVALID" *)
  output wire                         m_axi_memory_wvalid,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY WREADY" *)
  input  wire                         m_axi_memory_wready,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY WDATA" *)
  output wire [`NPC_FPGA_XLEN-1:0]   m_axi_memory_wdata,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY WSTRB" *)
  output wire [`NPC_FPGA_XLEN/8-1:0] m_axi_memory_wstrb,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY WLAST" *)
  output wire                         m_axi_memory_wlast,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY BVALID" *)
  input  wire                         m_axi_memory_bvalid,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY BREADY" *)
  output wire                         m_axi_memory_bready,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY BID" *)
  input  wire [3:0]                   m_axi_memory_bid,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY BRESP" *)
  input  wire [1:0]                   m_axi_memory_bresp,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY ARVALID" *)
  output wire                         m_axi_memory_arvalid,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY ARREADY" *)
  input  wire                         m_axi_memory_arready,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY ARADDR" *)
  output wire [63:0]                  m_axi_memory_araddr,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY ARID" *)
  output wire [3:0]                   m_axi_memory_arid,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY ARLEN" *)
  output wire [7:0]                   m_axi_memory_arlen,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY ARSIZE" *)
  output wire [2:0]                   m_axi_memory_arsize,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY ARBURST" *)
  output wire [1:0]                   m_axi_memory_arburst,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY ARLOCK" *)
  output wire                         m_axi_memory_arlock,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY ARCACHE" *)
  output wire [3:0]                   m_axi_memory_arcache,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY ARPROT" *)
  output wire [2:0]                   m_axi_memory_arprot,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY ARQOS" *)
  output wire [3:0]                   m_axi_memory_arqos,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY RVALID" *)
  input  wire                         m_axi_memory_rvalid,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY RREADY",
     X_INTERFACE_PARAMETER = "XIL_INTERFACENAME M_AXI_MEMORY, PROTOCOL AXI4, ADDR_WIDTH 64, DATA_WIDTH 32, ID_WIDTH 4, READ_WRITE_MODE READ_WRITE, HAS_BURST 1, HAS_LOCK 1, HAS_PROT 1, HAS_CACHE 1, HAS_QOS 1, HAS_WSTRB 1, HAS_BRESP 1, HAS_RRESP 1, SUPPORTS_NARROW_BURST 1, MAX_BURST_LENGTH 256, NUM_READ_OUTSTANDING 2, NUM_WRITE_OUTSTANDING 2" *)
  output wire                         m_axi_memory_rready,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY RID" *)
  input  wire [3:0]                   m_axi_memory_rid,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY RDATA" *)
  input  wire [`NPC_FPGA_XLEN-1:0]   m_axi_memory_rdata,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY RRESP" *)
  input  wire [1:0]                   m_axi_memory_rresp,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI_MEMORY RLAST" *)
  input  wire                         m_axi_memory_rlast,

  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI_CONTROL AWVALID" *)
  input  wire                         s_axi_control_awvalid,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI_CONTROL AWREADY" *)
  output wire                         s_axi_control_awready,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI_CONTROL AWADDR" *)
  input  wire [31:0]                  s_axi_control_awaddr,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI_CONTROL AWPROT" *)
  input  wire [2:0]                   s_axi_control_awprot,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI_CONTROL WVALID" *)
  input  wire                         s_axi_control_wvalid,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI_CONTROL WREADY" *)
  output wire                         s_axi_control_wready,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI_CONTROL WDATA" *)
  input  wire [31:0]                  s_axi_control_wdata,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI_CONTROL WSTRB" *)
  input  wire [3:0]                   s_axi_control_wstrb,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI_CONTROL BVALID" *)
  output wire                         s_axi_control_bvalid,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI_CONTROL BREADY" *)
  input  wire                         s_axi_control_bready,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI_CONTROL BRESP" *)
  output wire [1:0]                   s_axi_control_bresp,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI_CONTROL ARVALID" *)
  input  wire                         s_axi_control_arvalid,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI_CONTROL ARREADY" *)
  output wire                         s_axi_control_arready,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI_CONTROL ARADDR" *)
  input  wire [31:0]                  s_axi_control_araddr,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI_CONTROL ARPROT" *)
  input  wire [2:0]                   s_axi_control_arprot,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI_CONTROL RVALID" *)
  output wire                         s_axi_control_rvalid,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI_CONTROL RREADY",
     X_INTERFACE_PARAMETER = "XIL_INTERFACENAME S_AXI_CONTROL, PROTOCOL AXI4LITE, ADDR_WIDTH 32, DATA_WIDTH 32, READ_WRITE_MODE READ_WRITE, HAS_PROT 1, HAS_WSTRB 1, HAS_BRESP 1, HAS_RRESP 1" *)
  input  wire                         s_axi_control_rready,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI_CONTROL RDATA" *)
  output wire [31:0]                  s_axi_control_rdata,
  (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI_CONTROL RRESP" *)
  output wire [1:0]                   s_axi_control_rresp
);
  wire [31:0] core_awaddr;
  wire [31:0] core_araddr;
  wire [63:0] unused_memory_host_base;

  assign m_axi_memory_awaddr = HOST_MEMORY_BASE + {32'b0, core_awaddr - GUEST_MEMORY_BASE};
  assign m_axi_memory_araddr = HOST_MEMORY_BASE + {32'b0, core_araddr - GUEST_MEMORY_BASE};

  NpcFpgaTop core (
    .clock(ap_clk),
    .reset(!ap_rst_n),
    .io_interrupt(1'b0),
    .io_master_aw_ready(m_axi_memory_awready),
    .io_master_aw_valid(m_axi_memory_awvalid),
    .io_master_aw_bits_id(m_axi_memory_awid),
    .io_master_aw_bits_addr(core_awaddr),
    .io_master_aw_bits_len(m_axi_memory_awlen),
    .io_master_aw_bits_size(m_axi_memory_awsize),
    .io_master_aw_bits_burst(m_axi_memory_awburst),
    .io_master_aw_bits_lock(m_axi_memory_awlock),
    .io_master_aw_bits_cache(m_axi_memory_awcache),
    .io_master_aw_bits_prot(m_axi_memory_awprot),
    .io_master_aw_bits_qos(m_axi_memory_awqos),
    .io_master_w_ready(m_axi_memory_wready),
    .io_master_w_valid(m_axi_memory_wvalid),
    .io_master_w_bits_data(m_axi_memory_wdata),
    .io_master_w_bits_strb(m_axi_memory_wstrb),
    .io_master_w_bits_last(m_axi_memory_wlast),
    .io_master_b_ready(m_axi_memory_bready),
    .io_master_b_valid(m_axi_memory_bvalid),
    .io_master_b_bits_id(m_axi_memory_bid),
    .io_master_b_bits_resp(m_axi_memory_bresp),
    .io_master_ar_ready(m_axi_memory_arready),
    .io_master_ar_valid(m_axi_memory_arvalid),
    .io_master_ar_bits_id(m_axi_memory_arid),
    .io_master_ar_bits_addr(core_araddr),
    .io_master_ar_bits_len(m_axi_memory_arlen),
    .io_master_ar_bits_size(m_axi_memory_arsize),
    .io_master_ar_bits_burst(m_axi_memory_arburst),
    .io_master_ar_bits_lock(m_axi_memory_arlock),
    .io_master_ar_bits_cache(m_axi_memory_arcache),
    .io_master_ar_bits_prot(m_axi_memory_arprot),
    .io_master_ar_bits_qos(m_axi_memory_arqos),
    .io_master_r_ready(m_axi_memory_rready),
    .io_master_r_valid(m_axi_memory_rvalid),
    .io_master_r_bits_id(m_axi_memory_rid),
    .io_master_r_bits_data(m_axi_memory_rdata),
    .io_master_r_bits_resp(m_axi_memory_rresp),
    .io_master_r_bits_last(m_axi_memory_rlast),
    .io_control_aw_ready(s_axi_control_awready),
    .io_control_aw_valid(s_axi_control_awvalid),
    .io_control_aw_bits_addr(s_axi_control_awaddr),
    .io_control_aw_bits_size(3'd2),
    .io_control_aw_bits_prot(s_axi_control_awprot),
    .io_control_w_ready(s_axi_control_wready),
    .io_control_w_valid(s_axi_control_wvalid),
    .io_control_w_bits_data(s_axi_control_wdata),
    .io_control_w_bits_strb(s_axi_control_wstrb),
    .io_control_b_ready(s_axi_control_bready),
    .io_control_b_valid(s_axi_control_bvalid),
    .io_control_b_bits_resp(s_axi_control_bresp),
    .io_control_ar_ready(s_axi_control_arready),
    .io_control_ar_valid(s_axi_control_arvalid),
    .io_control_ar_bits_addr(s_axi_control_araddr),
    .io_control_ar_bits_size(3'd2),
    .io_control_ar_bits_prot(s_axi_control_arprot),
    .io_control_r_ready(s_axi_control_rready),
    .io_control_r_valid(s_axi_control_rvalid),
    .io_control_r_bits_data(s_axi_control_rdata),
    .io_control_r_bits_resp(s_axi_control_rresp),
    .io_mailboxInterrupt(interrupt),
    .io_memoryHostBase(unused_memory_host_base)
  );
endmodule
