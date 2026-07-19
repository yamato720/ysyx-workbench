// Chisel 算术端点与 Vivado 2022.2 乘除法 IP 之间的稳定适配层。
// 只有整数 M 扩展使用这些 IP；严格浮点运算由 mailbox 回退处理。

module npc_fpga_result_fifo #(
  parameter integer WIDTH = 32,
  parameter integer TAG_WIDTH = 4,
  parameter integer DEPTH = (1 << TAG_WIDTH)
) (
  input  wire                 clock,
  input  wire                 reset,
  input  wire                 push,
  input  wire [WIDTH-1:0]     push_result,
  input  wire [TAG_WIDTH-1:0] push_tag,
  input  wire                 pop,
  output wire                 full,
  output wire                 empty,
  output wire [TAG_WIDTH:0]   occupancy,
  output wire [WIDTH-1:0]     head_result,
  output wire [TAG_WIDTH-1:0] head_tag
);
  reg [TAG_WIDTH-1:0] write_ptr;
  reg [TAG_WIDTH-1:0] read_ptr;
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
      if (pop_fire)
        read_ptr <= read_ptr + 1'b1;
      case ({push_fire, pop_fire})
        2'b10: count <= count + 1'b1;
        2'b01: count <= count - 1'b1;
        default: count <= count;
      endcase
    end
  end
endmodule

