module FpgaFloatingDirectTb;
  logic clock = 0;
  logic reset = 1;
  logic req_valid = 0;
  logic req_ready;
  logic [31:0] operand_a = 0;
  logic [31:0] operand_b = 0;
  logic [4:0] operation = 0;
  logic [3:0] request_tag = 0;
  logic resp_valid;
  logic [31:0] result;
  logic [4:0] exception_flags;
  logic illegal;
  logic [3:0] response_tag;

  always #5 clock = ~clock;

  FpgaFloatingDirectOperator dut (
    .clock(clock),
    .reset(reset),
    .io_req_ready(req_ready),
    .io_req_valid(req_valid),
    .io_req_bits_operandA(operand_a),
    .io_req_bits_operandB(operand_b),
    .io_req_bits_operation(operation),
    .io_req_bits_tag(request_tag),
    .io_resp_ready(1'b1),
    .io_resp_valid(resp_valid),
    .io_resp_bits_result(result),
    .io_resp_bits_exceptionFlags(exception_flags),
    .io_resp_bits_illegal(illegal),
    .io_resp_bits_tag(response_tag)
  );

  task automatic expect_operation(
    input logic [4:0] selected_operation,
    input logic [31:0] a,
    input logic [31:0] b,
    input logic [31:0] expected_result
  );
    @(negedge clock);
    operation = selected_operation;
    operand_a = a;
    operand_b = b;
    request_tag = 4'h7;
    req_valid = 1;
    while (!req_ready) @(negedge clock);
    @(negedge clock);
    req_valid = 0;
    while (!resp_valid) @(negedge clock);
    if (result !== expected_result || exception_flags !== 0 || illegal || response_tag !== 4'h7) begin
      $fatal(1, "operation %0d: result=%h flags=%h illegal=%b tag=%h expected=%h",
        selected_operation, result, exception_flags, illegal, response_tag, expected_result);
    end
    @(negedge clock);
  endtask

  initial begin
    repeat (2) @(negedge clock);
    reset = 0;

    // 板卡终端 Config 固定 RV32F：1.0 是正正规数，min(1,-1) 为 -1。
    expect_operation(5'h1a, 32'h3f80_0000, 0, 32'h0000_0040);
    expect_operation(5'h09, 32'h3f80_0000, 32'hbf80_0000, 32'hbf80_0000);
    expect_operation(5'h0c, 32'h3f80_0000, 32'h3f80_0000, 32'h3f80_0000);

    // FMV.X.W 只传送位模式，按定义绕过 NaN-box 检查。
    expect_operation(5'h19, 32'h3f80_0000, 0, 32'h3f80_0000);
    $display("FPGA direct floating-point RTL tests passed");
    $finish;
  end
endmodule
