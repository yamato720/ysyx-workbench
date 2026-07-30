module FpgaDebugControlTb;
  logic clock = 0;
  logic reset = 1;
  logic aw_valid = 0;
  logic aw_ready;
  logic [31:0] aw_addr = 0;
  logic w_valid = 0;
  logic w_ready;
  logic [31:0] w_data = 0;
  logic b_valid;
  logic ar_valid = 0;
  logic ar_ready;
  logic [31:0] ar_addr = 0;
  logic r_valid;
  logic [31:0] r_data;
  logic core_busy = 0;
  logic dispatch_fire = 0;
  logic commit_valid = 0;
  logic [31:0] commit_instruction = 32'h0000_0013;
  logic completion_commit_valid = 0;
  logic [31:0] next_pc = 32'h8000_0000;
  logic [31:0] gpr10 = 32'h0000_005a;
  logic core_reset;
  logic dispatch_permit;
  logic guest_external_interrupt;
  logic mailbox_interrupt;

  always #5 clock = ~clock;

  FpgaRuntimeMailbox dut (
    .clock(clock),
    .reset(reset),
    .io_axi_aw_ready(aw_ready),
    .io_axi_aw_valid(aw_valid),
    .io_axi_aw_bits_addr(aw_addr),
    .io_axi_w_ready(w_ready),
    .io_axi_w_valid(w_valid),
    .io_axi_w_bits_data(w_data),
    .io_axi_w_bits_strb(4'hf),
    .io_axi_b_ready(1'b1),
    .io_axi_b_valid(b_valid),
    .io_axi_ar_ready(ar_ready),
    .io_axi_ar_valid(ar_valid),
    .io_axi_ar_bits_addr(ar_addr),
    .io_axi_r_ready(1'b1),
    .io_axi_r_valid(r_valid),
    .io_axi_r_bits_data(r_data),
    .io_runtime_sdb_nextArchitecturalPc(next_pc),
    .io_runtime_commitValid(commit_valid),
    .io_runtime_commitPc(next_pc - 4),
    .io_runtime_commitInstruction(commit_instruction),
    .io_runtime_commitNextPc(next_pc),
    .io_runtime_completionCommitValid(completion_commit_valid),
    .io_runtime_completionCommitPc(next_pc - 4),
    .io_runtime_completionCommitNextPc(next_pc),
    .io_runtime_completionCode(gpr10),
    .io_runtime_sdb_mstatus(32'h5566_7788),
    .io_runtime_sdb_mcause(32'h0506_0708),
    .io_runtime_sdb_mepc(32'h4433_2211),
    .io_runtime_sdb_mtvec(32'h8000_0100),
    .io_runtime_sdb_coreBusy(core_busy),
    .io_runtime_sdb_dispatchFire(dispatch_fire),
    .io_runtime_sdb_gprs_10(gpr10),
    .io_putch_valid(1'b0),
    .io_coreReset(core_reset),
    .io_dispatchPermit(dispatch_permit),
    .io_guestExternalInterrupt(guest_external_interrupt),
    .io_mailboxInterrupt(mailbox_interrupt)
  );

  task automatic write_register(input logic [31:0] address, input logic [31:0] value);
    @(negedge clock);
    aw_addr = address;
    w_data = value;
    aw_valid = 1;
    w_valid = 1;
    while (!aw_ready || !w_ready) @(negedge clock);
    @(negedge clock);
    aw_valid = 0;
    w_valid = 0;
    while (!b_valid) @(negedge clock);
  endtask

  task automatic read_register(input logic [31:0] address, output logic [31:0] value);
    @(negedge clock);
    ar_addr = address;
    ar_valid = 1;
    while (!ar_ready) @(negedge clock);
    @(negedge clock);
    ar_valid = 0;
    while (!r_valid) @(negedge clock);
    value = r_data;
  endtask

  task automatic command(input logic [31:0] command_seq, input logic [31:0] opcode);
    write_register(32'h40, command_seq);
    write_register(32'h44, opcode);
  endtask

  logic [31:0] value;
  initial begin
    repeat (2) @(negedge clock);
    reset = 0;

    read_register(32'hfc, value);
    if (value !== 32'h4e50_4306) $fatal(1, "missing v6 protocol signature: %h", value);
    read_register(32'h3c, value);
    if ((value & 7) !== 7) $fatal(1, "missing debug capabilities: %h", value);

    core_busy = 1;
    write_register(32'h80, 0);
    if (dispatch_permit) $fatal(1, "reset release incorrectly permitted dispatch");
    read_register(32'h4c, value);
    if ((value & 32'h4) == 0) $fatal(1, "reset release did not enter halting state: %h", value);
    core_busy = 0;
    repeat (2) @(negedge clock);
    read_register(32'h4c, value);
    if ((value & 32'h2) == 0) $fatal(1, "core did not reach initial halted state: %h", value);
    read_register(32'h50, value);
    if (value !== 32'h8000_0000) $fatal(1, "initial stop PC mismatch: %h", value);

    write_register(32'h5c, 1);
    read_register(32'hec, value);
    if (value !== 32'h0506_0708) $fatal(1, "mcause CSR low snapshot mismatch: %h", value);
    read_register(32'hf8, value);
    if (value !== 32'h0000_0000) $fatal(1, "RV32 mcause high snapshot mismatch: %h", value);

    command(1, 2);
    if (!dispatch_permit) $fatal(1, "resume did not open dispatch gate");
    read_register(32'h48, value);
    if (value !== 1) $fatal(1, "resume completion sequence mismatch: %h", value);

    command(2, 1);
    if (dispatch_permit) $fatal(1, "halt did not close dispatch gate immediately");
    repeat (2) @(negedge clock);
    command(3, 3);
    if (!dispatch_permit) $fatal(1, "step did not grant its dispatch credit");
    @(negedge clock);
    dispatch_fire = 1;
    @(negedge clock);
    dispatch_fire = 0;
    if (dispatch_permit) $fatal(1, "step granted more than one dispatch credit");
    core_busy = 1;
    commit_valid = 1;
    next_pc = 32'h8000_0004;
    @(negedge clock);
    commit_valid = 0;
    repeat (2) @(negedge clock);
    read_register(32'h48, value);
    if (value === 3) $fatal(1, "step completed before the backend drained");
    core_busy = 0;
    repeat (2) @(negedge clock);
    read_register(32'h48, value);
    if (value !== 3) $fatal(1, "step completion sequence mismatch: %h", value);
    read_register(32'h50, value);
    if (value !== 32'h8000_0004) $fatal(1, "step stop PC mismatch: %h", value);
    read_register(32'hb4, value);
    if (value !== 32'h8000_0000) $fatal(1, "step last commit PC mismatch: %h", value);
    read_register(32'hd0, value);
    if (value !== 1) $fatal(1, "step did not commit exactly once: %h", value);

    command(4, 2);
    core_busy = 1;
    command(5, 1);
    if (dispatch_permit) $fatal(1, "running halt left dispatch enabled");
    read_register(32'h48, value);
    if (value === 5) $fatal(1, "halt completed before drain");
    core_busy = 0;
    repeat (2) @(negedge clock);
    read_register(32'h48, value);
    if (value !== 5) $fatal(1, "halt completion sequence mismatch: %h", value);

    command(5, 1);
    read_register(32'h4c, value);
    if ((value & 32'h20) == 0) $fatal(1, "duplicate sequence did not report protocol error");
    write_register(32'h80, 1);
    write_register(32'h80, 0);
    read_register(32'h4c, value);
    if ((value & 32'h20) != 0) $fatal(1, "reset did not clear protocol error: %h", value);

    write_register(32'h70, 1);
    if (!guest_external_interrupt) $fatal(1, "mailbox did not raise guest MEIP");
    read_register(32'h70, value);
    if (value !== 1) $fatal(1, "guest MEIP register mismatch: %h", value);
    write_register(32'h70, 0);
    if (guest_external_interrupt) $fatal(1, "mailbox did not lower guest MEIP");

    command(6, 2);
    core_busy = 1;
    commit_instruction = 32'h7c05_1073;
    commit_valid = 1;
    completion_commit_valid = 1;
    next_pc = 32'h8000_0008;
    #1;
    if (!dispatch_permit) $fatal(1, "mtestexit changed debug dispatch state");
    @(negedge clock);
    commit_valid = 0;
    completion_commit_valid = 0;
    if (!core_reset) $fatal(1, "completion was not latched at the mtestexit commit boundary");
    repeat (2) @(negedge clock);
    if (!core_reset) $fatal(1, "completion did not hold the core in reset");
    if (!mailbox_interrupt) $fatal(1, "completion did not notify the host");
    read_register(32'h84, value);
    if ((value & 32'h10) == 0) $fatal(1, "completion pending bit is clear: %h", value);
    read_register(32'h60, value);
    if (value !== 32'h8000_0004) $fatal(1, "completion PC mismatch: %h", value);
    read_register(32'h68, value);
    if (value !== 32'h8000_0008) $fatal(1, "completion next PC mismatch: %h", value);
    read_register(32'hd8, value);
    if (value !== 32'h0000_005a) $fatal(1, "completion exit code mismatch: %h", value);
    read_register(32'h4c, value);
    if ((value & 32'h1) == 0 || (value & 32'h2) != 0)
      $fatal(1, "mtestexit was reclassified as a debug halt: %h", value);
    write_register(32'h80, 32'h0000_000a);
    if (core_reset || mailbox_interrupt)
      $fatal(1, "start did not acknowledge completion and release reset");

    $display("FPGA debug control RTL tests passed");
    $finish;
  end
endmodule