module npc_fpga_div_metadata_fifo #(
  parameter integer WIDTH = 32,
  parameter integer TAG_WIDTH = 4,
  parameter integer DEPTH = (1 << TAG_WIDTH)
) (
  input  wire                 clock,
  input  wire                 reset,
  input  wire                 push,
  input  wire [TAG_WIDTH-1:0] push_tag,
  input  wire [4:0]           push_operation,
  input  wire [WIDTH-1:0]     push_a,
  input  wire [WIDTH-1:0]     push_b,
  input  wire                 pop,
  output wire                 full,
  output wire                 empty,
  output wire [TAG_WIDTH-1:0] head_tag,
  output wire [4:0]           head_operation,
  output wire [WIDTH-1:0]     head_a,
  output wire [WIDTH-1:0]     head_b
);
  reg [TAG_WIDTH-1:0] write_ptr;
  reg [TAG_WIDTH-1:0] read_ptr;
  reg [TAG_WIDTH:0] count;
  reg [TAG_WIDTH-1:0] tag_mem [0:DEPTH-1];
  reg [4:0] operation_mem [0:DEPTH-1];
  reg [WIDTH-1:0] a_mem [0:DEPTH-1];
  reg [WIDTH-1:0] b_mem [0:DEPTH-1];

  wire push_fire = push && !full;
  wire pop_fire = pop && !empty;
  assign full = count == DEPTH;
  assign empty = count == 0;
  assign head_tag = tag_mem[read_ptr];
  assign head_operation = operation_mem[read_ptr];
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
        operation_mem[write_ptr] <= push_operation;
        a_mem[write_ptr] <= push_a;
        b_mem[write_ptr] <= push_b;
        write_ptr <= write_ptr + 1'b1;
      end
      if (pop_fire)
        read_ptr <= read_ptr + 1'b1;
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
  input  wire                 clock,
  input  wire                 reset,
  output wire                 arithmetic_req_ready,
  input  wire                 arithmetic_req_valid,
  input  wire [WIDTH-1:0]     arithmetic_req_bits_operandA,
  input  wire [WIDTH-1:0]     arithmetic_req_bits_operandB,
  input  wire [WIDTH-1:0]     arithmetic_req_bits_operandC,
  input  wire [4:0]           arithmetic_req_bits_operation,
  input  wire [2:0]           arithmetic_req_bits_roundingMode,
  input  wire [WIDTH-1:0]     arithmetic_req_bits_pc,
  input  wire [31:0]          arithmetic_req_bits_instruction,
  input  wire [7:0]           arithmetic_req_bits_fcsr,
  input  wire [TAG_WIDTH-1:0] arithmetic_req_bits_tag,
  input  wire                 arithmetic_resp_ready,
  output wire                 arithmetic_resp_valid,
  output wire [WIDTH-1:0]     arithmetic_resp_bits_result,
  output wire [4:0]           arithmetic_resp_bits_exceptionFlags,
  output wire                 arithmetic_resp_bits_illegal,
  output wire [TAG_WIDTH-1:0] arithmetic_resp_bits_tag
);
  localparam integer DEPTH = (1 << TAG_WIDTH);
  localparam [4:0] MUL = 0, MULH = 1, MULHSU = 2, MULHU = 3, MULW = 4;
  reg [LATENCY-1:0] pipe_valid;
  reg [TAG_WIDTH-1:0] pipe_tag [0:LATENCY-1];
  reg [4:0] pipe_operation [0:LATENCY-1];
  reg [WIDTH-1:0] pipe_operand_a [0:LATENCY-1];
  reg [WIDTH-1:0] pipe_operand_b [0:LATENCY-1];
  reg [TAG_WIDTH:0] pipe_occupancy;
  integer index;

  wire [TAG_WIDTH:0] fifo_occupancy;
  wire fifo_full;
  wire fifo_empty;
  wire [WIDTH-1:0] fifo_result;
  wire [TAG_WIDTH-1:0] fifo_tag;
  wire [WIDTH-1:0] ip_a;
  wire [WIDTH-1:0] ip_b;
  wire [(2*WIDTH)-1:0] ip_product;
  wire request_fire = arithmetic_req_valid && arithmetic_req_ready;
  wire result_valid = pipe_valid[LATENCY-1];

  function automatic [WIDTH-1:0] select_product;
    input [(2*WIDTH)-1:0] product;
    input [WIDTH-1:0] operand_a;
    input [WIDTH-1:0] operand_b;
    input [4:0] operation;
    reg [WIDTH-1:0] high_half;
    begin
      // 对无符号乘积 U*V 修正高半部：
      // signed(U)*signed(V) = U*V - sign(U)*2^W*V - sign(V)*2^W*U。
      // 只保留高 W 位时，修正项分别为 V 和 U；所有运算自然按 W 位回绕。
      high_half = product[(2*WIDTH)-1:WIDTH];
      if (operation == MULH) begin
        if (operand_a[WIDTH-1]) high_half = high_half - operand_b;
        if (operand_b[WIDTH-1]) high_half = high_half - operand_a;
      end else if (operation == MULHSU && operand_a[WIDTH-1]) begin
        high_half = high_half - operand_b;
      end
      if (WIDTH == 64 && operation == MULW)
        select_product = {{(WIDTH - 32){product[31]}}, product[31:0]};
      else if (operation == MULH || operation == MULHSU || operation == MULHU)
        select_product = high_half;
      else
        select_product = product[WIDTH-1:0];
    end
  endfunction

  assign ip_a = arithmetic_req_bits_operandA;
  assign ip_b = arithmetic_req_bits_operandB;
  assign arithmetic_req_ready = (fifo_occupancy + pipe_occupancy) < DEPTH;
  assign arithmetic_resp_valid = !fifo_empty;
  assign arithmetic_resp_bits_result = fifo_result;
  assign arithmetic_resp_bits_exceptionFlags = 0;
  assign arithmetic_resp_bits_illegal = 1'b0;
  assign arithmetic_resp_bits_tag = fifo_tag;

  always @* begin
    pipe_occupancy = 0;
    for (index = 0; index < LATENCY; index = index + 1)
      pipe_occupancy = pipe_occupancy + pipe_valid[index];
  end

  npc_int_multiplier_ip multiplier (
    .CLK(clock),
    .A(ip_a),
    .B(ip_b),
    .P(ip_product)
  );

  npc_fpga_result_fifo #(.WIDTH(WIDTH), .TAG_WIDTH(TAG_WIDTH)) results (
    .clock(clock),
    .reset(reset),
    .push(result_valid),
    .push_result(select_product(ip_product, pipe_operand_a[LATENCY-1],
      pipe_operand_b[LATENCY-1], pipe_operation[LATENCY-1])),
    .push_tag(pipe_tag[LATENCY-1]),
    .pop(arithmetic_resp_valid && arithmetic_resp_ready),
    .full(fifo_full),
    .empty(fifo_empty),
    .occupancy(fifo_occupancy),
    .head_result(fifo_result),
    .head_tag(fifo_tag)
  );

  always @(posedge clock) begin
    if (reset) begin
      pipe_valid <= 0;
    end else begin
      for (index = LATENCY - 1; index > 0; index = index - 1) begin
        pipe_valid[index] <= pipe_valid[index - 1];
        if (pipe_valid[index - 1]) begin
          pipe_tag[index] <= pipe_tag[index - 1];
          pipe_operation[index] <= pipe_operation[index - 1];
          pipe_operand_a[index] <= pipe_operand_a[index - 1];
          pipe_operand_b[index] <= pipe_operand_b[index - 1];
        end
      end
      pipe_valid[0] <= request_fire;
      if (request_fire) begin
        pipe_tag[0] <= arithmetic_req_bits_tag;
        pipe_operation[0] <= arithmetic_req_bits_operation;
        pipe_operand_a[0] <= arithmetic_req_bits_operandA;
        pipe_operand_b[0] <= arithmetic_req_bits_operandB;
      end
    end
  end
endmodule

