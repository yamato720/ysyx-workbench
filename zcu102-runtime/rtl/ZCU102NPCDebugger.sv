// Minimal ZCU102 NPC debugger/control block.
//
// This module is intentionally small: it provides PS-visible AXI-Lite
// registers and NPC debug/control signals. It does not instantiate the NPC CPU,
// guest memory, or trace RAM. Those blocks should be wired at the board/runtime
// top level.

module ZCU102NPCDebugger #(
  parameter int ADDR_WIDTH = 12
) (
  input  logic        clk,
  input  logic        resetn,

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

  output logic        npc_run,
  output logic        npc_reset,
  output logic        npc_halt_req,
  output logic        npc_single_step,
  output logic        npc_trace_enable,
  output logic [31:0] npc_boot_pc,

  input  logic        npc_running,
  input  logic        npc_halted,
  input  logic        npc_busy,
  input  logic        npc_trap_valid,
  input  logic [31:0] npc_trap_cause,

  input  logic        commit_valid,
  input  logic [63:0] commit_pc,
  input  logic [31:0] commit_inst,
  input  logic [4:0]  commit_rd,
  input  logic        commit_rd_wen,
  input  logic [63:0] commit_rd_wdata,

  input  logic        mmio_putch_valid,
  input  logic [7:0]  mmio_putch_data,
  input  logic        mmio_halt_valid,
  input  logic        mmio_exit_valid,
  input  logic [31:0] mmio_exit_code,

  output logic        clear_trace_pulse,
  output logic        clear_putch_pulse,
  output logic        irq_to_ps
);

  localparam logic [1:0] AXI_OKAY = 2'b00;
  localparam logic [1:0] AXI_SLVERR = 2'b10;

  localparam logic [ADDR_WIDTH-1:0] REG_CONTROL                = 'h000;
  localparam logic [ADDR_WIDTH-1:0] REG_STATUS                 = 'h004;
  localparam logic [ADDR_WIDTH-1:0] REG_BOOT_PC                = 'h008;
  localparam logic [ADDR_WIDTH-1:0] REG_EXIT_CODE              = 'h00c;
  localparam logic [ADDR_WIDTH-1:0] REG_CYCLE_LOW              = 'h010;
  localparam logic [ADDR_WIDTH-1:0] REG_CYCLE_HIGH             = 'h014;
  localparam logic [ADDR_WIDTH-1:0] REG_INSTRET_LOW            = 'h018;
  localparam logic [ADDR_WIDTH-1:0] REG_INSTRET_HIGH           = 'h01c;
  localparam logic [ADDR_WIDTH-1:0] REG_LAST_COMMIT_PC_LOW     = 'h020;
  localparam logic [ADDR_WIDTH-1:0] REG_LAST_COMMIT_PC_HIGH    = 'h024;
  localparam logic [ADDR_WIDTH-1:0] REG_LAST_COMMIT_INST       = 'h028;
  localparam logic [ADDR_WIDTH-1:0] REG_LAST_COMMIT_RD         = 'h02c;
  localparam logic [ADDR_WIDTH-1:0] REG_LAST_COMMIT_WDATA_LOW  = 'h030;
  localparam logic [ADDR_WIDTH-1:0] REG_LAST_COMMIT_WDATA_HIGH = 'h034;
  localparam logic [ADDR_WIDTH-1:0] REG_TRAP_CAUSE             = 'h038;
  localparam logic [ADDR_WIDTH-1:0] REG_TRACE_HEAD             = 'h040;
  localparam logic [ADDR_WIDTH-1:0] REG_TRACE_TAIL             = 'h044;
  localparam logic [ADDR_WIDTH-1:0] REG_TRACE_COUNT            = 'h048;
  localparam logic [ADDR_WIDTH-1:0] REG_TRACE_BASE             = 'h04c;
  localparam logic [ADDR_WIDTH-1:0] REG_TRACE_SIZE             = 'h050;
  localparam logic [ADDR_WIDTH-1:0] REG_PUTCH_DATA             = 'h060;
  localparam logic [ADDR_WIDTH-1:0] REG_PUTCH_STATUS           = 'h064;

  logic [31:0] control_q;
  logic [31:0] boot_pc_q;
  logic [31:0] exit_code_q;
  logic [63:0] cycle_q;
  logic [63:0] instret_q;
  logic [63:0] last_commit_pc_q;
  logic [31:0] last_commit_inst_q;
  logic [4:0]  last_commit_rd_q;
  logic        last_commit_rd_wen_q;
  logic [63:0] last_commit_rd_wdata_q;
  logic [31:0] trap_cause_q;
  logic [31:0] trace_head_q;
  logic [31:0] trace_tail_q;
  logic [31:0] trace_count_q;
  logic [31:0] trace_base_q;
  logic [31:0] trace_size_q;
  logic [7:0]  putch_data_q;
  logic        putch_valid_q;
  logic        halted_latch_q;
  logic        trap_latch_q;

  logic [31:0] wr_addr_q;
  logic [31:0] rd_addr_q;
  logic        write_fire;
  logic        read_fire;

  assign s_axil_awready = !s_axil_bvalid;
  assign s_axil_wready  = !s_axil_bvalid;
  assign write_fire = s_axil_awvalid && s_axil_awready && s_axil_wvalid && s_axil_wready;

  assign s_axil_arready = !s_axil_rvalid;
  assign read_fire = s_axil_arvalid && s_axil_arready;

  assign s_axil_bresp = AXI_OKAY;
  assign s_axil_rresp = AXI_OKAY;

  assign npc_run = control_q[0];
  assign npc_reset = control_q[1];
  assign npc_halt_req = control_q[2];
  assign npc_single_step = control_q[3];
  assign npc_trace_enable = control_q[4];
  assign npc_boot_pc = boot_pc_q;

  assign irq_to_ps = halted_latch_q || trap_latch_q || putch_valid_q;

  function automatic [31:0] apply_wstrb(
    input [31:0] old_value,
    input [31:0] new_value,
    input [3:0]  wstrb
  );
    automatic logic [31:0] merged;
    begin
      merged = old_value;
      for (int i = 0; i < 4; i++) begin
        if (wstrb[i]) merged[i * 8 +: 8] = new_value[i * 8 +: 8];
      end
      return merged;
    end
  endfunction

  function automatic [31:0] read_reg(input [31:0] addr);
    automatic logic [31:0] status;
    begin
      status = 32'b0;
      status[0] = npc_running;
      status[1] = npc_halted || halted_latch_q;
      status[2] = npc_busy;
      status[3] = npc_trap_valid || trap_latch_q;
      status[4] = 1'b0;
      status[5] = putch_valid_q;

      unique case (addr[ADDR_WIDTH - 1:0])
        REG_CONTROL:                read_reg = control_q;
        REG_STATUS:                 read_reg = status;
        REG_BOOT_PC:                read_reg = boot_pc_q;
        REG_EXIT_CODE:              read_reg = exit_code_q;
        REG_CYCLE_LOW:              read_reg = cycle_q[31:0];
        REG_CYCLE_HIGH:             read_reg = cycle_q[63:32];
        REG_INSTRET_LOW:            read_reg = instret_q[31:0];
        REG_INSTRET_HIGH:           read_reg = instret_q[63:32];
        REG_LAST_COMMIT_PC_LOW:     read_reg = last_commit_pc_q[31:0];
        REG_LAST_COMMIT_PC_HIGH:    read_reg = last_commit_pc_q[63:32];
        REG_LAST_COMMIT_INST:       read_reg = last_commit_inst_q;
        REG_LAST_COMMIT_RD:         read_reg = {26'b0, last_commit_rd_wen_q, last_commit_rd_q};
        REG_LAST_COMMIT_WDATA_LOW:  read_reg = last_commit_rd_wdata_q[31:0];
        REG_LAST_COMMIT_WDATA_HIGH: read_reg = last_commit_rd_wdata_q[63:32];
        REG_TRAP_CAUSE:             read_reg = trap_cause_q;
        REG_TRACE_HEAD:             read_reg = trace_head_q;
        REG_TRACE_TAIL:             read_reg = trace_tail_q;
        REG_TRACE_COUNT:            read_reg = trace_count_q;
        REG_TRACE_BASE:             read_reg = trace_base_q;
        REG_TRACE_SIZE:             read_reg = trace_size_q;
        REG_PUTCH_DATA:             read_reg = {24'b0, putch_data_q};
        REG_PUTCH_STATUS:           read_reg = {31'b0, putch_valid_q};
        default:                    read_reg = 32'b0;
      endcase
    end
  endfunction

  always_ff @(posedge clk) begin
    if (!resetn) begin
      control_q <= 32'h0000_0002;
      boot_pc_q <= 32'h8000_0000;
      exit_code_q <= 32'b0;
      cycle_q <= 64'b0;
      instret_q <= 64'b0;
      last_commit_pc_q <= 64'b0;
      last_commit_inst_q <= 32'b0;
      last_commit_rd_q <= 5'b0;
      last_commit_rd_wen_q <= 1'b0;
      last_commit_rd_wdata_q <= 64'b0;
      trap_cause_q <= 32'b0;
      trace_head_q <= 32'b0;
      trace_tail_q <= 32'b0;
      trace_count_q <= 32'b0;
      trace_base_q <= 32'b0;
      trace_size_q <= 32'b0;
      putch_data_q <= 8'b0;
      putch_valid_q <= 1'b0;
      halted_latch_q <= 1'b0;
      trap_latch_q <= 1'b0;
      clear_trace_pulse <= 1'b0;
      clear_putch_pulse <= 1'b0;
      s_axil_bvalid <= 1'b0;
      s_axil_rvalid <= 1'b0;
      s_axil_rdata <= 32'b0;
      wr_addr_q <= 32'b0;
      rd_addr_q <= 32'b0;
    end else begin
      clear_trace_pulse <= 1'b0;
      clear_putch_pulse <= 1'b0;

      cycle_q <= cycle_q + 64'd1;

      if (commit_valid) begin
        instret_q <= instret_q + 64'd1;
        last_commit_pc_q <= commit_pc;
        last_commit_inst_q <= commit_inst;
        last_commit_rd_q <= commit_rd;
        last_commit_rd_wen_q <= commit_rd_wen;
        last_commit_rd_wdata_q <= commit_rd_wdata;
      end

      if (npc_trap_valid) begin
        trap_latch_q <= 1'b1;
        trap_cause_q <= npc_trap_cause;
      end

      if (npc_halted || mmio_halt_valid) begin
        halted_latch_q <= 1'b1;
      end

      if (mmio_exit_valid) begin
        exit_code_q <= mmio_exit_code;
      end

      if (mmio_putch_valid) begin
        putch_data_q <= mmio_putch_data;
        putch_valid_q <= 1'b1;
      end

      if (write_fire) begin
        wr_addr_q <= s_axil_awaddr;
        unique case (s_axil_awaddr[ADDR_WIDTH - 1:0])
          REG_CONTROL: begin
            control_q <= apply_wstrb(control_q, s_axil_wdata, s_axil_wstrb);
            if (s_axil_wdata[5]) begin
              trace_head_q <= 32'b0;
              trace_tail_q <= 32'b0;
              trace_count_q <= 32'b0;
              clear_trace_pulse <= 1'b1;
            end
            if (s_axil_wdata[6]) begin
              putch_valid_q <= 1'b0;
              clear_putch_pulse <= 1'b1;
            end
            if (s_axil_wdata[7]) begin
              halted_latch_q <= 1'b0;
              trap_latch_q <= 1'b0;
            end
          end
          REG_BOOT_PC:     boot_pc_q <= apply_wstrb(boot_pc_q, s_axil_wdata, s_axil_wstrb);
          REG_EXIT_CODE:   exit_code_q <= apply_wstrb(exit_code_q, s_axil_wdata, s_axil_wstrb);
          REG_TRACE_HEAD:  trace_head_q <= apply_wstrb(trace_head_q, s_axil_wdata, s_axil_wstrb);
          REG_TRACE_TAIL:  trace_tail_q <= apply_wstrb(trace_tail_q, s_axil_wdata, s_axil_wstrb);
          REG_TRACE_COUNT: trace_count_q <= apply_wstrb(trace_count_q, s_axil_wdata, s_axil_wstrb);
          REG_TRACE_BASE:  trace_base_q <= apply_wstrb(trace_base_q, s_axil_wdata, s_axil_wstrb);
          REG_TRACE_SIZE:  trace_size_q <= apply_wstrb(trace_size_q, s_axil_wdata, s_axil_wstrb);
          default: begin
          end
        endcase
        s_axil_bvalid <= 1'b1;
      end else if (s_axil_bvalid && s_axil_bready) begin
        s_axil_bvalid <= 1'b0;
      end

      if (read_fire) begin
        rd_addr_q <= s_axil_araddr;
        s_axil_rdata <= read_reg(s_axil_araddr);
        s_axil_rvalid <= 1'b1;
        if (s_axil_araddr[ADDR_WIDTH - 1:0] == REG_PUTCH_DATA) begin
          putch_valid_q <= 1'b0;
        end
      end else if (s_axil_rvalid && s_axil_rready) begin
        s_axil_rvalid <= 1'b0;
      end
    end
  end

endmodule
