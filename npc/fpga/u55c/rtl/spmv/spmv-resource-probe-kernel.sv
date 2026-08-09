`define SPMV_AXI_MASTER_PORT(NAME) \
  output wire         m_axi_``NAME``_awvalid, \
  input  wire         m_axi_``NAME``_awready, \
  output wire [63:0]  m_axi_``NAME``_awaddr, \
  output wire [3:0]   m_axi_``NAME``_awid, \
  output wire [7:0]   m_axi_``NAME``_awlen, \
  output wire [2:0]   m_axi_``NAME``_awsize, \
  output wire [1:0]   m_axi_``NAME``_awburst, \
  output wire         m_axi_``NAME``_awlock, \
  output wire [3:0]   m_axi_``NAME``_awcache, \
  output wire [2:0]   m_axi_``NAME``_awprot, \
  output wire [3:0]   m_axi_``NAME``_awqos, \
  output wire         m_axi_``NAME``_wvalid, \
  input  wire         m_axi_``NAME``_wready, \
  output wire [511:0] m_axi_``NAME``_wdata, \
  output wire [63:0]  m_axi_``NAME``_wstrb, \
  output wire         m_axi_``NAME``_wlast, \
  input  wire         m_axi_``NAME``_bvalid, \
  output wire         m_axi_``NAME``_bready, \
  input  wire [3:0]   m_axi_``NAME``_bid, \
  input  wire [1:0]   m_axi_``NAME``_bresp, \
  output wire         m_axi_``NAME``_arvalid, \
  input  wire         m_axi_``NAME``_arready, \
  output wire [63:0]  m_axi_``NAME``_araddr, \
  output wire [3:0]   m_axi_``NAME``_arid, \
  output wire [7:0]   m_axi_``NAME``_arlen, \
  output wire [2:0]   m_axi_``NAME``_arsize, \
  output wire [1:0]   m_axi_``NAME``_arburst, \
  output wire         m_axi_``NAME``_arlock, \
  output wire [3:0]   m_axi_``NAME``_arcache, \
  output wire [2:0]   m_axi_``NAME``_arprot, \
  output wire [3:0]   m_axi_``NAME``_arqos, \
  input  wire         m_axi_``NAME``_rvalid, \
  output wire         m_axi_``NAME``_rready, \
  input  wire [3:0]   m_axi_``NAME``_rid, \
  input  wire [511:0] m_axi_``NAME``_rdata, \
  input  wire [1:0]   m_axi_``NAME``_rresp, \
  input  wire         m_axi_``NAME``_rlast

`define SPMV_AXI_READ_WIRES(INDEX) \
  wire         core_axi_``INDEX``_arvalid; \
  wire [63:0]  core_axi_``INDEX``_araddr; \
  wire [3:0]   core_axi_``INDEX``_arid; \
  wire [7:0]   core_axi_``INDEX``_arlen; \
  wire [2:0]   core_axi_``INDEX``_arsize; \
  wire [1:0]   core_axi_``INDEX``_arburst; \
  wire         core_axi_``INDEX``_arlock; \
  wire [3:0]   core_axi_``INDEX``_arcache; \
  wire [2:0]   core_axi_``INDEX``_arprot; \
  wire [3:0]   core_axi_``INDEX``_arqos; \
  wire         core_axi_``INDEX``_rready