module npc_int_divider_adapter #(
  parameter integer WIDTH = 32,
  parameter integer TAG_WIDTH = 4,
  parameter integer LATENCY = 37
) (
  input  wire                 clock,
  input  wire                 reset,
  output wire                 arithmetic_req_ready,
  input  wire                 arithmetic_req_valid,
  input  wire [WIDTH-1:0]     arithmetic_req_bits_operandA,
  input  wire [WIDTH-1:0]     arithmetic_req_bits_operandB,
  input  wire [WIDTH-1:0]     arithmetic_req_bits_operandC,
  input  wire [4:0]           arithmetic_req_bits_operation,
  input  wire [2:0]           arithmetic_req_bits_roundingMode,
  input  wire [WIDTH-1:0]     arithmetic_req_bits_pc,
  input  wire [31:0]          arithmetic_req_bits_instruction,
  input  wire [7:0]           arithmetic_req_bits_fcsr,
  input  wire [TAG_WIDTH-1:0] arithmetic_req_bits_tag,
  input  wire                 arithmetic_resp_ready,
  output wire                 arithmetic_resp_valid,
  output wire [WIDTH-1:0]     arithmetic_resp_bits_result,
  output wire [4:0]           arithmetic_resp_bits_exceptionFlags,
  output wire                 arithmetic_resp_bits_illegal,
  output wire [TAG_WIDTH-1:0] arithmetic_resp_bits_tag
);
  localparam integer VENDOR_LATENCY = 34;
  localparam integer PAD_LATENCY = LATENCY - VENDOR_LATENCY;
  localparam [4:0] DIV = 0, DIVU = 1, REM = 2, REMU = 3;
  localparam [4:0] DIVW = 4, DIVUW = 5, REMW = 6, REMUW = 7;
  wire divisor_ready;
  wire dividend_ready;
  wire result_valid;
  wire [2*WIDTH-1:0] result_data;
  wire metadata_full;
  wire metadata_empty;
  wire [TAG_WIDTH-1:0] metadata_tag;
  wire [4:0] metadata_operation;
  wire [WIDTH-1:0] metadata_a;
  wire [WIDTH-1:0] metadata_b;
  reg [PAD_LATENCY-1:0] pad_valid;
  reg [WIDTH-1:0] pad_result [0:PAD_LATENCY-1];
  reg [TAG_WIDTH-1:0] pad_tag [0:PAD_LATENCY-1];
  integer pad_index;

  function automatic is_word;
    input [4:0] operation;
    begin
      is_word = WIDTH == 64 &&
        (operation == DIVW || operation == DIVUW || operation == REMW || operation == REMUW);
    end
  endfunction

  function automatic is_remainder;
    input [4:0] operation;
    begin
      is_remainder = operation == REM || operation == REMU || operation == REMW || operation == REMUW;
    end
  endfunction

  function automatic is_signed;
    input [4:0] operation;
    begin
      is_signed = operation == DIV || operation == REM || operation == DIVW || operation == REMW;
    end
  endfunction

  function automatic [WIDTH-1:0] active_value;
    input [WIDTH-1:0] value;
    input [4:0] operation;
    begin
      if (is_word(operation))
        active_value = {{(WIDTH - 32){1'b0}}, value[31:0]};
      else
        active_value = value;
    end
  endfunction

  function automatic [WIDTH-1:0] format_value;
    input [WIDTH-1:0] value;
    input [4:0] operation;
    begin
      if (is_word(operation))
        format_value = {{(WIDTH - 32){value[31]}}, value[31:0]};
      else
        format_value = value;
    end
  endfunction

  wire [WIDTH-1:0] request_a = active_value(arithmetic_req_bits_operandA, arithmetic_req_bits_operation);
  wire [WIDTH-1:0] request_b = active_value(arithmetic_req_bits_operandB, arithmetic_req_bits_operation);
  wire request_a_negative = is_signed(arithmetic_req_bits_operation) &&
    (is_word(arithmetic_req_bits_operation) ? arithmetic_req_bits_operandA[31] : arithmetic_req_bits_operandA[WIDTH-1]);
  wire request_b_negative = is_signed(arithmetic_req_bits_operation) &&
    (is_word(arithmetic_req_bits_operation) ? arithmetic_req_bits_operandB[31] : arithmetic_req_bits_operandB[WIDTH-1]);
  wire [WIDTH-1:0] request_a_magnitude = request_a_negative ? (~request_a + 1'b1) : request_a;
  wire [WIDTH-1:0] request_b_magnitude = request_b_negative ? (~request_b + 1'b1) : request_b;

  wire [WIDTH-1:0] active_a = active_value(metadata_a, metadata_operation);
  wire [WIDTH-1:0] active_b = active_value(metadata_b, metadata_operation);
  wire a_negative = is_signed(metadata_operation) &&
    (is_word(metadata_operation) ? metadata_a[31] : metadata_a[WIDTH-1]);
  wire b_negative = is_signed(metadata_operation) &&
    (is_word(metadata_operation) ? metadata_b[31] : metadata_b[WIDTH-1]);
  // DivGen 示例设计规定余数位于 m_axis_dout_tdata 低位，商位于高位。
  wire [WIDTH-1:0] remainder = result_data[WIDTH-1:0];
  wire [WIDTH-1:0] quotient = result_data[(2*WIDTH)-1:WIDTH];
  wire [WIDTH-1:0] unsigned_result = is_remainder(metadata_operation) ? remainder : quotient;
  wire negate_result = is_signed(metadata_operation) &&
    (is_remainder(metadata_operation) ? a_negative : (a_negative ^ b_negative));
  wire [WIDTH-1:0] signed_result = negate_result ? (~unsigned_result + 1'b1) : unsigned_result;
  wire [WIDTH-1:0] active_all_ones = is_word(metadata_operation) ?
    {{(WIDTH - 32){1'b0}}, 32'hffff_ffff} : {WIDTH{1'b1}};
  wire [WIDTH-1:0] active_signed_min = is_word(metadata_operation) ?
    {{(WIDTH - 32){1'b0}}, 32'h8000_0000} : ({WIDTH{1'b1}} << (WIDTH - 1));
  wire divide_by_zero = active_b == 0;
  wire signed_overflow = is_signed(metadata_operation) &&
    active_a == active_signed_min && active_b == active_all_ones;
  wire [WIDTH-1:0] exceptional_result = divide_by_zero ?
    (is_remainder(metadata_operation) ? active_a : active_all_ones) :
    (signed_overflow ? (is_remainder(metadata_operation) ? 0 : active_a) : signed_result);

  // 两个 AXIS 输入通道必须与元数据 FIFO 原子传输。
  wire issue = arithmetic_req_valid && !metadata_full && divisor_ready && dividend_ready;
  wire pad_advance = !pad_valid[PAD_LATENCY-1] || arithmetic_resp_ready;
  wire ip_result_fire = result_valid && !metadata_empty && pad_advance;
  assign arithmetic_req_ready = !metadata_full && divisor_ready && dividend_ready;
  assign arithmetic_resp_valid = pad_valid[PAD_LATENCY-1];
  assign arithmetic_resp_bits_result = pad_result[PAD_LATENCY-1];
  assign arithmetic_resp_bits_exceptionFlags = 0;
  assign arithmetic_resp_bits_illegal = 1'b0;
  assign arithmetic_resp_bits_tag = pad_tag[PAD_LATENCY-1];

  npc_fpga_div_metadata_fifo #(.WIDTH(WIDTH), .TAG_WIDTH(TAG_WIDTH)) metadata (
    .clock(clock),
    .reset(reset),
    .push(issue),
    .push_tag(arithmetic_req_bits_tag),
    .push_operation(arithmetic_req_bits_operation),
    .push_a(arithmetic_req_bits_operandA),
    .push_b(arithmetic_req_bits_operandB),
    .pop(ip_result_fire),
    .full(metadata_full),
    .empty(metadata_empty),
    .head_tag(metadata_tag),
    .head_operation(metadata_operation),
    .head_a(metadata_a),
    .head_b(metadata_b)
  );

  npc_int_divider_ip divider (
    .aclk(clock),
    .aresetn(!reset),
    .s_axis_divisor_tvalid(issue),
    .s_axis_divisor_tready(divisor_ready),
    .s_axis_divisor_tdata(request_b_magnitude),
    .s_axis_dividend_tvalid(issue),
    .s_axis_dividend_tready(dividend_ready),
    .s_axis_dividend_tdata(request_a_magnitude),
    .m_axis_dout_tvalid(result_valid),
    .m_axis_dout_tready(!metadata_empty && pad_advance),
    .m_axis_dout_tdata(result_data)
  );

  always @(posedge clock) begin
    if (reset) begin
      pad_valid <= 0;
    end else if (pad_advance) begin
      for (pad_index = PAD_LATENCY - 1; pad_index > 0; pad_index = pad_index - 1) begin
        pad_valid[pad_index] <= pad_valid[pad_index - 1];
        if (pad_valid[pad_index - 1]) begin
          pad_result[pad_index] <= pad_result[pad_index - 1];
          pad_tag[pad_index] <= pad_tag[pad_index - 1];
        end
      end
      pad_valid[0] <= ip_result_fire;
      if (ip_result_fire) begin
        pad_result[0] <= format_value(exceptional_result, metadata_operation);
        pad_tag[0] <= metadata_tag;
      end
    end
  end
endmodule
