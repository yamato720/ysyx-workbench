module npc_int_multiplier_ip (
  input  wire        CLK,
  input  wire [63:0] A,
  input  wire [63:0] B,
  output wire [127:0] P
);
  assign P = A * B;
endmodule

module FpgaIntegerMultiplierAdapterTb;
  localparam [4:0] MUL = 0, MULH = 1, MULHSU = 2, MULHU = 3, MULW = 4;

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

  npc_int_multiplier_adapter #(.WIDTH(64), .TAG_WIDTH(4), .LATENCY(3)) dut (
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
    operation = MUL;
    operand_a = 64'd7;
    operand_b = 64'd9;
    request_tag = 4'hf;
    req_valid = 1;
    while (!req_ready) @(negedge clock);
    @(negedge clock);
    req_valid = 0;
    if (req_ready)
      $fatal(1, "adapter accepted a second request while the IP result was pending");
    while (!resp_valid) @(negedge clock);
    if (result !== 64'd63 || response_tag !== 4'hf)
      $fatal(1, "single-in-flight response mismatch");

    resp_ready = 0;
    repeat (3) begin
      @(negedge clock);
      if (!resp_valid || req_ready)
        $fatal(1, "adapter released the request slot before the response handshake");
    end
    resp_ready = 1;
    @(negedge clock);
    if (!req_ready)
      $fatal(1, "adapter did not release the request slot after the response handshake");
  endtask

  initial begin
    repeat (2) @(negedge clock);
    reset = 0;

    expect_operation(MUL,    64'hffff_ffff_ffff_ffff, 64'd2, 64'hffff_ffff_ffff_fffe, 4'h1);
    expect_operation(MULH,   -64'sd2,                  64'd3, 64'hffff_ffff_ffff_ffff, 4'h2);
    expect_operation(MULH,   -64'sd2,                 -64'sd3, 64'd0,                 4'h3);
    expect_operation(MULHSU, -64'sd2,                  64'd3, 64'hffff_ffff_ffff_ffff, 4'h4);
    expect_operation(MULHU,  64'hffff_ffff_ffff_ffff, 64'd2, 64'd1,                  4'h5);
    expect_operation(MULW,   64'h0000_0000_ffff_ffff, 64'd2, 64'hffff_ffff_ffff_fffe, 4'h6);
    expect_single_in_flight();

    $display("FPGA RV64 integer multiplier adapter tests passed");
    $finish;
  end
endmodule
