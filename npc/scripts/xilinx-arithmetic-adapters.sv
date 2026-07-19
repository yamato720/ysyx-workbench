// Stable NPC arithmetic adapter ports.  The Chisel BlackBoxes bind to these
// modules; only this file knows Vivado's generated IP port names.
//
// Vivado 2022.2 FPO cannot provide all RV32F/RV64F-visible behavior: it has
// no dynamic RISC-V rounding input (and no RMM), no NX status, and no unsigned
// float-to-integer conversion. NpcBuildConfig therefore rejects
// NPC_ARITH_BACKEND=ip when NPC_F=1. The floating adapters remain here for
// Vivado ABI and timing integration checks; they are not an ISA-compliant F
// execution backend.

module npc_arithmetic_meta_fifo #(
  parameter integer WIDTH = 32,
  parameter integer TAG_WIDTH = 4,
  parameter integer DEPTH = (1 << TAG_WIDTH)
) (
  input  wire                 clock,
  input  wire                 reset,
  input  wire                 push,
  input  wire [TAG_WIDTH-1:0] push_tag,
  input  wire [4:0]           push_op,
  input  wire [WIDTH-1:0]     push_a,
  input  wire [WIDTH-1:0]     push_b,
  input  wire                 pop,
  output wire                 full,
  output wire                 empty,
  output wire [TAG_WIDTH-1:0] head_tag,
  output wire [4:0]           head_op,
  output wire [WIDTH-1:0]     head_a,
  output wire [WIDTH-1:0]     head_b
);
  localparam integer COUNT_WIDTH = TAG_WIDTH + 1;
  reg [TAG_WIDTH-1:0] write_ptr;
  reg [TAG_WIDTH-1:0] read_ptr;
  reg [COUNT_WIDTH-1:0] count;
  reg [TAG_WIDTH-1:0] tag_mem [0:DEPTH-1];
  reg [4:0] op_mem [0:DEPTH-1];
  reg [WIDTH-1:0] a_mem [0:DEPTH-1];
  reg [WIDTH-1:0] b_mem [0:DEPTH-1];

  wire push_fire = push && !full;
  wire pop_fire = pop && !empty;
  assign full = count == DEPTH;
  assign empty = count == 0;
  assign head_tag = tag_mem[read_ptr];
  assign head_op = op_mem[read_ptr];
  assign head_a = a_mem[read_ptr];
  assign head_b = b_mem[read_ptr];

  always @(posedge clock) begin
    if (reset) begin
      write_ptr <= 0;
      read_ptr <= 0;
      count <= 0;
    end else begin
      if (push_fire) begin
        tag_mem[write_ptr] <= push_tag;
        op_mem[write_ptr] <= push_op;
        a_mem[write_ptr] <= push_a;
        b_mem[write_ptr] <= push_b;
        write_ptr <= write_ptr + 1'b1;
      end
      if (pop_fire) begin
        read_ptr <= read_ptr + 1'b1;
      end
      case ({push_fire, pop_fire})
        2'b10: count <= count + 1'b1;
        2'b01: count <= count - 1'b1;
        default: count <= count;
      endcase
    end
  end
endmodule

module npc_fp_addsub_adapter #(
  parameter integer WIDTH = 32,
  parameter integer TAG_WIDTH = 4,
  parameter integer LATENCY = 3
) (
  input  wire                 clock,
  input  wire                 reset,
  output wire                 arithmetic_req_ready,
  input  wire                 arithmetic_req_valid,
  input  wire [WIDTH-1:0]     arithmetic_req_bits_operandA,
  input  wire [WIDTH-1:0]     arithmetic_req_bits_operandB,
  input  wire [WIDTH-1:0]     arithmetic_req_bits_operandC,
  input  wire [4:0]           arithmetic_req_bits_op,
  input  wire [2:0]           arithmetic_req_bits_roundingMode,
  input  wire [TAG_WIDTH-1:0] arithmetic_req_bits_tag,
  input  wire                 arithmetic_resp_ready,
  output wire                 arithmetic_resp_valid,
  output wire [WIDTH-1:0]     arithmetic_resp_bits_result,
  output wire [4:0]           arithmetic_resp_bits_exceptionFlags,
  output wire [TAG_WIDTH-1:0] arithmetic_resp_bits_tag
);
  wire a_ready, b_ready, operation_ready, result_valid;
  wire [31:0] result_data;
  wire [2:0] result_user;
  wire meta_full, meta_empty;
  wire [TAG_WIDTH-1:0] meta_tag;
  wire [4:0] meta_op;
  wire [WIDTH-1:0] unused_a, unused_b;
  wire request_fire;

  assign arithmetic_req_ready = !meta_full && a_ready && b_ready && operation_ready;
  assign request_fire = arithmetic_req_valid && arithmetic_req_ready;
  assign arithmetic_resp_valid = result_valid && !meta_empty;
  assign arithmetic_resp_bits_result = result_data;
  assign arithmetic_resp_bits_tag = meta_tag;
  // FPO status is {invalid, overflow, underflow}; RV FFLAGS is
  // {NV, DZ, OF, UF, NX}. The FPO has no inexact-status output.
  assign arithmetic_resp_bits_exceptionFlags = {result_user[2], 1'b0, result_user[1], result_user[0], 1'b0};

  npc_arithmetic_meta_fifo #(.WIDTH(WIDTH), .TAG_WIDTH(TAG_WIDTH)) metadata (
    .clock(clock), .reset(reset), .push(request_fire), .push_tag(arithmetic_req_bits_tag),
    .push_op(arithmetic_req_bits_op), .push_a(arithmetic_req_bits_operandA),
    .push_b(arithmetic_req_bits_operandB),
    .pop(arithmetic_resp_valid && arithmetic_resp_ready), .full(meta_full), .empty(meta_empty),
    .head_tag(meta_tag), .head_op(meta_op), .head_a(unused_a), .head_b(unused_b)
  );

  npc_fp_addsub_ip ip (
    .aclk(clock), .aresetn(!reset),
    .s_axis_a_tvalid(arithmetic_req_valid && !meta_full), .s_axis_a_tready(a_ready),
    .s_axis_a_tdata(arithmetic_req_bits_operandA[31:0]),
    .s_axis_b_tvalid(arithmetic_req_valid && !meta_full), .s_axis_b_tready(b_ready),
    .s_axis_b_tdata(arithmetic_req_bits_operandB[31:0]),
    .s_axis_operation_tvalid(arithmetic_req_valid && !meta_full), .s_axis_operation_tready(operation_ready),
    .s_axis_operation_tdata(arithmetic_req_bits_op == 5'h1a ? 8'h01 : 8'h00),
    .m_axis_result_tvalid(result_valid), .m_axis_result_tready(arithmetic_resp_ready && !meta_empty),
    .m_axis_result_tdata(result_data), .m_axis_result_tuser(result_user)
  );
endmodule

module npc_fp_multiplier_adapter #(
  parameter integer WIDTH = 32,
  parameter integer TAG_WIDTH = 4,
  parameter integer LATENCY = 4
) (
  input wire clock, input wire reset,
  output wire arithmetic_req_ready, input wire arithmetic_req_valid,
  input wire [WIDTH-1:0] arithmetic_req_bits_operandA, input wire [WIDTH-1:0] arithmetic_req_bits_operandB,
  input wire [WIDTH-1:0] arithmetic_req_bits_operandC,
  input wire [4:0] arithmetic_req_bits_op, input wire [2:0] arithmetic_req_bits_roundingMode,
  input wire [TAG_WIDTH-1:0] arithmetic_req_bits_tag, input wire arithmetic_resp_ready,
  output wire arithmetic_resp_valid, output wire [WIDTH-1:0] arithmetic_resp_bits_result,
  output wire [4:0] arithmetic_resp_bits_exceptionFlags, output wire [TAG_WIDTH-1:0] arithmetic_resp_bits_tag
);
  wire a_ready, b_ready, result_valid, meta_full, meta_empty;
  wire [31:0] result_data;
  wire [2:0] result_user;
  wire [TAG_WIDTH-1:0] meta_tag;
  wire [4:0] unused_op;
  wire [WIDTH-1:0] unused_a, unused_b;
  wire request_fire = arithmetic_req_valid && arithmetic_req_ready;
  assign arithmetic_req_ready = !meta_full && a_ready && b_ready;
  assign arithmetic_resp_valid = result_valid && !meta_empty;
  assign arithmetic_resp_bits_result = result_data;
  assign arithmetic_resp_bits_tag = meta_tag;
  assign arithmetic_resp_bits_exceptionFlags = {result_user[2], 1'b0, result_user[1], result_user[0], 1'b0};
  npc_arithmetic_meta_fifo #(.WIDTH(WIDTH), .TAG_WIDTH(TAG_WIDTH)) metadata (
    .clock(clock), .reset(reset), .push(request_fire), .push_tag(arithmetic_req_bits_tag),
    .push_op(arithmetic_req_bits_op), .push_a(arithmetic_req_bits_operandA), .push_b(arithmetic_req_bits_operandB),
    .pop(arithmetic_resp_valid && arithmetic_resp_ready), .full(meta_full), .empty(meta_empty),
    .head_tag(meta_tag), .head_op(unused_op), .head_a(unused_a), .head_b(unused_b)
  );
  npc_fp_multiplier_ip ip (
    .aclk(clock), .aresetn(!reset),
    .s_axis_a_tvalid(arithmetic_req_valid && !meta_full), .s_axis_a_tready(a_ready),
    .s_axis_a_tdata(arithmetic_req_bits_operandA[31:0]),
    .s_axis_b_tvalid(arithmetic_req_valid && !meta_full), .s_axis_b_tready(b_ready),
    .s_axis_b_tdata(arithmetic_req_bits_operandB[31:0]),
    .m_axis_result_tvalid(result_valid), .m_axis_result_tready(arithmetic_resp_ready && !meta_empty),
    .m_axis_result_tdata(result_data), .m_axis_result_tuser(result_user)
  );
