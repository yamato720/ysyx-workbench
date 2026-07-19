module FpgaMailboxResetTb;
  logic clock = 0;
  logic reset = 1;
  logic aw_valid = 0;
  logic aw_ready;
  logic w_valid = 0;
  logic w_ready;
  logic b_valid;
  logic request_valid = 0;
  logic request_ready;
  logic core_reset;
  logic interrupt;

  always #5 clock = ~clock;

  FpgaFallbackMailbox dut (
    .clock(clock),
    .reset(reset),
    .io_axi_aw_ready(aw_ready),
    .io_axi_aw_valid(aw_valid),
    .io_axi_aw_bits_addr(32'h80),
    .io_axi_w_ready(w_ready),
    .io_axi_w_valid(w_valid),
    .io_axi_w_bits_data(32'h1),
    .io_axi_w_bits_strb(4'h1),
    .io_axi_b_ready(1'b1),
    .io_axi_b_valid(b_valid),
    .io_axi_ar_valid(1'b0),
    .io_axi_ar_bits_addr(32'h0),
    .io_axi_r_ready(1'b1),
    .io_core_request_ready(request_ready),
    .io_core_request_valid(request_valid),
    .io_core_request_bits_sequence(32'h9),
    .io_core_request_bits_pc(32'h8000_0000),
    .io_core_request_bits_instruction(32'h53),
    .io_core_request_bits_operandA(32'h3f80_0000),
    .io_core_request_bits_operandB(32'h4000_0000),
    .io_core_request_bits_operandC(32'h0),
    .io_core_request_bits_fcsr(8'h0),
    .io_core_request_bits_operation(5'h0),
    .io_core_request_bits_roundingMode(3'h0),
    .io_core_response_ready(1'b0),
    .io_core_busy(1'b1),
    .io_runtime_commitValid(1'b0),
    .io_putch_valid(1'b0),
    .io_putch_bits(8'h0),
    .io_coreReset(core_reset),
    .io_interrupt(interrupt)
  );

  initial begin
    repeat (2) @(negedge clock);
    reset = 0;

    @(negedge clock);
    if (!request_ready) $fatal(1, "mailbox was not ready after reset");
    request_valid = 1;
    @(negedge clock);
    request_valid = 0;
    if (request_ready || !interrupt) $fatal(1, "mailbox did not retain the fallback request");

    aw_valid = 1;
    w_valid = 1;
    while (!aw_ready || !w_ready) @(negedge clock);
    @(negedge clock);
    aw_valid = 0;
    w_valid = 0;
    @(negedge clock);

    if (!core_reset) $fatal(1, "AXI-Lite reset write did not hold the core in reset");
    if (!request_ready) $fatal(1, "core reset did not discard the outstanding request");
    if (interrupt) $fatal(1, "discarded request still asserted the mailbox interrupt");

    request_valid = 1;
    @(negedge clock);
    request_valid = 0;
    if (request_ready) $fatal(1, "mailbox did not accept a new request after reset recovery");
    $display("FPGA mailbox reset RTL tests passed");
    $finish;
  end
endmodule
