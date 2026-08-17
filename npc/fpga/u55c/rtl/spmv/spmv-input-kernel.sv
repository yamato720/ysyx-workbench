`define SPMV_INPUT_AXI_MASTER_PORT(NAME) \
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

`define SPMV_INPUT_AXI_READ_WIRES(INDEX) \
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

`define SPMV_INPUT_AXI_BIND(NAME, INDEX) \
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

`define SPMV_INPUT_A_CORE_BIND(NAME, INDEX, PORT) \
    .io_aHbm_``INDEX``_ar_ready (m_axi_``NAME``_arready), \
    .io_aHbm_``INDEX``_ar_valid (core_axi_``PORT``_arvalid), \
    .io_aHbm_``INDEX``_ar_bits_id (core_axi_``PORT``_arid), \
    .io_aHbm_``INDEX``_ar_bits_addr (core_axi_``PORT``_araddr), \
    .io_aHbm_``INDEX``_ar_bits_len (core_axi_``PORT``_arlen), \
    .io_aHbm_``INDEX``_ar_bits_size (core_axi_``PORT``_arsize), \
    .io_aHbm_``INDEX``_ar_bits_burst (core_axi_``PORT``_arburst), \
    .io_aHbm_``INDEX``_ar_bits_lock (core_axi_``PORT``_arlock), \
    .io_aHbm_``INDEX``_ar_bits_cache (core_axi_``PORT``_arcache), \
    .io_aHbm_``INDEX``_ar_bits_prot (core_axi_``PORT``_arprot), \
    .io_aHbm_``INDEX``_ar_bits_qos (core_axi_``PORT``_arqos), \
    .io_aHbm_``INDEX``_r_ready (core_axi_``PORT``_rready), \
    .io_aHbm_``INDEX``_r_valid (m_axi_``NAME``_rvalid), \
    .io_aHbm_``INDEX``_r_bits_id (m_axi_``NAME``_rid), \
    .io_aHbm_``INDEX``_r_bits_data (m_axi_``NAME``_rdata), \
    .io_aHbm_``INDEX``_r_bits_resp (m_axi_``NAME``_rresp), \
    .io_aHbm_``INDEX``_r_bits_last (m_axi_``NAME``_rlast)