endmodule

module npc_fp_divider_adapter #(
  parameter integer WIDTH = 32,
  parameter integer TAG_WIDTH = 4,
  parameter integer LATENCY = 29
) (
  input wire clock, input wire reset,
  output wire arithmetic_req_ready, input wire arithmetic_req_valid,
  input wire [WIDTH-1:0] arithmetic_req_bits_operandA, input wire [WIDTH-1:0] arithmetic_req_bits_operandB,
  input wire [WIDTH-1:0] arithmetic_req_bits_operandC,
  input wire [4:0] arithmetic_req_bits_op, input wire [2:0] arithmetic_req_bits_roundingMode,
  input wire [TAG_WIDTH-1:0] arithmetic_req_bits_tag, input wire arithmetic_resp_ready,
  output wire arithmetic_resp_valid, output wire [WIDTH-1:0] arithmetic_resp_bits_result,
  output wire [4:0] arithmetic_resp_bits_exceptionFlags, output wire [TAG_WIDTH-1:0] arithmetic_resp_bits_tag
);
  wire a_ready, b_ready, result_valid, meta_full, meta_empty;
  wire [31:0] result_data;
  wire [3:0] result_user;
  wire [TAG_WIDTH-1:0] meta_tag;
  wire [4:0] unused_op;
  wire [WIDTH-1:0] unused_a, unused_b;
  wire request_fire = arithmetic_req_valid && arithmetic_req_ready;
  assign arithmetic_req_ready = !meta_full && a_ready && b_ready;
  assign arithmetic_resp_valid = result_valid && !meta_empty;
  assign arithmetic_resp_bits_result = result_data;
  assign arithmetic_resp_bits_tag = meta_tag;
  // FPO divider status is {invalid, divide-by-zero, overflow, underflow}.
  assign arithmetic_resp_bits_exceptionFlags = {result_user[3], result_user[2], result_user[1], result_user[0], 1'b0};
  npc_arithmetic_meta_fifo #(.WIDTH(WIDTH), .TAG_WIDTH(TAG_WIDTH)) metadata (
    .clock(clock), .reset(reset), .push(request_fire), .push_tag(arithmetic_req_bits_tag),
    .push_op(arithmetic_req_bits_op), .push_a(arithmetic_req_bits_operandA), .push_b(arithmetic_req_bits_operandB),
    .pop(arithmetic_resp_valid && arithmetic_resp_ready), .full(meta_full), .empty(meta_empty),
    .head_tag(meta_tag), .head_op(unused_op), .head_a(unused_a), .head_b(unused_b)
  );
  npc_fp_divider_ip ip (
    .aclk(clock), .aresetn(!reset),
    .s_axis_a_tvalid(arithmetic_req_valid && !meta_full), .s_axis_a_tready(a_ready),
    .s_axis_a_tdata(arithmetic_req_bits_operandA[31:0]),
    .s_axis_b_tvalid(arithmetic_req_valid && !meta_full), .s_axis_b_tready(b_ready),
    .s_axis_b_tdata(arithmetic_req_bits_operandB[31:0]),
    .m_axis_result_tvalid(result_valid), .m_axis_result_tready(arithmetic_resp_ready && !meta_empty),
    .m_axis_result_tdata(result_data), .m_axis_result_tuser(result_user)
  );
