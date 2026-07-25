// Chisel 算术端点与 Vivado 2022.2 整数乘除法 IP 之间的稳定适配层。
// FPGA 核心对 M 指令严格单在途；这里仅保留 IP 固定延迟寄存器和一个响应寄存器，
// 不实例化完成 FIFO、metadata FIFO 或多请求 tag 回填逻辑。

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
  localparam [4:0] MUL = 0, MULH = 1, MULHSU = 2, MULHU = 3, MULW = 4;

  reg in_flight;
  reg [LATENCY-1:0] result_valid_pipe;
  reg [TAG_WIDTH-1:0] active_tag;
  reg [4:0] active_operation;
  reg [WIDTH-1:0] active_operand_a;
  reg [WIDTH-1:0] active_operand_b;
  reg response_valid;
  reg [WIDTH-1:0] response_result;
  reg [TAG_WIDTH-1:0] response_tag;
  integer index;

  wire [WIDTH-1:0] ip_a;
  wire [WIDTH-1:0] ip_b;
  wire [(2*WIDTH)-1:0] ip_product;
  wire request_fire = arithmetic_req_valid && arithmetic_req_ready;
  wire result_due = result_valid_pipe[LATENCY-1];
  wire response_fire = response_valid && arithmetic_resp_ready;

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

  // 请求握手拍直接驱动厂商 IP；之后改由锁存的操作数保持仿真 stub 和波形可读性。
  assign ip_a = request_fire ? arithmetic_req_bits_operandA : active_operand_a;
  assign ip_b = request_fire ? arithmetic_req_bits_operandB : active_operand_b;
  assign arithmetic_req_ready = !in_flight;
  assign arithmetic_resp_valid = response_valid;
  assign arithmetic_resp_bits_result = response_result;
  assign arithmetic_resp_bits_exceptionFlags = 0;
  assign arithmetic_resp_bits_illegal = 1'b0;
  assign arithmetic_resp_bits_tag = response_tag;

  npc_int_multiplier_ip multiplier (
    .CLK(clock),
    .A(ip_a),
    .B(ip_b),
    .P(ip_product)
  );

  always @(posedge clock) begin
    if (reset) begin
      in_flight <= 1'b0;
      result_valid_pipe <= 0;
      response_valid <= 1'b0;
    end else begin
      for (index = LATENCY - 1; index > 0; index = index - 1)
        result_valid_pipe[index] <= result_valid_pipe[index - 1];
      result_valid_pipe[0] <= request_fire;

      if (request_fire) begin
        in_flight <= 1'b1;
        active_tag <= arithmetic_req_bits_tag;
        active_operation <= arithmetic_req_bits_operation;
        active_operand_a <= arithmetic_req_bits_operandA;
        active_operand_b <= arithmetic_req_bits_operandB;
      end
      if (response_fire) begin
        in_flight <= 1'b0;
        response_valid <= 1'b0;
      end
      if (result_due) begin
        response_valid <= 1'b1;
        response_result <= select_product(ip_product, active_operand_a, active_operand_b, active_operation);
        response_tag <= active_tag;
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

  reg in_flight;
  reg [TAG_WIDTH-1:0] active_tag;
  reg [4:0] active_operation;
  reg [WIDTH-1:0] active_operand_a;
  reg [WIDTH-1:0] active_operand_b;
  reg [PAD_LATENCY-1:0] pad_valid;
  reg [WIDTH-1:0] pad_result [0:PAD_LATENCY-1];
  reg [TAG_WIDTH-1:0] pad_tag [0:PAD_LATENCY-1];
  integer pad_index;

  wire divisor_ready;
  wire dividend_ready;
  wire result_valid;
  wire [2*WIDTH-1:0] result_data;

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

  wire [WIDTH-1:0] active_a = active_value(active_operand_a, active_operation);
  wire [WIDTH-1:0] active_b = active_value(active_operand_b, active_operation);
  wire a_negative = is_signed(active_operation) &&
    (is_word(active_operation) ? active_operand_a[31] : active_operand_a[WIDTH-1]);
  wire b_negative = is_signed(active_operation) &&
    (is_word(active_operation) ? active_operand_b[31] : active_operand_b[WIDTH-1]);
  // DivGen 示例设计规定余数位于 m_axis_dout_tdata 低位，商位于高位。
  wire [WIDTH-1:0] remainder = result_data[WIDTH-1:0];
  wire [WIDTH-1:0] quotient = result_data[(2*WIDTH)-1:WIDTH];
  wire [WIDTH-1:0] unsigned_result = is_remainder(active_operation) ? remainder : quotient;
  wire negate_result = is_signed(active_operation) &&
    (is_remainder(active_operation) ? a_negative : (a_negative ^ b_negative));
  wire [WIDTH-1:0] signed_result = negate_result ? (~unsigned_result + 1'b1) : unsigned_result;
  wire [WIDTH-1:0] active_all_ones = is_word(active_operation) ?
    {{(WIDTH - 32){1'b0}}, 32'hffff_ffff} : {WIDTH{1'b1}};
  wire [WIDTH-1:0] active_signed_min = is_word(active_operation) ?
    {{(WIDTH - 32){1'b0}}, 32'h8000_0000} : ({WIDTH{1'b1}} << (WIDTH - 1));
  wire divide_by_zero = active_b == 0;
  wire signed_overflow = is_signed(active_operation) &&
    active_a == active_signed_min && active_b == active_all_ones;
  wire [WIDTH-1:0] exceptional_result = divide_by_zero ?
    (is_remainder(active_operation) ? active_a : active_all_ones) :
    (signed_overflow ? (is_remainder(active_operation) ? 0 : active_a) : signed_result);

  // 固定 padding 流水寄存器把 DivGen 输出对齐到配置时延；它不是请求或完成 FIFO。
  wire pad_advance = !pad_valid[PAD_LATENCY-1] || arithmetic_resp_ready;
  wire issue = arithmetic_req_valid && arithmetic_req_ready;
  wire ip_result_fire = result_valid && in_flight && pad_advance;
  wire response_fire = pad_valid[PAD_LATENCY-1] && arithmetic_resp_ready;
  assign arithmetic_req_ready = !in_flight && divisor_ready && dividend_ready;
  assign arithmetic_resp_valid = pad_valid[PAD_LATENCY-1];
  assign arithmetic_resp_bits_result = pad_result[PAD_LATENCY-1];
  assign arithmetic_resp_bits_exceptionFlags = 0;
  assign arithmetic_resp_bits_illegal = 1'b0;
  assign arithmetic_resp_bits_tag = pad_tag[PAD_LATENCY-1];

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
    .m_axis_dout_tready(in_flight && pad_advance),
    .m_axis_dout_tdata(result_data)
  );

  always @(posedge clock) begin
    if (reset) begin
      in_flight <= 1'b0;
      pad_valid <= 0;
    end else begin
      if (issue) begin
        in_flight <= 1'b1;
        active_tag <= arithmetic_req_bits_tag;
        active_operation <= arithmetic_req_bits_operation;
        active_operand_a <= arithmetic_req_bits_operandA;
        active_operand_b <= arithmetic_req_bits_operandB;
      end
      if (response_fire)
        in_flight <= 1'b0;

      if (pad_advance) begin
        for (pad_index = PAD_LATENCY - 1; pad_index > 0; pad_index = pad_index - 1) begin
          pad_valid[pad_index] <= pad_valid[pad_index - 1];
          if (pad_valid[pad_index - 1]) begin
            pad_result[pad_index] <= pad_result[pad_index - 1];
            pad_tag[pad_index] <= pad_tag[pad_index - 1];
          end
        end
        pad_valid[0] <= ip_result_fire;
        if (ip_result_fire) begin
          pad_result[0] <= format_value(exceptional_result, active_operation);
          pad_tag[0] <= active_tag;
        end
      end
    end
  end
endmodule
