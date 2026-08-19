`ifndef SPMV_CUPERFLOW_PLATFORM_CLOCK_MHZ
`define SPMV_CUPERFLOW_PLATFORM_CLOCK_MHZ 300
`endif

`ifndef SPMV_CUPERFLOW_CORE_CLOCK_MHZ
`define SPMV_CUPERFLOW_CORE_CLOCK_MHZ `SPMV_CUPERFLOW_PLATFORM_CLOCK_MHZ
`endif

`define SPMV_CUPERFLOW_AXI_MASTER_PORT(NAME) \
  output wire         m_axi_``NAME``_awvalid, input wire m_axi_``NAME``_awready, \
  output wire [63:0]  m_axi_``NAME``_awaddr, output wire [3:0] m_axi_``NAME``_awid, \
  output wire [7:0]   m_axi_``NAME``_awlen, output wire [2:0] m_axi_``NAME``_awsize, \
  output wire [1:0]   m_axi_``NAME``_awburst, output wire m_axi_``NAME``_awlock, \
  output wire [3:0]   m_axi_``NAME``_awcache, output wire [2:0] m_axi_``NAME``_awprot, \
  output wire [3:0]   m_axi_``NAME``_awqos, output wire m_axi_``NAME``_wvalid, \
  input wire m_axi_``NAME``_wready, output wire [511:0] m_axi_``NAME``_wdata, \
  output wire [63:0]  m_axi_``NAME``_wstrb, output wire m_axi_``NAME``_wlast, \
  input wire m_axi_``NAME``_bvalid, output wire m_axi_``NAME``_bready, \
  input wire [3:0] m_axi_``NAME``_bid, input wire [1:0] m_axi_``NAME``_bresp, \
  output wire m_axi_``NAME``_arvalid, input wire m_axi_``NAME``_arready, \
  output wire [63:0]  m_axi_``NAME``_araddr, output wire [3:0] m_axi_``NAME``_arid, \
  output wire [7:0]   m_axi_``NAME``_arlen, output wire [2:0] m_axi_``NAME``_arsize, \
  output wire [1:0]   m_axi_``NAME``_arburst, output wire m_axi_``NAME``_arlock, \
  output wire [3:0]   m_axi_``NAME``_arcache, output wire [2:0] m_axi_``NAME``_arprot, \
  output wire [3:0]   m_axi_``NAME``_arqos, input wire m_axi_``NAME``_rvalid, \
  output wire m_axi_``NAME``_rready, input wire [3:0] m_axi_``NAME``_rid, \
  input wire [511:0] m_axi_``NAME``_rdata, input wire [1:0] m_axi_``NAME``_rresp, \
  input wire m_axi_``NAME``_rlast