`define SPMV_AXI_BIND(NAME, INDEX) \
  assign m_axi_``NAME``_awvalid = 1'b0; \
  assign m_axi_``NAME``_awaddr = 64'b0; \
  assign m_axi_``NAME``_awid = 4'b0; \
  assign m_axi_``NAME``_awlen = 8'b0; \
  assign m_axi_``NAME``_awsize = 3'd6; \
  assign m_axi_``NAME``_awburst = 2'b01; \
  assign m_axi_``NAME``_awlock = 1'b0; \
  assign m_axi_``NAME``_awcache = 4'b0; \
  assign m_axi_``NAME``_awprot = 3'b0; \
  assign m_axi_``NAME``_awqos = 4'b0; \
  assign m_axi_``NAME``_wvalid = 1'b0; \
  assign m_axi_``NAME``_wdata = 512'b0; \
  assign m_axi_``NAME``_wstrb = 64'b0; \
  assign m_axi_``NAME``_wlast = 1'b1; \
  assign m_axi_``NAME``_bready = 1'b1; \
  assign m_axi_``NAME``_arvalid = core_axi_``INDEX``_arvalid; \
  assign m_axi_``NAME``_araddr = core_axi_``INDEX``_araddr; \
  assign m_axi_``NAME``_arid = core_axi_``INDEX``_arid; \
  assign m_axi_``NAME``_arlen = core_axi_``INDEX``_arlen; \
  assign m_axi_``NAME``_arsize = core_axi_``INDEX``_arsize; \
  assign m_axi_``NAME``_arburst = core_axi_``INDEX``_arburst; \
  assign m_axi_``NAME``_arlock = core_axi_``INDEX``_arlock; \
  assign m_axi_``NAME``_arcache = core_axi_``INDEX``_arcache; \
  assign m_axi_``NAME``_arprot = core_axi_``INDEX``_arprot; \
  assign m_axi_``NAME``_arqos = core_axi_``INDEX``_arqos; \
  assign m_axi_``NAME``_rready = core_axi_``INDEX``_rready

`define SPMV_CORE_BIND(NAME, INDEX) \
    .io_baseAddresses_``INDEX (base_addresses[INDEX]), \
    .io_axi_``INDEX``_ar_ready (m_axi_``NAME``_arready), \
    .io_axi_``INDEX``_ar_valid (core_axi_``INDEX``_arvalid), \
    .io_axi_``INDEX``_ar_bits_id (core_axi_``INDEX``_arid), \
    .io_axi_``INDEX``_ar_bits_addr (core_axi_``INDEX``_araddr), \
    .io_axi_``INDEX``_ar_bits_len (core_axi_``INDEX``_arlen), \
    .io_axi_``INDEX``_ar_bits_size (core_axi_``INDEX``_arsize), \
    .io_axi_``INDEX``_ar_bits_burst (core_axi_``INDEX``_arburst), \
    .io_axi_``INDEX``_ar_bits_lock (core_axi_``INDEX``_arlock), \
    .io_axi_``INDEX``_ar_bits_cache (core_axi_``INDEX``_arcache), \
    .io_axi_``INDEX``_ar_bits_prot (core_axi_``INDEX``_arprot), \
    .io_axi_``INDEX``_ar_bits_qos (core_axi_``INDEX``_arqos), \
    .io_axi_``INDEX``_r_ready (core_axi_``INDEX``_rready), \
    .io_axi_``INDEX``_r_valid (m_axi_``NAME``_rvalid), \
    .io_axi_``INDEX``_r_bits_id (m_axi_``NAME``_rid), \
    .io_axi_``INDEX``_r_bits_data (m_axi_``NAME``_rdata), \
    .io_axi_``INDEX``_r_bits_resp (m_axi_``NAME``_rresp), \
    .io_axi_``INDEX``_r_bits_last (m_axi_``NAME``_rlast)

