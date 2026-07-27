module npc_int_divider_ip (
  input  wire         aclk,
  input  wire         aresetn,
  input  wire         s_axis_divisor_tvalid,
  output wire         s_axis_divisor_tready,
  input  wire [63:0]  s_axis_divisor_tdata,
  input  wire         s_axis_dividend_tvalid,
  output wire         s_axis_dividend_tready,
  input  wire [63:0]  s_axis_dividend_tdata,
  output wire         m_axis_dout_tvalid,
  input  wire         m_axis_dout_tready,
  output wire [127:0] m_axis_dout_tdata
);
  reg result_valid;
  reg [127:0] result_data;
`ifdef NPC_TEST_DIVIDER_NON_BLOCKING
  wire issue = s_axis_divisor_tvalid && s_axis_dividend_tvalid;
`else
  wire issue = s_axis_divisor_tvalid && s_axis_dividend_tvalid &&
    s_axis_divisor_tready && s_axis_dividend_tready;

  assign s_axis_divisor_tready = !result_valid;
  assign s_axis_dividend_tready = !result_valid;
`endif
  assign m_axis_dout_tvalid = result_valid;
  assign m_axis_dout_tdata = result_data;

  always @(posedge aclk) begin
    if (!aresetn) begin
      result_valid <= 1'b0;
    end else if (issue) begin
      result_valid <= 1'b1;
      if (s_axis_divisor_tdata == 0)
        result_data <= 0;
      else
        result_data <= {s_axis_dividend_tdata / s_axis_divisor_tdata,
          s_axis_dividend_tdata % s_axis_divisor_tdata};
`ifdef NPC_TEST_DIVIDER_NON_BLOCKING
    end else begin
      result_valid <= 1'b0;
`else
    end else if (result_valid && m_axis_dout_tready) begin
      result_valid <= 1'b0;
`endif
    end
  end
endmodule

module FpgaIntegerDividerAdapterTb #(
  parameter integer NON_BLOCKING = 0
);
  localparam [4:0] DIV = 0, DIVU = 1, REM = 2, REMU = 3;
  localparam [4:0] DIVW = 4, DIVUW = 5, REMW = 6, REMUW = 7;

  logic clock = 0;
  logic reset = 1;
  logic req_valid = 0;
  logic req_ready;
  logic [63:0] operand_a = 0;
  logic [63:0] operand_b = 0;
  logic [4:0] operation = 0;
  logic [3:0] request_tag = 0;
  logic resp_ready = 1;
  logic resp_valid;
  logic [63:0] result;
  logic [4:0] exception_flags;
  logic illegal;
  logic [3:0] response_tag;

  always #5 clock = ~clock;

  npc_int_divider_adapter #(.WIDTH(64), .TAG_WIDTH(4), .LATENCY(35), .NON_BLOCKING(NON_BLOCKING)) dut (
    .clock(clock),
    .reset(reset),
    .arithmetic_req_ready(req_ready),
    .arithmetic_req_valid(req_valid),
    .arithmetic_req_bits_operandA(operand_a),
    .arithmetic_req_bits_operandB(operand_b),
    .arithmetic_req_bits_operandC(64'b0),
    .arithmetic_req_bits_operation(operation),
    .arithmetic_req_bits_roundingMode(3'b0),
    .arithmetic_req_bits_pc(64'b0),
    .arithmetic_req_bits_instruction(32'b0),
    .arithmetic_req_bits_fcsr(8'b0),
    .arithmetic_req_bits_tag(request_tag),
    .arithmetic_resp_ready(resp_ready),
    .arithmetic_resp_valid(resp_valid),
    .arithmetic_resp_bits_result(result),
    .arithmetic_resp_bits_exceptionFlags(exception_flags),
    .arithmetic_resp_bits_illegal(illegal),
    .arithmetic_resp_bits_tag(response_tag)
  );

  task automatic expect_operation(
    input logic [4:0] selected_operation,
    input logic [63:0] a,
    input logic [63:0] b,
    input logic [63:0] expected_result,
    input logic [3:0] expected_tag
  );
    @(negedge clock);
    resp_ready = 1;
    operation = selected_operation;
    operand_a = a;
    operand_b = b;
    request_tag = expected_tag;
    req_valid = 1;
    while (!req_ready) @(negedge clock);
    @(negedge clock);
    req_valid = 0;
    while (!resp_valid) @(negedge clock);
    if (result !== expected_result || exception_flags !== 0 || illegal || response_tag !== expected_tag) begin
      $fatal(1, "op=%0d a=%h b=%h result=%h tag=%h expected=%h/%h", selected_operation,
        a, b, result, response_tag, expected_result, expected_tag);
    end
    @(negedge clock);
  endtask

  task automatic expect_single_in_flight;
    @(negedge clock);
    operation = DIV;
    operand_a = 64'd17;
    operand_b = 64'd5;
    request_tag = 4'hf;
    req_valid = 1;
    while (!req_ready) @(negedge clock);
    @(negedge clock);
    req_valid = 0;
    if (req_ready)
      $fatal(1, "divider accepted a second request while the result was pending");
    while (!resp_valid) @(negedge clock);
    if (result !== 64'd3 || response_tag !== 4'hf)
      $fatal(1, "single-in-flight divider response mismatch");

    resp_ready = 0;
    repeat (3) begin
      @(negedge clock);
      if (!resp_valid || req_ready)
        $fatal(1, "divider released the request slot before the response handshake");
    end
    resp_ready = 1;
    @(negedge clock);
    if (!req_ready)
      $fatal(1, "divider did not release the request slot after the response handshake");
  endtask

  initial begin
    repeat (2) @(negedge clock);
    reset = 0;

    expect_operation(DIV,   -64'sd20, 64'd3, 64'hffff_ffff_ffff_fffa, 4'h1);
    expect_operation(DIVU,  64'hffff_ffff_ffff_fff0, 64'd2, 64'h7fff_ffff_ffff_fff8, 4'h2);
    expect_operation(REM,   -64'sd20, 64'd3, 64'hffff_ffff_ffff_fffe, 4'h3);
    expect_operation(REMU,  64'd20, 64'd3, 64'd2, 4'h4);
    expect_operation(DIVW,  64'h0000_0000_ffff_ffec, 64'd3, 64'hffff_ffff_ffff_fffa, 4'h5);
    expect_operation(DIVUW, 64'h0000_0000_ffff_fff0, 64'd2, 64'h0000_0000_7fff_fff8, 4'h6);
    expect_operation(REMW,  64'h0000_0000_ffff_ffec, 64'd3, 64'hffff_ffff_ffff_fffe, 4'h7);
    expect_operation(REMUW, 64'h0000_0000_ffff_fff1, 64'd4, 64'd1, 4'h8);
    expect_operation(DIV,   64'd5, 64'd0, 64'hffff_ffff_ffff_ffff, 4'h9);
    expect_operation(REM,   64'd5, 64'd0, 64'd5, 4'ha);
    expect_operation(DIV,   64'h8000_0000_0000_0000, -64'sd1, 64'h8000_0000_0000_0000, 4'hb);
    expect_single_in_flight();

    $display("FPGA RV64 integer divider adapter tests passed");
    $finish;
  end
endmodule