`define SPMV_CUPERFLOW_AXI_WIRES(INDEX) \
  wire core_axi_``INDEX``_ar_valid, core_axi_``INDEX``_ar_ready; \
  wire [63:0] core_axi_``INDEX``_ar_addr; wire [3:0] core_axi_``INDEX``_ar_id; \
  wire [7:0] core_axi_``INDEX``_ar_len; wire [2:0] core_axi_``INDEX``_ar_size; \
  wire [1:0] core_axi_``INDEX``_ar_burst; wire core_axi_``INDEX``_ar_lock; \
  wire [3:0] core_axi_``INDEX``_ar_cache; wire [2:0] core_axi_``INDEX``_ar_prot; \
  wire [3:0] core_axi_``INDEX``_ar_qos; wire core_axi_``INDEX``_r_valid; \
  wire core_axi_``INDEX``_r_ready; wire [3:0] core_axi_``INDEX``_r_id; \
  wire [511:0] core_axi_``INDEX``_r_data; wire [1:0] core_axi_``INDEX``_r_resp; \
  wire core_axi_``INDEX``_r_last;

`define SPMV_CUPERFLOW_AXI_BIND(NAME, INDEX) \
  assign m_axi_``NAME``_awvalid = 1'b0; assign m_axi_``NAME``_awaddr = 64'b0; \
  assign m_axi_``NAME``_awid = 4'b0; assign m_axi_``NAME``_awlen = 8'b0; \
  assign m_axi_``NAME``_awsize = 3'd6; assign m_axi_``NAME``_awburst = 2'b01; \
  assign m_axi_``NAME``_awlock = 1'b0; assign m_axi_``NAME``_awcache = 4'b0; \
  assign m_axi_``NAME``_awprot = 3'b0; assign m_axi_``NAME``_awqos = 4'b0; \
  assign m_axi_``NAME``_wvalid = 1'b0; assign m_axi_``NAME``_wdata = 512'b0; \
  assign m_axi_``NAME``_wstrb = 64'b0; assign m_axi_``NAME``_wlast = 1'b1; \
  assign m_axi_``NAME``_bready = 1'b1; \
  u55c_axi4_read_cdc #(.SAME_CLOCK(CORE_SAME_CLOCK)) read_cdc_``INDEX`` ( \
    .core_clock(core_clock), .shell_clock(ap_clk), .reset(fifo_reset), \
    .core_ar_valid(core_axi_``INDEX``_ar_valid), .core_ar_ready(core_axi_``INDEX``_ar_ready), \
    .core_ar_id(core_axi_``INDEX``_ar_id), \
    .core_ar_addr(core_axi_``INDEX``_ar_addr + hbm_base[INDEX]), \
    .core_ar_len(core_axi_``INDEX``_ar_len), .core_ar_size(core_axi_``INDEX``_ar_size), \
    .core_ar_burst(core_axi_``INDEX``_ar_burst), .core_ar_lock(core_axi_``INDEX``_ar_lock), \
    .core_ar_cache(core_axi_``INDEX``_ar_cache), .core_ar_prot(core_axi_``INDEX``_ar_prot), \
    .core_ar_qos(core_axi_``INDEX``_ar_qos), .core_r_valid(core_axi_``INDEX``_r_valid), \
    .core_r_ready(core_axi_``INDEX``_r_ready), .core_r_id(core_axi_``INDEX``_r_id), \
    .core_r_data(core_axi_``INDEX``_r_data), .core_r_resp(core_axi_``INDEX``_r_resp), \
    .core_r_last(core_axi_``INDEX``_r_last), .shell_ar_valid(m_axi_``NAME``_arvalid), \
    .shell_ar_ready(m_axi_``NAME``_arready), .shell_ar_id(m_axi_``NAME``_arid), \
    .shell_ar_addr(m_axi_``NAME``_araddr), .shell_ar_len(m_axi_``NAME``_arlen), \
    .shell_ar_size(m_axi_``NAME``_arsize), .shell_ar_burst(m_axi_``NAME``_arburst), \
    .shell_ar_lock(m_axi_``NAME``_arlock), .shell_ar_cache(m_axi_``NAME``_arcache), \
    .shell_ar_prot(m_axi_``NAME``_arprot), .shell_ar_qos(m_axi_``NAME``_arqos), \
    .shell_r_valid(m_axi_``NAME``_rvalid), .shell_r_ready(m_axi_``NAME``_rready), \
    .shell_r_id(m_axi_``NAME``_rid), .shell_r_data(m_axi_``NAME``_rdata), \
    .shell_r_resp(m_axi_``NAME``_rresp), .shell_r_last(m_axi_``NAME``_rlast) \
  );