module SpmvResourceProbeKernel (
  input  wire        ap_clk,
  input  wire        ap_rst_n,
  output wire        interrupt,

  `SPMV_AXI_MASTER_PORT(pc00),
  `SPMV_AXI_MASTER_PORT(pc01),
  `SPMV_AXI_MASTER_PORT(pc02),
  `SPMV_AXI_MASTER_PORT(pc03),
  `SPMV_AXI_MASTER_PORT(pc04),
  `SPMV_AXI_MASTER_PORT(pc05),
  `SPMV_AXI_MASTER_PORT(pc06),
  `SPMV_AXI_MASTER_PORT(pc07),
  `SPMV_AXI_MASTER_PORT(pc08),
  `SPMV_AXI_MASTER_PORT(pc09),
  `SPMV_AXI_MASTER_PORT(pc10),
  `SPMV_AXI_MASTER_PORT(pc11),
  `SPMV_AXI_MASTER_PORT(pc12),
  `SPMV_AXI_MASTER_PORT(pc13),
  `SPMV_AXI_MASTER_PORT(pc14),
  `SPMV_AXI_MASTER_PORT(pc15),
  `SPMV_AXI_MASTER_PORT(pc16),
  `SPMV_AXI_MASTER_PORT(pc17),
  `SPMV_AXI_MASTER_PORT(pc18),
  `SPMV_AXI_MASTER_PORT(pc19),
  `SPMV_AXI_MASTER_PORT(pc20),
  `SPMV_AXI_MASTER_PORT(pc21),
  `SPMV_AXI_MASTER_PORT(pc22),
  `SPMV_AXI_MASTER_PORT(pc23),
  `SPMV_AXI_MASTER_PORT(pc24),
  `SPMV_AXI_MASTER_PORT(pc25),
  `SPMV_AXI_MASTER_PORT(pc26),
  `SPMV_AXI_MASTER_PORT(pc27),
  `SPMV_AXI_MASTER_PORT(pc28),
  `SPMV_AXI_MASTER_PORT(pc29),
  `SPMV_AXI_MASTER_PORT(pc30),
  `SPMV_AXI_MASTER_PORT(pc31),

  input  wire        s_axi_control_awvalid,
  output wire        s_axi_control_awready,
  input  wire [11:0] s_axi_control_awaddr,
  input  wire        s_axi_control_wvalid,
  output wire        s_axi_control_wready,
  input  wire [31:0] s_axi_control_wdata,
  input  wire [3:0]  s_axi_control_wstrb,
  output wire        s_axi_control_bvalid,
  input  wire        s_axi_control_bready,
  output wire [1:0]  s_axi_control_bresp,
  input  wire        s_axi_control_arvalid,
  output wire        s_axi_control_arready,
  input  wire [11:0] s_axi_control_araddr,
  output wire        s_axi_control_rvalid,
  input  wire        s_axi_control_rready,
  output wire [31:0] s_axi_control_rdata,
  output wire [1:0]  s_axi_control_rresp
);
  reg [63:0] base_addresses [0:31];
  reg        aw_pending;
  reg [11:0] aw_address;
  reg        w_pending;
  reg [31:0] w_data;
  reg [3:0]  w_strobe;
  reg        write_response_valid;
  reg        read_response_valid;
  reg [31:0] read_response_data;
  reg        ap_start;
  reg        ap_done;
  reg        ap_ready;
  reg        auto_restart;
  reg        active;
  reg        global_interrupt_enable;
  reg [1:0]  interrupt_enable;
  reg [1:0]  interrupt_status;
  integer index;

  wire        start_pulse = ap_start && !active;
  wire [63:0] aggregate_checksum;
  wire [31:0] lane_done_mask;
  wire [31:0] lane_error_mask;

  assign s_axi_control_awready = !aw_pending && !write_response_valid;
  assign s_axi_control_wready = !w_pending && !write_response_valid;
  assign s_axi_control_bvalid = write_response_valid;
  assign s_axi_control_bresp = 2'b00;
  assign s_axi_control_arready = !read_response_valid;
  assign s_axi_control_rvalid = read_response_valid;
  assign s_axi_control_rdata = read_response_data;
  assign s_axi_control_rresp = 2'b00;
  assign interrupt = global_interrupt_enable && |(interrupt_enable & interrupt_status);

  function automatic [31:0] merge_strobes;
    input [31:0] previous;
    input [31:0] next_value;
    input [3:0] strobes;
    integer byte_index;
    begin
      merge_strobes = previous;
      for (byte_index = 0; byte_index < 4; byte_index = byte_index + 1)
        if (strobes[byte_index])
          merge_strobes[byte_index * 8 +: 8] = next_value[byte_index * 8 +: 8];
    end
  endfunction

  function automatic [31:0] read_control;
    input [11:0] address;
    integer lane;
    begin
      read_control = 32'b0;
      case (address)
        12'h000: read_control = {24'b0, auto_restart, 3'b0, ap_ready, !active, ap_done, ap_start};
        12'h004: read_control = {31'b0, global_interrupt_enable};
        12'h008: read_control = {30'b0, interrupt_enable};
        12'h00c: read_control = {30'b0, interrupt_status};
        12'h110: read_control = aggregate_checksum[31:0];
        12'h114: read_control = lane_done_mask;
        12'h118: read_control = lane_error_mask;
        12'h11c: read_control = aggregate_checksum[63:32];
        default: begin
          for (lane = 0; lane < 32; lane = lane + 1) begin
            if (address == 12'h010 + lane * 8) read_control = base_addresses[lane][31:0];
            if (address == 12'h014 + lane * 8) read_control = base_addresses[lane][63:32];
          end
        end
      endcase
    end
  endfunction

  always @(posedge ap_clk) begin
    if (!ap_rst_n) begin
      aw_pending <= 1'b0;
      w_pending <= 1'b0;
      write_response_valid <= 1'b0;
      read_response_valid <= 1'b0;
      read_response_data <= 32'b0;
      ap_start <= 1'b0;
      ap_done <= 1'b0;
      ap_ready <= 1'b0;
      auto_restart <= 1'b0;
      active <= 1'b0;
      global_interrupt_enable <= 1'b0;
      interrupt_enable <= 2'b0;
      interrupt_status <= 2'b0;
      for (index = 0; index < 32; index = index + 1) base_addresses[index] <= 64'b0;
    end else begin
      ap_ready <= 1'b0;
      if (s_axi_control_awready && s_axi_control_awvalid) begin
        aw_pending <= 1'b1;
        aw_address <= s_axi_control_awaddr;
      end
      if (s_axi_control_wready && s_axi_control_wvalid) begin
        w_pending <= 1'b1;
        w_data <= s_axi_control_wdata;
        w_strobe <= s_axi_control_wstrb;
      end
      if (aw_pending && w_pending && !write_response_valid) begin
        aw_pending <= 1'b0;
        w_pending <= 1'b0;
        write_response_valid <= 1'b1;
        case (aw_address)
          12'h000: begin
            if (w_strobe[0]) begin
              if (w_data[0]) ap_start <= 1'b1;
              auto_restart <= w_data[7];
            end
          end
          12'h004: if (w_strobe[0]) global_interrupt_enable <= w_data[0];
          12'h008: if (w_strobe[0]) interrupt_enable <= w_data[1:0];
          12'h00c: if (w_strobe[0]) interrupt_status <= interrupt_status ^ w_data[1:0];
          default: begin
            for (index = 0; index < 32; index = index + 1) begin
              if (aw_address == 12'h010 + index * 8)
                base_addresses[index][31:0] <= merge_strobes(base_addresses[index][31:0], w_data, w_strobe);
              if (aw_address == 12'h014 + index * 8)
                base_addresses[index][63:32] <= merge_strobes(base_addresses[index][63:32], w_data, w_strobe);
            end
          end
        endcase
      end
      if (write_response_valid && s_axi_control_bready) write_response_valid <= 1'b0;

      if (s_axi_control_arready && s_axi_control_arvalid) begin
        read_response_valid <= 1'b1;
        read_response_data <= read_control(s_axi_control_araddr);
        if (s_axi_control_araddr == 12'h000) ap_done <= 1'b0;
      end
      if (read_response_valid && s_axi_control_rready) read_response_valid <= 1'b0;

      if (start_pulse) begin
        active <= 1'b1;
        ap_done <= 1'b0;
        if (!auto_restart) ap_start <= 1'b0;
      end
      if (active && &lane_done_mask) begin
        active <= 1'b0;
        ap_done <= 1'b1;
        ap_ready <= 1'b1;
        interrupt_status <= interrupt_status | 2'b11;
        if (auto_restart) ap_start <= 1'b1;
      end
    end
  end

  `SPMV_AXI_READ_WIRES(0);
  `SPMV_AXI_READ_WIRES(1);
  `SPMV_AXI_READ_WIRES(2);
  `SPMV_AXI_READ_WIRES(3);
  `SPMV_AXI_READ_WIRES(4);
  `SPMV_AXI_READ_WIRES(5);
  `SPMV_AXI_READ_WIRES(6);
  `SPMV_AXI_READ_WIRES(7);
  `SPMV_AXI_READ_WIRES(8);
  `SPMV_AXI_READ_WIRES(9);
  `SPMV_AXI_READ_WIRES(10);
  `SPMV_AXI_READ_WIRES(11);
  `SPMV_AXI_READ_WIRES(12);
  `SPMV_AXI_READ_WIRES(13);
  `SPMV_AXI_READ_WIRES(14);
  `SPMV_AXI_READ_WIRES(15);
  `SPMV_AXI_READ_WIRES(16);
  `SPMV_AXI_READ_WIRES(17);
  `SPMV_AXI_READ_WIRES(18);
  `SPMV_AXI_READ_WIRES(19);
  `SPMV_AXI_READ_WIRES(20);
  `SPMV_AXI_READ_WIRES(21);
  `SPMV_AXI_READ_WIRES(22);
  `SPMV_AXI_READ_WIRES(23);
  `SPMV_AXI_READ_WIRES(24);
  `SPMV_AXI_READ_WIRES(25);
  `SPMV_AXI_READ_WIRES(26);
  `SPMV_AXI_READ_WIRES(27);
  `SPMV_AXI_READ_WIRES(28);
  `SPMV_AXI_READ_WIRES(29);
  `SPMV_AXI_READ_WIRES(30);
  `SPMV_AXI_READ_WIRES(31);

  `SPMV_AXI_BIND(pc00, 0);
  `SPMV_AXI_BIND(pc01, 1);
  `SPMV_AXI_BIND(pc02, 2);
  `SPMV_AXI_BIND(pc03, 3);
  `SPMV_AXI_BIND(pc04, 4);
  `SPMV_AXI_BIND(pc05, 5);
  `SPMV_AXI_BIND(pc06, 6);
  `SPMV_AXI_BIND(pc07, 7);
  `SPMV_AXI_BIND(pc08, 8);
  `SPMV_AXI_BIND(pc09, 9);
  `SPMV_AXI_BIND(pc10, 10);
  `SPMV_AXI_BIND(pc11, 11);
  `SPMV_AXI_BIND(pc12, 12);
  `SPMV_AXI_BIND(pc13, 13);
  `SPMV_AXI_BIND(pc14, 14);
  `SPMV_AXI_BIND(pc15, 15);
  `SPMV_AXI_BIND(pc16, 16);
  `SPMV_AXI_BIND(pc17, 17);
  `SPMV_AXI_BIND(pc18, 18);
  `SPMV_AXI_BIND(pc19, 19);
  `SPMV_AXI_BIND(pc20, 20);
  `SPMV_AXI_BIND(pc21, 21);
  `SPMV_AXI_BIND(pc22, 22);
  `SPMV_AXI_BIND(pc23, 23);
  `SPMV_AXI_BIND(pc24, 24);
  `SPMV_AXI_BIND(pc25, 25);
  `SPMV_AXI_BIND(pc26, 26);
  `SPMV_AXI_BIND(pc27, 27);
  `SPMV_AXI_BIND(pc28, 28);
  `SPMV_AXI_BIND(pc29, 29);
  `SPMV_AXI_BIND(pc30, 30);
  `SPMV_AXI_BIND(pc31, 31);

  SpmvResourceProbeTop core (
    .clock (ap_clk),
    .reset (!ap_rst_n),
    .io_start (start_pulse),
    `SPMV_CORE_BIND(pc00, 0),
    `SPMV_CORE_BIND(pc01, 1),
    `SPMV_CORE_BIND(pc02, 2),
    `SPMV_CORE_BIND(pc03, 3),
    `SPMV_CORE_BIND(pc04, 4),
    `SPMV_CORE_BIND(pc05, 5),
    `SPMV_CORE_BIND(pc06, 6),
    `SPMV_CORE_BIND(pc07, 7),
    `SPMV_CORE_BIND(pc08, 8),
    `SPMV_CORE_BIND(pc09, 9),
    `SPMV_CORE_BIND(pc10, 10),
    `SPMV_CORE_BIND(pc11, 11),
    `SPMV_CORE_BIND(pc12, 12),
    `SPMV_CORE_BIND(pc13, 13),
    `SPMV_CORE_BIND(pc14, 14),
    `SPMV_CORE_BIND(pc15, 15),
    `SPMV_CORE_BIND(pc16, 16),
    `SPMV_CORE_BIND(pc17, 17),
    `SPMV_CORE_BIND(pc18, 18),
    `SPMV_CORE_BIND(pc19, 19),
    `SPMV_CORE_BIND(pc20, 20),
    `SPMV_CORE_BIND(pc21, 21),
    `SPMV_CORE_BIND(pc22, 22),
    `SPMV_CORE_BIND(pc23, 23),
    `SPMV_CORE_BIND(pc24, 24),
    `SPMV_CORE_BIND(pc25, 25),
    `SPMV_CORE_BIND(pc26, 26),
    `SPMV_CORE_BIND(pc27, 27),
    `SPMV_CORE_BIND(pc28, 28),
    `SPMV_CORE_BIND(pc29, 29),
    `SPMV_CORE_BIND(pc30, 30),
    `SPMV_CORE_BIND(pc31, 31),
    .io_aggregateChecksum (aggregate_checksum),
    .io_doneMask (lane_done_mask),
    .io_errorMask (lane_error_mask)
  );
endmodule

`undef SPMV_CORE_BIND
`undef SPMV_AXI_BIND
`undef SPMV_AXI_READ_WIRES
`undef SPMV_AXI_MASTER_PORT