`define SPMV_INPUT_X_CORE_BIND(NAME, INDEX, PORT) \
    .io_xHbm_``INDEX``_ar_ready (m_axi_``NAME``_arready), \
    .io_xHbm_``INDEX``_ar_valid (core_axi_``PORT``_arvalid), \
    .io_xHbm_``INDEX``_ar_bits_id (core_axi_``PORT``_arid), \
    .io_xHbm_``INDEX``_ar_bits_addr (core_axi_``PORT``_araddr), \
    .io_xHbm_``INDEX``_ar_bits_len (core_axi_``PORT``_arlen), \
    .io_xHbm_``INDEX``_ar_bits_size (core_axi_``PORT``_arsize), \
    .io_xHbm_``INDEX``_ar_bits_burst (core_axi_``PORT``_arburst), \
    .io_xHbm_``INDEX``_ar_bits_lock (core_axi_``PORT``_arlock), \
    .io_xHbm_``INDEX``_ar_bits_cache (core_axi_``PORT``_arcache), \
    .io_xHbm_``INDEX``_ar_bits_prot (core_axi_``PORT``_arprot), \
    .io_xHbm_``INDEX``_ar_bits_qos (core_axi_``PORT``_arqos), \
    .io_xHbm_``INDEX``_r_ready (core_axi_``PORT``_rready), \
    .io_xHbm_``INDEX``_r_valid (m_axi_``NAME``_rvalid), \
    .io_xHbm_``INDEX``_r_bits_id (m_axi_``NAME``_rid), \
    .io_xHbm_``INDEX``_r_bits_data (m_axi_``NAME``_rdata), \
    .io_xHbm_``INDEX``_r_bits_resp (m_axi_``NAME``_rresp), \
    .io_xHbm_``INDEX``_r_bits_last (m_axi_``NAME``_rlast)

module SpmvInputKernel (
  input wire ap_clk,
  input wire ap_rst_n,
  output wire interrupt,

  `SPMV_INPUT_AXI_MASTER_PORT(pc00),
  `SPMV_INPUT_AXI_MASTER_PORT(pc01),
  `SPMV_INPUT_AXI_MASTER_PORT(pc02),
  `SPMV_INPUT_AXI_MASTER_PORT(pc03),
  `SPMV_INPUT_AXI_MASTER_PORT(pc04),
  `SPMV_INPUT_AXI_MASTER_PORT(pc05),
  `SPMV_INPUT_AXI_MASTER_PORT(pc06),
  `SPMV_INPUT_AXI_MASTER_PORT(pc07),
  `SPMV_INPUT_AXI_MASTER_PORT(pc08),
  `SPMV_INPUT_AXI_MASTER_PORT(pc09),
  `SPMV_INPUT_AXI_MASTER_PORT(pc10),
  `SPMV_INPUT_AXI_MASTER_PORT(pc11),
  `SPMV_INPUT_AXI_MASTER_PORT(pc12),
  `SPMV_INPUT_AXI_MASTER_PORT(pc13),
  `SPMV_INPUT_AXI_MASTER_PORT(pc14),
  `SPMV_INPUT_AXI_MASTER_PORT(pc15),
  `SPMV_INPUT_AXI_MASTER_PORT(pc16),
  `SPMV_INPUT_AXI_MASTER_PORT(pc17),
  `SPMV_INPUT_AXI_MASTER_PORT(pc18),

  input wire s_axi_control_awvalid,
  output wire s_axi_control_awready,
  input wire [11:0] s_axi_control_awaddr,
  input wire s_axi_control_wvalid,
  output wire s_axi_control_wready,
  input wire [31:0] s_axi_control_wdata,
  input wire [3:0] s_axi_control_wstrb,
  output wire s_axi_control_bvalid,
  input wire s_axi_control_bready,
  output wire [1:0] s_axi_control_bresp,
  input wire s_axi_control_arvalid,
  output wire s_axi_control_arready,
  input wire [11:0] s_axi_control_araddr,
  output wire s_axi_control_rvalid,
  input wire s_axi_control_rready,
  output wire [31:0] s_axi_control_rdata,
  output wire [1:0] s_axi_control_rresp
);
  localparam [2:0] S_IDLE = 3'd0;
  localparam [2:0] S_CTRL_REQUEST = 3'd1;
  localparam [2:0] S_CTRL_WAIT = 3'd2;
  localparam [2:0] S_X_REQUEST = 3'd3;
  localparam [2:0] S_PRELOAD_WAIT = 3'd4;
  localparam [2:0] S_RUN = 3'd5;

  reg [63:0] hbm_base [0:18];
  reg [63:0] a_offset [0:15];
  reg [31:0] a_beats [0:15];
  reg [63:0] x_offset [0:1];
  reg [31:0] x_beats [0:1];
  reg [31:0] ctrl_beats;
  reg [7:0] batch_index;
  reg [2:0] state;
  reg [15:0] a_issued;
  reg [1:0] x_issued;
  reg ctrl_loaded;
  reg active;
  reg ap_start;
  reg ap_done;
  reg ap_ready;
  reg auto_restart;
  reg error;
  reg global_interrupt_enable;
  reg [1:0] interrupt_enable;
  reg [1:0] interrupt_status;
  reg aw_pending;
  reg [11:0] aw_address;
  reg w_pending;
  reg [31:0] w_data;
  reg [3:0] w_strobe;
  reg write_response_valid;
  reg read_response_valid;
  reg [31:0] read_response_data;
  reg [63:0] completed_checksum;
  reg [63:0] cycle_counter;
  integer index;

  wire start_pulse = ap_start && !active;
  wire ctrl_request_ready;
  wire x_request_ready [0:1];
  wire [15:0] a_request_ready;
  wire [15:0] a_nonzero = {a_beats[15] != 0, a_beats[14] != 0, a_beats[13] != 0,
    a_beats[12] != 0, a_beats[11] != 0, a_beats[10] != 0, a_beats[9] != 0,
    a_beats[8] != 0, a_beats[7] != 0, a_beats[6] != 0, a_beats[5] != 0,
    a_beats[4] != 0, a_beats[3] != 0, a_beats[2] != 0, a_beats[1] != 0,
    a_beats[0] != 0};
  wire [1:0] x_nonzero = {x_beats[1] != 0, x_beats[0] != 0};
  wire [15:0] a_request_valid = {16{state == S_RUN && mul_ready}} & ~a_issued & a_nonzero;
  wire [1:0] x_request_valid = {2{state == S_X_REQUEST}} & ~x_issued & x_nonzero;
  wire [15:0] a_request_fire = a_request_valid & a_request_ready;
  wire [1:0] x_request_fire = {x_request_valid[1] && x_request_ready[1],
    x_request_valid[0] && x_request_ready[0]};
  wire ctrl_request_valid = state == S_CTRL_REQUEST;
  wire ctrl_request_fire = ctrl_request_valid && ctrl_request_ready;
  wire all_a_issued = &(a_issued | ~a_nonzero);
  wire all_x_issued = &(x_issued | ~x_nonzero);
  wire mul_enable = state == S_RUN;
  wire x_idle0, x_idle1, ctrl_map_ready, mul_ready, compute_done, mul_error;
  wire [63:0] product_checksum;
  wire x_complete = x_idle0 && x_idle1;

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
        12'h0b0: read_control = ctrl_beats;
        12'h220: read_control = {24'b0, batch_index};
        12'h230: read_control = completed_checksum[31:0];
        12'h234: read_control = completed_checksum[63:32];
        12'h238: read_control = {26'b0, error, ctrl_loaded, state};
        12'h23c: read_control = cycle_counter[31:0];
        12'h240: read_control = cycle_counter[63:32];
        default: begin
          for (lane = 0; lane < 19; lane = lane + 1) begin
            if (address == 12'h010 + lane * 8) read_control = hbm_base[lane][31:0];
            if (address == 12'h014 + lane * 8) read_control = hbm_base[lane][63:32];
          end
          for (lane = 0; lane < 16; lane = lane + 1) begin
            if (address == 12'h100 + lane * 16) read_control = a_offset[lane][31:0];
            if (address == 12'h104 + lane * 16) read_control = a_offset[lane][63:32];
            if (address == 12'h108 + lane * 16) read_control = a_beats[lane];
          end
          for (lane = 0; lane < 2; lane = lane + 1) begin
            if (address == 12'h200 + lane * 16) read_control = x_offset[lane][31:0];
            if (address == 12'h204 + lane * 16) read_control = x_offset[lane][63:32];
            if (address == 12'h208 + lane * 16) read_control = x_beats[lane];
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
      error <= 1'b0;
      ctrl_loaded <= 1'b0;
      state <= S_IDLE;
      a_issued <= 16'b0;
      x_issued <= 2'b0;
      ctrl_beats <= 32'b0;
      batch_index <= 8'b0;
      completed_checksum <= 64'b0;
      cycle_counter <= 64'b0;
      global_interrupt_enable <= 1'b0;
      interrupt_enable <= 2'b0;
      interrupt_status <= 2'b0;
      for (index = 0; index < 19; index = index + 1) hbm_base[index] <= 64'b0;
      for (index = 0; index < 16; index = index + 1) begin
        a_offset[index] <= 64'b0;
        a_beats[index] <= 32'b0;
      end
      for (index = 0; index < 2; index = index + 1) begin
        x_offset[index] <= 64'b0;
        x_beats[index] <= 32'b0;
      end
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
          12'h000: if (w_strobe[0]) begin
            if (w_data[0]) ap_start <= 1'b1;
            auto_restart <= w_data[7];
          end
          12'h004: if (w_strobe[0]) global_interrupt_enable <= w_data[0];
          12'h008: if (w_strobe[0]) interrupt_enable <= w_data[1:0];
          12'h00c: if (w_strobe[0]) interrupt_status <= interrupt_status ^ w_data[1:0];
          12'h0b0: ctrl_beats <= merge_strobes(ctrl_beats, w_data, w_strobe);
          12'h220: if (w_strobe[0]) batch_index <= w_data[7:0];
          default: begin
            for (index = 0; index < 19; index = index + 1) begin
              if (aw_address == 12'h010 + index * 8)
                hbm_base[index][31:0] <= merge_strobes(hbm_base[index][31:0], w_data, w_strobe);
              if (aw_address == 12'h014 + index * 8)
                hbm_base[index][63:32] <= merge_strobes(hbm_base[index][63:32], w_data, w_strobe);
            end
            for (index = 0; index < 16; index = index + 1) begin
              if (aw_address == 12'h100 + index * 16)
                a_offset[index][31:0] <= merge_strobes(a_offset[index][31:0], w_data, w_strobe);
              if (aw_address == 12'h104 + index * 16)
                a_offset[index][63:32] <= merge_strobes(a_offset[index][63:32], w_data, w_strobe);
              if (aw_address == 12'h108 + index * 16)
                a_beats[index] <= merge_strobes(a_beats[index], w_data, w_strobe);
            end
            for (index = 0; index < 2; index = index + 1) begin
              if (aw_address == 12'h200 + index * 16)
                x_offset[index][31:0] <= merge_strobes(x_offset[index][31:0], w_data, w_strobe);
              if (aw_address == 12'h204 + index * 16)
                x_offset[index][63:32] <= merge_strobes(x_offset[index][63:32], w_data, w_strobe);
              if (aw_address == 12'h208 + index * 16)
                x_beats[index] <= merge_strobes(x_beats[index], w_data, w_strobe);
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
        error <= 1'b0;
        completed_checksum <= 64'b0;
        cycle_counter <= 64'b0;
        a_issued <= 16'b0;
        x_issued <= 2'b0;
        state <= ctrl_loaded ? S_X_REQUEST : S_CTRL_REQUEST;
        if (!auto_restart) ap_start <= 1'b0;
      end
      if (active) begin
        cycle_counter <= cycle_counter + 1'b1;
        if (mul_error) error <= 1'b1;
        case (state)
          S_CTRL_REQUEST: if (ctrl_request_fire) state <= S_CTRL_WAIT;
          S_CTRL_WAIT: if (ctrl_map_ready) begin
            ctrl_loaded <= 1'b1;
            state <= S_X_REQUEST;
          end
          S_X_REQUEST: begin
            x_issued <= x_issued | x_request_fire;
            if (&((x_issued | x_request_fire) | ~x_nonzero)) begin
`ifdef SPMV_INPUT_PINGPONG
              state <= S_RUN;
`else
              state <= S_PRELOAD_WAIT;
`endif
            end
          end
          S_PRELOAD_WAIT: if (x_complete) state <= S_RUN;
          S_RUN: begin
            a_issued <= a_issued | a_request_fire;
            if (&((a_issued | a_request_fire) | ~a_nonzero) && compute_done && x_complete) begin
              completed_checksum <= product_checksum;
              active <= 1'b0;
              ap_done <= 1'b1;
              ap_ready <= 1'b1;
              interrupt_status <= interrupt_status | 2'b11;
              state <= S_IDLE;
              if (auto_restart) ap_start <= 1'b1;
            end
          end
          default: state <= S_IDLE;
        endcase
      end
    end
  end

  `SPMV_INPUT_AXI_READ_WIRES(0); `SPMV_INPUT_AXI_READ_WIRES(1);
  `SPMV_INPUT_AXI_READ_WIRES(2); `SPMV_INPUT_AXI_READ_WIRES(3);
  `SPMV_INPUT_AXI_READ_WIRES(4); `SPMV_INPUT_AXI_READ_WIRES(5);
  `SPMV_INPUT_AXI_READ_WIRES(6); `SPMV_INPUT_AXI_READ_WIRES(7);
  `SPMV_INPUT_AXI_READ_WIRES(8); `SPMV_INPUT_AXI_READ_WIRES(9);
  `SPMV_INPUT_AXI_READ_WIRES(10); `SPMV_INPUT_AXI_READ_WIRES(11);
  `SPMV_INPUT_AXI_READ_WIRES(12); `SPMV_INPUT_AXI_READ_WIRES(13);
  `SPMV_INPUT_AXI_READ_WIRES(14); `SPMV_INPUT_AXI_READ_WIRES(15);
  `SPMV_INPUT_AXI_READ_WIRES(16); `SPMV_INPUT_AXI_READ_WIRES(17);
  `SPMV_INPUT_AXI_READ_WIRES(18);
  `SPMV_INPUT_AXI_BIND(pc00, 0); `SPMV_INPUT_AXI_BIND(pc01, 1);
  `SPMV_INPUT_AXI_BIND(pc02, 2); `SPMV_INPUT_AXI_BIND(pc03, 3);
  `SPMV_INPUT_AXI_BIND(pc04, 4); `SPMV_INPUT_AXI_BIND(pc05, 5);
  `SPMV_INPUT_AXI_BIND(pc06, 6); `SPMV_INPUT_AXI_BIND(pc07, 7);
  `SPMV_INPUT_AXI_BIND(pc08, 8); `SPMV_INPUT_AXI_BIND(pc09, 9);
  `SPMV_INPUT_AXI_BIND(pc10, 10); `SPMV_INPUT_AXI_BIND(pc11, 11);
  `SPMV_INPUT_AXI_BIND(pc12, 12); `SPMV_INPUT_AXI_BIND(pc13, 13);
  `SPMV_INPUT_AXI_BIND(pc14, 14); `SPMV_INPUT_AXI_BIND(pc15, 15);
  `SPMV_INPUT_AXI_BIND(pc16, 16); `SPMV_INPUT_AXI_BIND(pc17, 17);
  `SPMV_INPUT_AXI_BIND(pc18, 18);

  SpmvInputTop core (
    .clock(ap_clk), .reset(!ap_rst_n),
    .io_aRequest_0_ready(a_request_ready[0]), .io_aRequest_0_valid(a_request_valid[0]), .io_aRequest_0_bits_address(hbm_base[0] + a_offset[0]), .io_aRequest_0_bits_beats(a_beats[0]),
    .io_aRequest_1_ready(a_request_ready[1]), .io_aRequest_1_valid(a_request_valid[1]), .io_aRequest_1_bits_address(hbm_base[1] + a_offset[1]), .io_aRequest_1_bits_beats(a_beats[1]),
    .io_aRequest_2_ready(a_request_ready[2]), .io_aRequest_2_valid(a_request_valid[2]), .io_aRequest_2_bits_address(hbm_base[2] + a_offset[2]), .io_aRequest_2_bits_beats(a_beats[2]),
    .io_aRequest_3_ready(a_request_ready[3]), .io_aRequest_3_valid(a_request_valid[3]), .io_aRequest_3_bits_address(hbm_base[3] + a_offset[3]), .io_aRequest_3_bits_beats(a_beats[3]),
    .io_aRequest_4_ready(a_request_ready[4]), .io_aRequest_4_valid(a_request_valid[4]), .io_aRequest_4_bits_address(hbm_base[4] + a_offset[4]), .io_aRequest_4_bits_beats(a_beats[4]),
    .io_aRequest_5_ready(a_request_ready[5]), .io_aRequest_5_valid(a_request_valid[5]), .io_aRequest_5_bits_address(hbm_base[5] + a_offset[5]), .io_aRequest_5_bits_beats(a_beats[5]),
    .io_aRequest_6_ready(a_request_ready[6]), .io_aRequest_6_valid(a_request_valid[6]), .io_aRequest_6_bits_address(hbm_base[6] + a_offset[6]), .io_aRequest_6_bits_beats(a_beats[6]),
    .io_aRequest_7_ready(a_request_ready[7]), .io_aRequest_7_valid(a_request_valid[7]), .io_aRequest_7_bits_address(hbm_base[7] + a_offset[7]), .io_aRequest_7_bits_beats(a_beats[7]),
    .io_aRequest_8_ready(a_request_ready[8]), .io_aRequest_8_valid(a_request_valid[8]), .io_aRequest_8_bits_address(hbm_base[8] + a_offset[8]), .io_aRequest_8_bits_beats(a_beats[8]),
    .io_aRequest_9_ready(a_request_ready[9]), .io_aRequest_9_valid(a_request_valid[9]), .io_aRequest_9_bits_address(hbm_base[9] + a_offset[9]), .io_aRequest_9_bits_beats(a_beats[9]),
    .io_aRequest_10_ready(a_request_ready[10]), .io_aRequest_10_valid(a_request_valid[10]), .io_aRequest_10_bits_address(hbm_base[10] + a_offset[10]), .io_aRequest_10_bits_beats(a_beats[10]),
    .io_aRequest_11_ready(a_request_ready[11]), .io_aRequest_11_valid(a_request_valid[11]), .io_aRequest_11_bits_address(hbm_base[11] + a_offset[11]), .io_aRequest_11_bits_beats(a_beats[11]),
    .io_aRequest_12_ready(a_request_ready[12]), .io_aRequest_12_valid(a_request_valid[12]), .io_aRequest_12_bits_address(hbm_base[12] + a_offset[12]), .io_aRequest_12_bits_beats(a_beats[12]),
    .io_aRequest_13_ready(a_request_ready[13]), .io_aRequest_13_valid(a_request_valid[13]), .io_aRequest_13_bits_address(hbm_base[13] + a_offset[13]), .io_aRequest_13_bits_beats(a_beats[13]),
    .io_aRequest_14_ready(a_request_ready[14]), .io_aRequest_14_valid(a_request_valid[14]), .io_aRequest_14_bits_address(hbm_base[14] + a_offset[14]), .io_aRequest_14_bits_beats(a_beats[14]),
    .io_aRequest_15_ready(a_request_ready[15]), .io_aRequest_15_valid(a_request_valid[15]), .io_aRequest_15_bits_address(hbm_base[15] + a_offset[15]), .io_aRequest_15_bits_beats(a_beats[15]),
    .io_xRequest_0_ready(x_request_ready[0]), .io_xRequest_0_valid(x_request_valid[0]), .io_xRequest_0_bits_address(hbm_base[16] + x_offset[0]), .io_xRequest_0_bits_beats(x_beats[0]),
    .io_xRequest_1_ready(x_request_ready[1]), .io_xRequest_1_valid(x_request_valid[1]), .io_xRequest_1_bits_address(hbm_base[17] + x_offset[1]), .io_xRequest_1_bits_beats(x_beats[1]),
    .io_ctrlRequest_0_ready(ctrl_request_ready), .io_ctrlRequest_0_valid(ctrl_request_valid), .io_ctrlRequest_0_bits_address(hbm_base[18]), .io_ctrlRequest_0_bits_beats(ctrl_beats),
    `SPMV_INPUT_A_CORE_BIND(pc00, 0, 0), `SPMV_INPUT_A_CORE_BIND(pc01, 1, 1),
    `SPMV_INPUT_A_CORE_BIND(pc02, 2, 2), `SPMV_INPUT_A_CORE_BIND(pc03, 3, 3),
    `SPMV_INPUT_A_CORE_BIND(pc04, 4, 4), `SPMV_INPUT_A_CORE_BIND(pc05, 5, 5),
    `SPMV_INPUT_A_CORE_BIND(pc06, 6, 6), `SPMV_INPUT_A_CORE_BIND(pc07, 7, 7),
    `SPMV_INPUT_A_CORE_BIND(pc08, 8, 8), `SPMV_INPUT_A_CORE_BIND(pc09, 9, 9),
    `SPMV_INPUT_A_CORE_BIND(pc10, 10, 10), `SPMV_INPUT_A_CORE_BIND(pc11, 11, 11),
    `SPMV_INPUT_A_CORE_BIND(pc12, 12, 12), `SPMV_INPUT_A_CORE_BIND(pc13, 13, 13),
    `SPMV_INPUT_A_CORE_BIND(pc14, 14, 14), `SPMV_INPUT_A_CORE_BIND(pc15, 15, 15),
    `SPMV_INPUT_X_CORE_BIND(pc16, 0, 16), `SPMV_INPUT_X_CORE_BIND(pc17, 1, 17),
    .io_ctrlHbm_0_ar_ready(m_axi_pc18_arready), .io_ctrlHbm_0_ar_valid(core_axi_18_arvalid), .io_ctrlHbm_0_ar_bits_id(core_axi_18_arid), .io_ctrlHbm_0_ar_bits_addr(core_axi_18_araddr), .io_ctrlHbm_0_ar_bits_len(core_axi_18_arlen), .io_ctrlHbm_0_ar_bits_size(core_axi_18_arsize), .io_ctrlHbm_0_ar_bits_burst(core_axi_18_arburst), .io_ctrlHbm_0_ar_bits_lock(core_axi_18_arlock), .io_ctrlHbm_0_ar_bits_cache(core_axi_18_arcache), .io_ctrlHbm_0_ar_bits_prot(core_axi_18_arprot), .io_ctrlHbm_0_ar_bits_qos(core_axi_18_arqos), .io_ctrlHbm_0_r_ready(core_axi_18_rready), .io_ctrlHbm_0_r_valid(m_axi_pc18_rvalid), .io_ctrlHbm_0_r_bits_id(m_axi_pc18_rid), .io_ctrlHbm_0_r_bits_data(m_axi_pc18_rdata), .io_ctrlHbm_0_r_bits_resp(m_axi_pc18_rresp), .io_ctrlHbm_0_r_bits_last(m_axi_pc18_rlast),
    .io_xIdle_0(x_idle0), .io_xIdle_1(x_idle1), .io_mulEnable(mul_enable), .io_mulBatch(batch_index), .io_ctrlMapReady(ctrl_map_ready), .io_mulReady(mul_ready), .io_computeDone(compute_done), .io_mulError(mul_error), .io_mulProductChecksum(product_checksum)
  );
endmodule

`undef SPMV_INPUT_X_CORE_BIND
`undef SPMV_INPUT_A_CORE_BIND
`undef SPMV_INPUT_AXI_BIND
`undef SPMV_INPUT_AXI_READ_WIRES
`undef SPMV_INPUT_AXI_MASTER_PORT