`define SPMV_CUPERFLOW_CORE_BIND(INDEX) \
    .io_hbm_``INDEX``_ar_ready(core_axi_``INDEX``_ar_ready), \
    .io_hbm_``INDEX``_ar_valid(core_axi_``INDEX``_ar_valid), \
    .io_hbm_``INDEX``_ar_bits_id(core_axi_``INDEX``_ar_id), \
    .io_hbm_``INDEX``_ar_bits_addr(core_axi_``INDEX``_ar_addr), \
    .io_hbm_``INDEX``_ar_bits_len(core_axi_``INDEX``_ar_len), \
    .io_hbm_``INDEX``_ar_bits_size(core_axi_``INDEX``_ar_size), \
    .io_hbm_``INDEX``_ar_bits_burst(core_axi_``INDEX``_ar_burst), \
    .io_hbm_``INDEX``_ar_bits_lock(core_axi_``INDEX``_ar_lock), \
    .io_hbm_``INDEX``_ar_bits_cache(core_axi_``INDEX``_ar_cache), \
    .io_hbm_``INDEX``_ar_bits_prot(core_axi_``INDEX``_ar_prot), \
    .io_hbm_``INDEX``_ar_bits_qos(core_axi_``INDEX``_ar_qos), \
    .io_hbm_``INDEX``_r_ready(core_axi_``INDEX``_r_ready), \
    .io_hbm_``INDEX``_r_valid(core_axi_``INDEX``_r_valid), \
    .io_hbm_``INDEX``_r_bits_id(core_axi_``INDEX``_r_id), \
    .io_hbm_``INDEX``_r_bits_data(core_axi_``INDEX``_r_data), \
    .io_hbm_``INDEX``_r_bits_resp(core_axi_``INDEX``_r_resp), \
    .io_hbm_``INDEX``_r_bits_last(core_axi_``INDEX``_r_last)

module SpmvCuperflowKernel (
  input wire ap_clk, input wire ap_rst_n, output wire interrupt,
  `SPMV_CUPERFLOW_AXI_MASTER_PORT(pc00), `SPMV_CUPERFLOW_AXI_MASTER_PORT(pc01),
  `SPMV_CUPERFLOW_AXI_MASTER_PORT(pc02), `SPMV_CUPERFLOW_AXI_MASTER_PORT(pc03),
  `SPMV_CUPERFLOW_AXI_MASTER_PORT(pc04), `SPMV_CUPERFLOW_AXI_MASTER_PORT(pc05),
  `SPMV_CUPERFLOW_AXI_MASTER_PORT(pc06), `SPMV_CUPERFLOW_AXI_MASTER_PORT(pc07),
  `SPMV_CUPERFLOW_AXI_MASTER_PORT(pc08), `SPMV_CUPERFLOW_AXI_MASTER_PORT(pc09),
  `SPMV_CUPERFLOW_AXI_MASTER_PORT(pc10), `SPMV_CUPERFLOW_AXI_MASTER_PORT(pc11),
  `SPMV_CUPERFLOW_AXI_MASTER_PORT(pc12), `SPMV_CUPERFLOW_AXI_MASTER_PORT(pc13),
  `SPMV_CUPERFLOW_AXI_MASTER_PORT(pc14), `SPMV_CUPERFLOW_AXI_MASTER_PORT(pc15),
  input wire s_axi_control_awvalid, output wire s_axi_control_awready,
  input wire [11:0] s_axi_control_awaddr, input wire s_axi_control_wvalid,
  output wire s_axi_control_wready, input wire [31:0] s_axi_control_wdata,
  input wire [3:0] s_axi_control_wstrb, output wire s_axi_control_bvalid,
  input wire s_axi_control_bready, output wire [1:0] s_axi_control_bresp,
  input wire s_axi_control_arvalid, output wire s_axi_control_arready,
  input wire [11:0] s_axi_control_araddr, output wire s_axi_control_rvalid,
  input wire s_axi_control_rready, output wire [31:0] s_axi_control_rdata,
  output wire [1:0] s_axi_control_rresp
);
  reg [63:0] hbm_base [0:15];
  reg aw_pending, w_pending, write_response_valid, read_response_valid;
  reg [11:0] aw_address; reg [31:0] w_data, read_response_data; reg [3:0] w_strobe;
  reg ap_start, ap_done, ap_ready, active, auto_restart;
  reg global_interrupt_enable; reg [1:0] interrupt_enable, interrupt_status;
  reg [63:0] completed_checksum; reg completed_error; integer index;

  localparam integer CORE_SAME_CLOCK =
    (`SPMV_CUPERFLOW_CORE_CLOCK_MHZ == `SPMV_CUPERFLOW_PLATFORM_CLOCK_MHZ);
  wire core_clock, core_reset_n;
  wire fifo_reset = !ap_rst_n || !core_reset_n;
  u55c_core_clock #(.PLATFORM_CLOCK_MHZ(`SPMV_CUPERFLOW_PLATFORM_CLOCK_MHZ),
    .CORE_CLOCK_MHZ(`SPMV_CUPERFLOW_CORE_CLOCK_MHZ)) core_clocking (
      .platform_clock(ap_clk), .platform_reset(!ap_rst_n),
      .core_clock(core_clock), .core_reset_n(core_reset_n));

  wire start_pulse = ap_start && !active;
  reg start_toggle, core_start_seen;
  wire core_start_toggle;
  u55c_cdc_bus #(.WIDTH(1), .SAME_CLOCK(CORE_SAME_CLOCK)) start_cdc (
    .src_clock(ap_clk), .dst_clock(core_clock), .reset(fifo_reset),
    .src_bits(start_toggle), .dst_bits(core_start_toggle));
  wire core_start = core_start_toggle ^ core_start_seen;

  wire core_done, core_error;
  wire [63:0] core_checksum;
  wire [65:0] shell_status;
  u55c_cdc_bus #(.WIDTH(66), .SAME_CLOCK(CORE_SAME_CLOCK)) status_cdc (
    .src_clock(core_clock), .dst_clock(ap_clk), .reset(fifo_reset),
    .src_bits({core_error, core_done, core_checksum}), .dst_bits(shell_status));

  assign s_axi_control_awready = !aw_pending && !write_response_valid;
  assign s_axi_control_wready = !w_pending && !write_response_valid;
  assign s_axi_control_bvalid = write_response_valid; assign s_axi_control_bresp = 2'b00;
  assign s_axi_control_arready = !read_response_valid;
  assign s_axi_control_rvalid = read_response_valid;
  assign s_axi_control_rdata = read_response_data; assign s_axi_control_rresp = 2'b00;
  assign interrupt = global_interrupt_enable && |(interrupt_enable & interrupt_status);

  function automatic [31:0] merge_strobes;
    input [31:0] previous, next_value; input [3:0] strobes; integer byte_index;
    begin
      merge_strobes = previous;
      for (byte_index = 0; byte_index < 4; byte_index = byte_index + 1)
        if (strobes[byte_index]) merge_strobes[byte_index*8 +: 8] = next_value[byte_index*8 +: 8];
    end
  endfunction

  function automatic [31:0] read_control;
    input [11:0] address; integer lane;
    begin
      read_control = 32'b0;
      case (address)
        12'h000: read_control = {24'b0, auto_restart, 3'b0, ap_ready, !active, ap_done, ap_start};
        12'h004: read_control = {31'b0, global_interrupt_enable};
        12'h008: read_control = {30'b0, interrupt_enable};
        12'h00c: read_control = {30'b0, interrupt_status};
        12'h100: read_control = completed_checksum[31:0];
        12'h104: read_control = completed_checksum[63:32];
        12'h108: read_control = {30'b0, completed_error, active};
        default: for (lane = 0; lane < 16; lane = lane + 1) begin
          if (address == 12'h010 + lane*8) read_control = hbm_base[lane][31:0];
          if (address == 12'h014 + lane*8) read_control = hbm_base[lane][63:32];
        end
      endcase
    end
  endfunction

  always @(posedge ap_clk) begin
    if (!ap_rst_n) begin
      aw_pending <= 1'b0; w_pending <= 1'b0; write_response_valid <= 1'b0;
      read_response_valid <= 1'b0; read_response_data <= 32'b0;
      ap_start <= 1'b0; ap_done <= 1'b0; ap_ready <= 1'b0; active <= 1'b0;
      auto_restart <= 1'b0; global_interrupt_enable <= 1'b0;
      interrupt_enable <= 2'b0; interrupt_status <= 2'b0;
      start_toggle <= 1'b0; completed_checksum <= 64'b0; completed_error <= 1'b0;
      for (index = 0; index < 16; index = index + 1) hbm_base[index] <= 64'b0;
    end else begin
      ap_ready <= 1'b0;
      if (s_axi_control_awready && s_axi_control_awvalid) begin aw_pending <= 1'b1; aw_address <= s_axi_control_awaddr; end
      if (s_axi_control_wready && s_axi_control_wvalid) begin w_pending <= 1'b1; w_data <= s_axi_control_wdata; w_strobe <= s_axi_control_wstrb; end
      if (aw_pending && w_pending && !write_response_valid) begin
        aw_pending <= 1'b0; w_pending <= 1'b0; write_response_valid <= 1'b1;
        case (aw_address)
          12'h000: if (w_strobe[0]) begin if (w_data[0]) ap_start <= 1'b1; auto_restart <= w_data[7]; end
          12'h004: if (w_strobe[0]) global_interrupt_enable <= w_data[0];
          12'h008: if (w_strobe[0]) interrupt_enable <= w_data[1:0];
          12'h00c: if (w_strobe[0]) interrupt_status <= interrupt_status ^ w_data[1:0];
          default: for (index = 0; index < 16; index = index + 1) begin
            if (aw_address == 12'h010 + index*8)
              hbm_base[index][31:0] <= merge_strobes(hbm_base[index][31:0], w_data, w_strobe);
            if (aw_address == 12'h014 + index*8)
              hbm_base[index][63:32] <= merge_strobes(hbm_base[index][63:32], w_data, w_strobe);
          end
        endcase
      end
      if (write_response_valid && s_axi_control_bready) write_response_valid <= 1'b0;
      if (s_axi_control_arready && s_axi_control_arvalid) begin
        read_response_valid <= 1'b1; read_response_data <= read_control(s_axi_control_araddr);
        if (s_axi_control_araddr == 12'h000) ap_done <= 1'b0;
      end
      if (read_response_valid && s_axi_control_rready) read_response_valid <= 1'b0;
      if (start_pulse) begin
        active <= 1'b1; ap_done <= 1'b0; ap_ready <= 1'b0;
        completed_error <= 1'b0; completed_checksum <= 64'b0;
        start_toggle <= ~start_toggle; if (!auto_restart) ap_start <= 1'b0;
      end
      if (active && shell_status[64]) begin
        active <= 1'b0; ap_done <= 1'b1; ap_ready <= 1'b1;
        completed_error <= shell_status[65]; completed_checksum <= shell_status[63:0];
        interrupt_status <= interrupt_status | 2'b11;
        if (auto_restart) ap_start <= 1'b1;
      end
    end
  end

  always @(posedge core_clock) begin
    if (!core_reset_n) core_start_seen <= 1'b0;
    else core_start_seen <= core_start_toggle;
  end

`SPMV_CUPERFLOW_AXI_WIRES(0)  `SPMV_CUPERFLOW_AXI_WIRES(1)
`SPMV_CUPERFLOW_AXI_WIRES(2)  `SPMV_CUPERFLOW_AXI_WIRES(3)
`SPMV_CUPERFLOW_AXI_WIRES(4)  `SPMV_CUPERFLOW_AXI_WIRES(5)
`SPMV_CUPERFLOW_AXI_WIRES(6)  `SPMV_CUPERFLOW_AXI_WIRES(7)
`SPMV_CUPERFLOW_AXI_WIRES(8)  `SPMV_CUPERFLOW_AXI_WIRES(9)
`SPMV_CUPERFLOW_AXI_WIRES(10) `SPMV_CUPERFLOW_AXI_WIRES(11)
`SPMV_CUPERFLOW_AXI_WIRES(12) `SPMV_CUPERFLOW_AXI_WIRES(13)
`SPMV_CUPERFLOW_AXI_WIRES(14) `SPMV_CUPERFLOW_AXI_WIRES(15)
`SPMV_CUPERFLOW_AXI_BIND(pc00, 0)  `SPMV_CUPERFLOW_AXI_BIND(pc01, 1)
`SPMV_CUPERFLOW_AXI_BIND(pc02, 2)  `SPMV_CUPERFLOW_AXI_BIND(pc03, 3)
`SPMV_CUPERFLOW_AXI_BIND(pc04, 4)  `SPMV_CUPERFLOW_AXI_BIND(pc05, 5)
`SPMV_CUPERFLOW_AXI_BIND(pc06, 6)  `SPMV_CUPERFLOW_AXI_BIND(pc07, 7)
`SPMV_CUPERFLOW_AXI_BIND(pc08, 8)  `SPMV_CUPERFLOW_AXI_BIND(pc09, 9)
`SPMV_CUPERFLOW_AXI_BIND(pc10, 10) `SPMV_CUPERFLOW_AXI_BIND(pc11, 11)
`SPMV_CUPERFLOW_AXI_BIND(pc12, 12) `SPMV_CUPERFLOW_AXI_BIND(pc13, 13)
`SPMV_CUPERFLOW_AXI_BIND(pc14, 14) `SPMV_CUPERFLOW_AXI_BIND(pc15, 15)

  SpmvCuperflowInputTop core (
    .clock(core_clock), .reset(!core_reset_n), .io_start(core_start),
    `SPMV_CUPERFLOW_CORE_BIND(0),  `SPMV_CUPERFLOW_CORE_BIND(1),
    `SPMV_CUPERFLOW_CORE_BIND(2),  `SPMV_CUPERFLOW_CORE_BIND(3),
    `SPMV_CUPERFLOW_CORE_BIND(4),  `SPMV_CUPERFLOW_CORE_BIND(5),
    `SPMV_CUPERFLOW_CORE_BIND(6),  `SPMV_CUPERFLOW_CORE_BIND(7),
    `SPMV_CUPERFLOW_CORE_BIND(8),  `SPMV_CUPERFLOW_CORE_BIND(9),
    `SPMV_CUPERFLOW_CORE_BIND(10), `SPMV_CUPERFLOW_CORE_BIND(11),
    `SPMV_CUPERFLOW_CORE_BIND(12), `SPMV_CUPERFLOW_CORE_BIND(13),
    `SPMV_CUPERFLOW_CORE_BIND(14), `SPMV_CUPERFLOW_CORE_BIND(15),
    .io_done(core_done), .io_error(core_error), .io_productChecksum(core_checksum)
  );
endmodule

`undef SPMV_CUPERFLOW_CORE_BIND
`undef SPMV_CUPERFLOW_AXI_BIND
`undef SPMV_CUPERFLOW_AXI_WIRES
`undef SPMV_CUPERFLOW_AXI_MASTER_PORT