endmodule

module npc_fp_fma_adapter #(
  parameter integer WIDTH = 32,
  parameter integer TAG_WIDTH = 4,
  parameter integer LATENCY = 4
) (
  input wire clock, input wire reset,
  output wire arithmetic_req_ready, input wire arithmetic_req_valid,
  input wire [WIDTH-1:0] arithmetic_req_bits_operandA,
  input wire [WIDTH-1:0] arithmetic_req_bits_operandB,
  input wire [WIDTH-1:0] arithmetic_req_bits_operandC,
  input wire [4:0] arithmetic_req_bits_op,
  input wire [2:0] arithmetic_req_bits_roundingMode,
  input wire [TAG_WIDTH-1:0] arithmetic_req_bits_tag,
  input wire arithmetic_resp_ready,
  output wire arithmetic_resp_valid,
  output wire [WIDTH-1:0] arithmetic_resp_bits_result,
  output wire [4:0] arithmetic_resp_bits_exceptionFlags,
  output wire [TAG_WIDTH-1:0] arithmetic_resp_bits_tag
);
  localparam [4:0] FMADD = 5'h05, FMSUB = 5'h06, FNMSUB = 5'h07, FNMADD = 5'h08;
  wire a_ready, b_ready, c_ready, operation_ready, result_valid, meta_full, meta_empty;
  wire [31:0] result_data;
  wire [2:0] result_user;
  wire [TAG_WIDTH-1:0] meta_tag;
  wire [4:0] unused_op;
  wire [WIDTH-1:0] unused_a, unused_b;
  wire request_fire = arithmetic_req_valid && arithmetic_req_ready;
  wire negate_product = arithmetic_req_bits_op == FNMSUB || arithmetic_req_bits_op == FNMADD;
  wire subtract_c = arithmetic_req_bits_op == FMSUB || arithmetic_req_bits_op == FNMADD;
  wire [31:0] fma_a = arithmetic_req_bits_operandA[31:0] ^ {negate_product, 31'b0};

  assign arithmetic_req_ready = !meta_full && a_ready && b_ready && c_ready && operation_ready;
  assign arithmetic_resp_valid = result_valid && !meta_empty;
  assign arithmetic_resp_bits_result = result_data;
  assign arithmetic_resp_bits_tag = meta_tag;
  assign arithmetic_resp_bits_exceptionFlags = {result_user[2], 1'b0, result_user[1], result_user[0], 1'b0};

  npc_arithmetic_meta_fifo #(.WIDTH(WIDTH), .TAG_WIDTH(TAG_WIDTH)) metadata (
    .clock(clock), .reset(reset), .push(request_fire), .push_tag(arithmetic_req_bits_tag),
    .push_op(arithmetic_req_bits_op), .push_a(arithmetic_req_bits_operandA), .push_b(arithmetic_req_bits_operandB),
    .pop(arithmetic_resp_valid && arithmetic_resp_ready), .full(meta_full), .empty(meta_empty),
    .head_tag(meta_tag), .head_op(unused_op), .head_a(unused_a), .head_b(unused_b)
  );

  // FPO encodes FMA/FMS as add/subtract on C. Negating A produces the two
  // RISC-V negative-product forms without losing single-rounding semantics.
  npc_fp_fma_ip ip (
    .aclk(clock), .aresetn(!reset),
    .s_axis_a_tvalid(arithmetic_req_valid && !meta_full), .s_axis_a_tready(a_ready),
    .s_axis_a_tdata(fma_a),
    .s_axis_b_tvalid(arithmetic_req_valid && !meta_full), .s_axis_b_tready(b_ready),
    .s_axis_b_tdata(arithmetic_req_bits_operandB[31:0]),
    .s_axis_c_tvalid(arithmetic_req_valid && !meta_full), .s_axis_c_tready(c_ready),
    .s_axis_c_tdata(arithmetic_req_bits_operandC[31:0]),
    .s_axis_operation_tvalid(arithmetic_req_valid && !meta_full), .s_axis_operation_tready(operation_ready),
    .s_axis_operation_tdata(subtract_c ? 8'h01 : 8'h00),
    .m_axis_result_tvalid(result_valid), .m_axis_result_tready(arithmetic_resp_ready && !meta_empty),
    .m_axis_result_tdata(result_data), .m_axis_result_tuser(result_user)
  );
endmodule

module npc_fp_sqrt_adapter #(
  parameter integer WIDTH = 32,
  parameter integer TAG_WIDTH = 4,
  parameter integer LATENCY = 29
) (
  input wire clock, input wire reset,
  output wire arithmetic_req_ready, input wire arithmetic_req_valid,
  input wire [WIDTH-1:0] arithmetic_req_bits_operandA,
  input wire [WIDTH-1:0] arithmetic_req_bits_operandB,
  input wire [WIDTH-1:0] arithmetic_req_bits_operandC,
  input wire [4:0] arithmetic_req_bits_op,
  input wire [2:0] arithmetic_req_bits_roundingMode,
  input wire [TAG_WIDTH-1:0] arithmetic_req_bits_tag,
  input wire arithmetic_resp_ready,
  output wire arithmetic_resp_valid,
  output wire [WIDTH-1:0] arithmetic_resp_bits_result,
  output wire [4:0] arithmetic_resp_bits_exceptionFlags,
  output wire [TAG_WIDTH-1:0] arithmetic_resp_bits_tag
);
  wire a_ready, result_valid, meta_full, meta_empty;
  wire [31:0] result_data;
  wire result_user;
  wire [TAG_WIDTH-1:0] meta_tag;
  wire [4:0] unused_op;
  wire [WIDTH-1:0] unused_a, unused_b;
  wire request_fire = arithmetic_req_valid && arithmetic_req_ready;

  assign arithmetic_req_ready = !meta_full && a_ready;
  assign arithmetic_resp_valid = result_valid && !meta_empty;
  assign arithmetic_resp_bits_result = result_data;
  assign arithmetic_resp_bits_tag = meta_tag;
  assign arithmetic_resp_bits_exceptionFlags = {result_user, 4'b0};

  npc_arithmetic_meta_fifo #(.WIDTH(WIDTH), .TAG_WIDTH(TAG_WIDTH)) metadata (
    .clock(clock), .reset(reset), .push(request_fire), .push_tag(arithmetic_req_bits_tag),
    .push_op(arithmetic_req_bits_op), .push_a(arithmetic_req_bits_operandA), .push_b(arithmetic_req_bits_operandB),
    .pop(arithmetic_resp_valid && arithmetic_resp_ready), .full(meta_full), .empty(meta_empty),
    .head_tag(meta_tag), .head_op(unused_op), .head_a(unused_a), .head_b(unused_b)
  );
  npc_fp_sqrt_ip ip (
    .aclk(clock), .aresetn(!reset),
    .s_axis_a_tvalid(arithmetic_req_valid && !meta_full), .s_axis_a_tready(a_ready),
    .s_axis_a_tdata(arithmetic_req_bits_operandA[31:0]),
    .m_axis_result_tvalid(result_valid), .m_axis_result_tready(arithmetic_resp_ready && !meta_empty),
    .m_axis_result_tdata(result_data), .m_axis_result_tuser(result_user)
  );
endmodule

module npc_fp_compare_adapter #(
  parameter integer WIDTH = 32,
  parameter integer TAG_WIDTH = 4,
  parameter integer LATENCY = 3
) (
  input wire clock, input wire reset,
  output wire arithmetic_req_ready, input wire arithmetic_req_valid,
  input wire [WIDTH-1:0] arithmetic_req_bits_operandA,
  input wire [WIDTH-1:0] arithmetic_req_bits_operandB,
  input wire [WIDTH-1:0] arithmetic_req_bits_operandC,
  input wire [4:0] arithmetic_req_bits_op,
  input wire [2:0] arithmetic_req_bits_roundingMode,
  input wire [TAG_WIDTH-1:0] arithmetic_req_bits_tag,
  input wire arithmetic_resp_ready,
  output wire arithmetic_resp_valid,
  output wire [WIDTH-1:0] arithmetic_resp_bits_result,
  output wire [4:0] arithmetic_resp_bits_exceptionFlags,
  output wire [TAG_WIDTH-1:0] arithmetic_resp_bits_tag
);
  localparam [4:0] FSGNJ = 5'h09, FSGNJN = 5'h0a, FSGNJX = 5'h0b;
  localparam [4:0] FMIN = 5'h0c, FMAX = 5'h0d, FEQ = 5'h0e, FLT = 5'h0f, FLE = 5'h10;
  localparam [4:0] FMV_X_W = 5'h19, FCLASS = 5'h1a, FMV_W_X = 5'h1b;
  localparam [7:0] FPO_LESS_THAN = 8'h0c, FPO_EQUAL = 8'h14, FPO_LESS_EQUAL = 8'h1c;
  wire a_ready, b_ready, operation_ready, result_valid, meta_full, meta_empty;
  wire [7:0] result_data;
  wire ignored_invalid;
  wire [TAG_WIDTH-1:0] meta_tag;
  wire [4:0] meta_op;
  wire [WIDTH-1:0] meta_a, meta_b;
  wire request_fire = arithmetic_req_valid && arithmetic_req_ready;

  function automatic is_nan;
    input [31:0] value;
    begin is_nan = (&value[30:23]) && (|value[22:0]); end
  endfunction
  function automatic is_snan;
    input [31:0] value;
    begin is_snan = is_nan(value) && !value[22]; end
  endfunction
  function automatic float_less;
    input [31:0] a;
    input [31:0] b;
    begin
      if (((a | b) << 1) == 0) float_less = 1'b0;
      else if (a[31] != b[31]) float_less = a[31];
      else if (a[31]) float_less = a[30:0] > b[30:0];
      else float_less = a[30:0] < b[30:0];
    end
  endfunction
  function automatic [31:0] minmax;
    input [31:0] a;
    input [31:0] b;
    input maximum;
    begin
      if (is_nan(a) || is_nan(b)) begin
        if (is_nan(a) && is_nan(b)) minmax = 32'h7fc00000;
        else minmax = is_nan(a) ? b : a;
      end else if (((a | b) << 1) == 0) begin
        minmax = maximum ? (a & b) : (a | b);
      end else if (float_less(a, b)) begin
        minmax = maximum ? b : a;
      end else begin
        minmax = maximum ? a : b;
      end
    end
  endfunction
  function automatic [31:0] fclass;
    input [31:0] value;
    begin
      if (&value[30:23]) begin
        if (!(|value[22:0])) fclass = value[31] ? 32'h00000001 : 32'h00000080;
        else fclass = is_snan(value) ? 32'h00000100 : 32'h00000200;
      end else if (!(|value[30:23])) begin
        if (!(|value[22:0])) fclass = value[31] ? 32'h00000008 : 32'h00000010;
        else fclass = value[31] ? 32'h00000004 : 32'h00000020;
      end else begin
        fclass = value[31] ? 32'h00000002 : 32'h00000040;
      end
    end
  endfunction
  function automatic [WIDTH-1:0] helper_result;
    input [4:0] op;
    input [WIDTH-1:0] a;
    input [WIDTH-1:0] b;
    reg [31:0] a32, b32;
    begin
      a32 = a[31:0];
      b32 = b[31:0];
      helper_result = {{WIDTH{1'b0}}};
      case (op)
        FSGNJ: helper_result = {b32[31], a32[30:0]};
        FSGNJN: helper_result = {~b32[31], a32[30:0]};
        FSGNJX: helper_result = {a32[31] ^ b32[31], a32[30:0]};
        FMIN: helper_result = minmax(a32, b32, 1'b0);
        FMAX: helper_result = minmax(a32, b32, 1'b1);
        FMV_X_W: begin
          if (WIDTH == 64) helper_result = {{32{a32[31]}}, a32};
          else helper_result = a32;
        end
        FCLASS: helper_result = fclass(a32);
        FMV_W_X: helper_result = a32;
        default: helper_result = {{WIDTH{1'b0}}};
      endcase
    end
  endfunction
  function automatic [4:0] architectural_flags;
    input [4:0] op;
    input [WIDTH-1:0] a;
    input [WIDTH-1:0] b;
    reg any_nan;
    reg any_snan;
    begin
      any_nan = is_nan(a[31:0]) || is_nan(b[31:0]);
      any_snan = is_snan(a[31:0]) || is_snan(b[31:0]);
      architectural_flags = 5'b0;
      if (op == FMIN || op == FMAX) architectural_flags = any_snan ? 5'h10 : 5'b0;
      else if (op == FEQ) architectural_flags = any_snan ? 5'h10 : 5'b0;
      else if (op == FLT || op == FLE) architectural_flags = any_nan ? 5'h10 : 5'b0;
    end
  endfunction
  function automatic [7:0] fpo_operation;
    input [4:0] op;
    begin
      if (op == FLT) fpo_operation = FPO_LESS_THAN;
      else if (op == FLE) fpo_operation = FPO_LESS_EQUAL;
      else fpo_operation = FPO_EQUAL;
    end
  endfunction

  assign arithmetic_req_ready = !meta_full && a_ready && b_ready && operation_ready;
  assign arithmetic_resp_valid = result_valid && !meta_empty;
  assign arithmetic_resp_bits_tag = meta_tag;
  assign arithmetic_resp_bits_exceptionFlags = architectural_flags(meta_op, meta_a, meta_b);
  assign arithmetic_resp_bits_result = (meta_op == FEQ || meta_op == FLT || meta_op == FLE) ?
    {{(WIDTH - 1){1'b0}}, result_data[0]} : helper_result(meta_op, meta_a, meta_b);

  npc_arithmetic_meta_fifo #(.WIDTH(WIDTH), .TAG_WIDTH(TAG_WIDTH)) metadata (
    .clock(clock), .reset(reset), .push(request_fire), .push_tag(arithmetic_req_bits_tag),
    .push_op(arithmetic_req_bits_op), .push_a(arithmetic_req_bits_operandA), .push_b(arithmetic_req_bits_operandB),
    .pop(arithmetic_resp_valid && arithmetic_resp_ready), .full(meta_full), .empty(meta_empty),
    .head_tag(meta_tag), .head_op(meta_op), .head_a(meta_a), .head_b(meta_b)
  );

  // Every request traverses the fixed-latency FPO compare core.  FSGNJ,
  // move, class and min/max replace the numeric result with exact local logic
  // at retirement, while FEQ/FLT/FLE consume the configured comparator.
  npc_fp_compare_ip ip (
    .aclk(clock), .aresetn(!reset),
    .s_axis_a_tvalid(arithmetic_req_valid && !meta_full), .s_axis_a_tready(a_ready),
    .s_axis_a_tdata(arithmetic_req_bits_operandA[31:0]),
    .s_axis_b_tvalid(arithmetic_req_valid && !meta_full), .s_axis_b_tready(b_ready),
    .s_axis_b_tdata(arithmetic_req_bits_operandB[31:0]),
    .s_axis_operation_tvalid(arithmetic_req_valid && !meta_full), .s_axis_operation_tready(operation_ready),
    .s_axis_operation_tdata(fpo_operation(arithmetic_req_bits_op)),
    .m_axis_result_tvalid(result_valid), .m_axis_result_tready(arithmetic_resp_ready && !meta_empty),
    .m_axis_result_tdata(result_data), .m_axis_result_tuser(ignored_invalid)
  );
endmodule

module npc_fp_convert_adapter #(
  parameter integer WIDTH = 32,
  parameter integer TAG_WIDTH = 4,
  parameter integer LATENCY = 4
) (
  input wire clock, input wire reset,
  output wire arithmetic_req_ready, input wire arithmetic_req_valid,
  input wire [WIDTH-1:0] arithmetic_req_bits_operandA,
  input wire [WIDTH-1:0] arithmetic_req_bits_operandB,
  input wire [WIDTH-1:0] arithmetic_req_bits_operandC,
  input wire [4:0] arithmetic_req_bits_op,
  input wire [2:0] arithmetic_req_bits_roundingMode,
  input wire [TAG_WIDTH-1:0] arithmetic_req_bits_tag,
  input wire arithmetic_resp_ready,
  output wire arithmetic_resp_valid,
  output wire [WIDTH-1:0] arithmetic_resp_bits_result,
  output wire [4:0] arithmetic_resp_bits_exceptionFlags,
  output wire [TAG_WIDTH-1:0] arithmetic_resp_bits_tag
);
  localparam [4:0] FCVT_W = 5'h11, FCVT_WU = 5'h12, FCVT_L = 5'h13, FCVT_LU = 5'h14;
  localparam [4:0] FCVT_S_W = 5'h15, FCVT_S_WU = 5'h16, FCVT_S_L = 5'h17, FCVT_S_LU = 5'h18;
  wire meta_full, meta_empty;
  wire [TAG_WIDTH-1:0] meta_tag;
  wire [4:0] meta_op;
  wire [WIDTH-1:0] unused_a, unused_b;
  wire f2i32_ready, f2i64_ready, i32f_ready, ui32f_ready, i64f_ready, ui64f_ready;
  wire f2i32_valid, f2i64_valid, i32f_valid, ui32f_valid, i64f_valid, ui64f_valid;
  wire [31:0] f2i32_result, i32f_result, ui32f_result, i64f_result, ui64f_result;
  wire [63:0] f2i64_result;
  wire f2i32_invalid, f2i64_invalid;
  wire is_f2i32_request = arithmetic_req_bits_op == FCVT_W;
  wire is_f2i64_request = arithmetic_req_bits_op == FCVT_WU ||
    arithmetic_req_bits_op == FCVT_L || arithmetic_req_bits_op == FCVT_LU;
  wire is_i32f_request = arithmetic_req_bits_op == FCVT_S_W;
  wire is_ui32f_request = arithmetic_req_bits_op == FCVT_S_WU;
  wire is_i64f_request = arithmetic_req_bits_op == FCVT_S_L;
  wire is_ui64f_request = arithmetic_req_bits_op == FCVT_S_LU;
  wire selected_ready = is_f2i32_request ? f2i32_ready :
    is_f2i64_request ? f2i64_ready :
    is_i32f_request ? i32f_ready :
    is_ui32f_request ? ui32f_ready :
    is_i64f_request ? i64f_ready : ui64f_ready;
  wire request_fire = arithmetic_req_valid && arithmetic_req_ready;
  wire response_from_f2i32 = meta_op == FCVT_W;
  wire response_from_f2i64 = meta_op == FCVT_WU || meta_op == FCVT_L || meta_op == FCVT_LU;
  wire response_from_i32f = meta_op == FCVT_S_W;
  wire response_from_ui32f = meta_op == FCVT_S_WU;
  wire response_from_i64f = meta_op == FCVT_S_L;
  wire response_from_ui64f = meta_op == FCVT_S_LU;
  wire selected_valid = response_from_f2i32 ? f2i32_valid :
    response_from_f2i64 ? f2i64_valid :
    response_from_i32f ? i32f_valid :
    response_from_ui32f ? ui32f_valid :
    response_from_i64f ? i64f_valid : ui64f_valid;
  wire selected_invalid = response_from_f2i32 ? f2i32_invalid : f2i64_invalid;
  reg [WIDTH-1:0] selected_result;

  always @* begin
    selected_result = {WIDTH{1'b0}};
    case (meta_op)
      FCVT_W: begin
        if (WIDTH == 64) selected_result = {{32{f2i32_result[31]}}, f2i32_result};
        else selected_result = f2i32_result;
      end
      FCVT_WU: selected_result = f2i64_result[31:0];
      FCVT_L, FCVT_LU: selected_result = f2i64_result;
      FCVT_S_W: selected_result = i32f_result;
      FCVT_S_WU: selected_result = ui32f_result;
      FCVT_S_L: selected_result = i64f_result;
      FCVT_S_LU: selected_result = ui64f_result;
      default: selected_result = {WIDTH{1'b0}};
    endcase
  end

  assign arithmetic_req_ready = !meta_full && selected_ready;
  assign arithmetic_resp_valid = selected_valid && !meta_empty;
  assign arithmetic_resp_bits_result = selected_result;
  assign arithmetic_resp_bits_tag = meta_tag;
  assign arithmetic_resp_bits_exceptionFlags = response_from_f2i32 || response_from_f2i64 ?
    {selected_invalid, 4'b0} : 5'b0;

  npc_arithmetic_meta_fifo #(.WIDTH(WIDTH), .TAG_WIDTH(TAG_WIDTH)) metadata (
    .clock(clock), .reset(reset), .push(request_fire), .push_tag(arithmetic_req_bits_tag),
    .push_op(arithmetic_req_bits_op), .push_a(arithmetic_req_bits_operandA), .push_b(arithmetic_req_bits_operandB),
    .pop(arithmetic_resp_valid && arithmetic_resp_ready), .full(meta_full), .empty(meta_empty),
    .head_tag(meta_tag), .head_op(meta_op), .head_a(unused_a), .head_b(unused_b)
  );

  npc_fp_float_to_i32_ip f2i32 (
    .aclk(clock), .aresetn(!reset),
    .s_axis_a_tvalid(arithmetic_req_valid && !meta_full && is_f2i32_request), .s_axis_a_tready(f2i32_ready),
    .s_axis_a_tdata(arithmetic_req_bits_operandA[31:0]),
    .m_axis_result_tvalid(f2i32_valid), .m_axis_result_tready(arithmetic_resp_ready && !meta_empty && response_from_f2i32),
    .m_axis_result_tdata(f2i32_result), .m_axis_result_tuser(f2i32_invalid)
  );
  npc_fp_float_to_i64_ip f2i64 (
    .aclk(clock), .aresetn(!reset),
    .s_axis_a_tvalid(arithmetic_req_valid && !meta_full && is_f2i64_request), .s_axis_a_tready(f2i64_ready),
    .s_axis_a_tdata(arithmetic_req_bits_operandA[31:0]),
    .m_axis_result_tvalid(f2i64_valid), .m_axis_result_tready(arithmetic_resp_ready && !meta_empty && response_from_f2i64),
    .m_axis_result_tdata(f2i64_result), .m_axis_result_tuser(f2i64_invalid)
  );
  npc_fp_i32_to_float_ip i32f (
    .aclk(clock), .aresetn(!reset),
    .s_axis_a_tvalid(arithmetic_req_valid && !meta_full && is_i32f_request), .s_axis_a_tready(i32f_ready),
    .s_axis_a_tdata(arithmetic_req_bits_operandA[31:0]),
    .m_axis_result_tvalid(i32f_valid), .m_axis_result_tready(arithmetic_resp_ready && !meta_empty && response_from_i32f),
    .m_axis_result_tdata(i32f_result)
  );
  npc_fp_ui32_to_float_ip ui32f (
    .aclk(clock), .aresetn(!reset),
    .s_axis_a_tvalid(arithmetic_req_valid && !meta_full && is_ui32f_request), .s_axis_a_tready(ui32f_ready),
    .s_axis_a_tdata(arithmetic_req_bits_operandA[31:0]),
    .m_axis_result_tvalid(ui32f_valid), .m_axis_result_tready(arithmetic_resp_ready && !meta_empty && response_from_ui32f),
    .m_axis_result_tdata(ui32f_result)
  );
  npc_fp_i64_to_float_ip i64f (
    .aclk(clock), .aresetn(!reset),
    .s_axis_a_tvalid(arithmetic_req_valid && !meta_full && is_i64f_request), .s_axis_a_tready(i64f_ready),
    .s_axis_a_tdata(arithmetic_req_bits_operandA),
    .m_axis_result_tvalid(i64f_valid), .m_axis_result_tready(arithmetic_resp_ready && !meta_empty && response_from_i64f),
    .m_axis_result_tdata(i64f_result)
  );
  npc_fp_ui64_to_float_ip ui64f (
    .aclk(clock), .aresetn(!reset),
    .s_axis_a_tvalid(arithmetic_req_valid && !meta_full && is_ui64f_request), .s_axis_a_tready(ui64f_ready),
    .s_axis_a_tdata(arithmetic_req_bits_operandA),
    .m_axis_result_tvalid(ui64f_valid), .m_axis_result_tready(arithmetic_resp_ready && !meta_empty && response_from_ui64f),
    .m_axis_result_tdata(ui64f_result)
  );
endmodule

module npc_arithmetic_result_fifo #(
  parameter integer WIDTH = 32,
  parameter integer TAG_WIDTH = 4,
  parameter integer DEPTH = (1 << TAG_WIDTH)
) (
  input wire clock, input wire reset,
  input wire push, input wire [WIDTH-1:0] push_result, input wire [TAG_WIDTH-1:0] push_tag,
  input wire pop, output wire full, output wire empty, output wire [TAG_WIDTH:0] occupancy,
  output wire [WIDTH-1:0] head_result, output wire [TAG_WIDTH-1:0] head_tag
);
  reg [TAG_WIDTH-1:0] write_ptr, read_ptr;
  reg [TAG_WIDTH:0] count;
  reg [WIDTH-1:0] result_mem [0:DEPTH-1];
  reg [TAG_WIDTH-1:0] tag_mem [0:DEPTH-1];
  wire push_fire = push && !full;
  wire pop_fire = pop && !empty;
  assign full = count == DEPTH;
  assign empty = count == 0;
  assign occupancy = count;
  assign head_result = result_mem[read_ptr];
  assign head_tag = tag_mem[read_ptr];
  always @(posedge clock) begin
    if (reset) begin
      write_ptr <= 0;
      read_ptr <= 0;
      count <= 0;
    end else begin
      if (push_fire) begin
        result_mem[write_ptr] <= push_result;
        tag_mem[write_ptr] <= push_tag;
        write_ptr <= write_ptr + 1'b1;
      end
      if (pop_fire) read_ptr <= read_ptr + 1'b1;
      case ({push_fire, pop_fire})
        2'b10: count <= count + 1'b1;
        2'b01: count <= count - 1'b1;
        default: count <= count;
      endcase
    end
  end
endmodule

module npc_int_multiplier_adapter #(
  parameter integer WIDTH = 32,
  parameter integer TAG_WIDTH = 4,
  parameter integer LATENCY = 3
) (
  input wire clock, input wire reset,
  output wire arithmetic_req_ready, input wire arithmetic_req_valid,
  input wire [WIDTH-1:0] arithmetic_req_bits_operandA, input wire [WIDTH-1:0] arithmetic_req_bits_operandB,
  input wire [WIDTH-1:0] arithmetic_req_bits_operandC,
  input wire [4:0] arithmetic_req_bits_op, input wire [2:0] arithmetic_req_bits_roundingMode,
  input wire [TAG_WIDTH-1:0] arithmetic_req_bits_tag, input wire arithmetic_resp_ready,
  output wire arithmetic_resp_valid, output wire [WIDTH-1:0] arithmetic_resp_bits_result,
  output wire [4:0] arithmetic_resp_bits_exceptionFlags, output wire [TAG_WIDTH-1:0] arithmetic_resp_bits_tag
);
  localparam integer DEPTH = (1 << TAG_WIDTH);
  localparam [4:0] MUL = 5'h00, MULH = 5'h01, MULHSU = 5'h02, MULHU = 5'h03, MULW = 5'h08;
  reg [LATENCY-1:0] pipe_valid;
  reg [TAG_WIDTH-1:0] pipe_tag [0:LATENCY-1];
  reg [4:0] pipe_op [0:LATENCY-1];
  wire [TAG_WIDTH:0] fifo_occupancy;
  wire fifo_full, fifo_empty;
  wire [WIDTH-1:0] fifo_result;
  wire [TAG_WIDTH-1:0] fifo_tag;
  wire [WIDTH:0] ip_a, ip_b;
  wire [(2*WIDTH)+1:0] ip_p;
  reg [TAG_WIDTH:0] pipe_occupancy;
  integer pipe_index;
  wire pipe_result_valid = pipe_valid[LATENCY-1];
  wire request_fire = arithmetic_req_valid && arithmetic_req_ready;

  function automatic [WIDTH:0] prepared_a;
    input [WIDTH-1:0] value;
    input [4:0] op;
    begin
      if (WIDTH == 64 && op == MULW)
        prepared_a = {{(WIDTH - 31){value[31]}}, value[31:0]};
      else if (op == MULHU)
        prepared_a = {1'b0, value};
      else
        prepared_a = {value[WIDTH-1], value};
    end
  endfunction
  function automatic [WIDTH:0] prepared_b;
    input [WIDTH-1:0] value;
    input [4:0] op;
    begin
      if (WIDTH == 64 && op == MULW)
        prepared_b = {{(WIDTH - 31){value[31]}}, value[31:0]};
      else if (op == MULHSU || op == MULHU)
        prepared_b = {1'b0, value};
      else
        prepared_b = {value[WIDTH-1], value};
    end
  endfunction
  function automatic [WIDTH-1:0] select_product;
    input [(2*WIDTH)+1:0] product;
    input [4:0] op;
    begin
      if (WIDTH == 64 && op == MULW)
        select_product = {{(WIDTH - 32){product[31]}}, product[31:0]};
      else if (op == MULH || op == MULHSU || op == MULHU)
        select_product = product[(2*WIDTH)-1:WIDTH];
      else
        select_product = product[WIDTH-1:0];
    end
  endfunction

  assign ip_a = prepared_a(arithmetic_req_bits_operandA, arithmetic_req_bits_op);
  assign ip_b = prepared_b(arithmetic_req_bits_operandB, arithmetic_req_bits_op);
  assign arithmetic_req_ready = (fifo_occupancy + pipe_occupancy) < DEPTH;
  assign arithmetic_resp_valid = !fifo_empty;
  assign arithmetic_resp_bits_result = fifo_result;
  assign arithmetic_resp_bits_exceptionFlags = 5'b0;
  assign arithmetic_resp_bits_tag = fifo_tag;

  always @* begin
    pipe_occupancy = 0;
    for (pipe_index = 0; pipe_index < LATENCY; pipe_index = pipe_index + 1)
      pipe_occupancy = pipe_occupancy + pipe_valid[pipe_index];
  end

  npc_int_multiplier_ip ip (.CLK(clock), .A(ip_a), .B(ip_b), .P(ip_p));
  npc_arithmetic_result_fifo #(.WIDTH(WIDTH), .TAG_WIDTH(TAG_WIDTH)) results (
    .clock(clock), .reset(reset), .push(pipe_result_valid),
    .push_result(select_product(ip_p, pipe_op[LATENCY-1])), .push_tag(pipe_tag[LATENCY-1]),
    .pop(arithmetic_resp_valid && arithmetic_resp_ready), .full(fifo_full), .empty(fifo_empty),
    .occupancy(fifo_occupancy), .head_result(fifo_result), .head_tag(fifo_tag)
  );
  always @(posedge clock) begin
    if (reset) begin
      pipe_valid <= {LATENCY{1'b0}};
    end else begin
      for (pipe_index = LATENCY - 1; pipe_index > 0; pipe_index = pipe_index - 1) begin
        pipe_valid[pipe_index] <= pipe_valid[pipe_index - 1];
        if (pipe_valid[pipe_index - 1]) begin
          pipe_tag[pipe_index] <= pipe_tag[pipe_index - 1];
          pipe_op[pipe_index] <= pipe_op[pipe_index - 1];
        end
      end
      pipe_valid[0] <= request_fire;
      if (request_fire) begin
        pipe_tag[0] <= arithmetic_req_bits_tag;
        pipe_op[0] <= arithmetic_req_bits_op;
      end
    end
  end
endmodule

module npc_int_divider_adapter #(
  parameter integer WIDTH = 32,
  parameter integer TAG_WIDTH = 4,
  parameter integer LATENCY = 37
) (
  input wire clock, input wire reset,
  output wire arithmetic_req_ready, input wire arithmetic_req_valid,
  input wire [WIDTH-1:0] arithmetic_req_bits_operandA, input wire [WIDTH-1:0] arithmetic_req_bits_operandB,
  input wire [WIDTH-1:0] arithmetic_req_bits_operandC,
  input wire [4:0] arithmetic_req_bits_op, input wire [2:0] arithmetic_req_bits_roundingMode,
  input wire [TAG_WIDTH-1:0] arithmetic_req_bits_tag, input wire arithmetic_resp_ready,
  output wire arithmetic_resp_valid, output wire [WIDTH-1:0] arithmetic_resp_bits_result,
  output wire [4:0] arithmetic_resp_bits_exceptionFlags, output wire [TAG_WIDTH-1:0] arithmetic_resp_bits_tag
);
  localparam [4:0] DIV = 5'h04, DIVU = 5'h05, REM = 5'h06, REMU = 5'h07;
  localparam [4:0] DIVW = 5'h09, DIVUW = 5'h0A, REMW = 5'h0B, REMUW = 5'h0C;
  wire divisor_ready, dividend_ready, dout_valid, meta_full, meta_empty;
  wire [2*WIDTH-1:0] dout_data;
  wire [TAG_WIDTH-1:0] meta_tag;
  wire [4:0] meta_op;
  wire [WIDTH-1:0] meta_a, meta_b;
  wire request_fire = arithmetic_req_valid && arithmetic_req_ready;

  function automatic is_word;
    input [4:0] op;
    begin
      is_word = (WIDTH == 64) && (op == DIVW || op == DIVUW || op == REMW || op == REMUW);
    end
  endfunction
  function automatic is_remainder;
    input [4:0] op;
    begin
      is_remainder = op == REM || op == REMU || op == REMW || op == REMUW;
    end
  endfunction
  function automatic is_signed;
    input [4:0] op;
    begin
      is_signed = op == DIV || op == REM || op == DIVW || op == REMW;
    end
  endfunction
  function automatic [WIDTH-1:0] active_value;
    input [WIDTH-1:0] value;
    input [4:0] op;
    begin
      if (is_word(op)) active_value = {{(WIDTH-32){1'b0}}, value[31:0]};
      else active_value = value;
    end
  endfunction
  function automatic [WIDTH-1:0] format_value;
    input [WIDTH-1:0] value;
    input [4:0] op;
    begin
      if (is_word(op)) format_value = {{(WIDTH-32){value[31]}}, value[31:0]};
      else format_value = value;
    end
  endfunction

  wire [WIDTH-1:0] request_a_active = active_value(arithmetic_req_bits_operandA, arithmetic_req_bits_op);
  wire [WIDTH-1:0] request_b_active = active_value(arithmetic_req_bits_operandB, arithmetic_req_bits_op);
  wire request_a_negative = is_signed(arithmetic_req_bits_op) &&
    (is_word(arithmetic_req_bits_op) ? arithmetic_req_bits_operandA[31] : arithmetic_req_bits_operandA[WIDTH-1]);
  wire request_b_negative = is_signed(arithmetic_req_bits_op) &&
    (is_word(arithmetic_req_bits_op) ? arithmetic_req_bits_operandB[31] : arithmetic_req_bits_operandB[WIDTH-1]);
  wire [WIDTH-1:0] request_a_magnitude = request_a_negative ? (~request_a_active + 1'b1) : request_a_active;
  wire [WIDTH-1:0] request_b_magnitude = request_b_negative ? (~request_b_active + 1'b1) : request_b_active;

  wire [WIDTH-1:0] meta_a_active = active_value(meta_a, meta_op);
  wire [WIDTH-1:0] meta_b_active = active_value(meta_b, meta_op);
  wire meta_a_negative = is_signed(meta_op) && (is_word(meta_op) ? meta_a[31] : meta_a[WIDTH-1]);
  wire meta_b_negative = is_signed(meta_op) && (is_word(meta_op) ? meta_b[31] : meta_b[WIDTH-1]);
  wire [WIDTH-1:0] quotient = dout_data[(2*WIDTH)-1:WIDTH];
  wire [WIDTH-1:0] remainder = dout_data[WIDTH-1:0];
  wire [WIDTH-1:0] raw_result = is_remainder(meta_op) ? remainder : quotient;
  wire negate_result = is_signed(meta_op) &&
    (is_remainder(meta_op) ? meta_a_negative : (meta_a_negative ^ meta_b_negative));
  wire [WIDTH-1:0] signed_result = negate_result ? (~raw_result + 1'b1) : raw_result;
  wire [WIDTH-1:0] active_all_ones = is_word(meta_op) ? {{(WIDTH-32){1'b0}}, 32'hffff_ffff} : {WIDTH{1'b1}};
  wire [WIDTH-1:0] active_signed_min = is_word(meta_op) ? {{(WIDTH-32){1'b0}}, 32'h8000_0000} : ({{(WIDTH-1){1'b0}},1'b1} << (WIDTH-1));
  wire divide_by_zero = meta_b_active == 0;
  wire signed_overflow = is_signed(meta_op) && meta_a_active == active_signed_min && meta_b_active == active_all_ones;
  wire [WIDTH-1:0] exceptional_result = divide_by_zero ?
    (is_remainder(meta_op) ? meta_a_active : active_all_ones) :
    (signed_overflow ? (is_remainder(meta_op) ? {WIDTH{1'b0}} : meta_a_active) : signed_result);

  assign arithmetic_req_ready = !meta_full && divisor_ready && dividend_ready;
  assign arithmetic_resp_valid = dout_valid && !meta_empty;
  assign arithmetic_resp_bits_result = format_value(exceptional_result, meta_op);
  assign arithmetic_resp_bits_exceptionFlags = 5'b0;
  assign arithmetic_resp_bits_tag = meta_tag;
  npc_arithmetic_meta_fifo #(.WIDTH(WIDTH), .TAG_WIDTH(TAG_WIDTH)) metadata (
    .clock(clock), .reset(reset), .push(request_fire), .push_tag(arithmetic_req_bits_tag),
    .push_op(arithmetic_req_bits_op), .push_a(arithmetic_req_bits_operandA), .push_b(arithmetic_req_bits_operandB),
    .pop(arithmetic_resp_valid && arithmetic_resp_ready), .full(meta_full), .empty(meta_empty),
    .head_tag(meta_tag), .head_op(meta_op), .head_a(meta_a), .head_b(meta_b)
  );
  npc_int_divider_ip ip (
    .aclk(clock), .aresetn(!reset),
    .s_axis_divisor_tvalid(arithmetic_req_valid && !meta_full), .s_axis_divisor_tready(divisor_ready),
    .s_axis_divisor_tdata(request_b_magnitude),
    .s_axis_dividend_tvalid(arithmetic_req_valid && !meta_full), .s_axis_dividend_tready(dividend_ready),
    .s_axis_dividend_tdata(request_a_magnitude),
    .m_axis_dout_tvalid(dout_valid), .m_axis_dout_tready(arithmetic_resp_ready && !meta_empty),
    .m_axis_dout_tdata(dout_data)
  );
endmodule
