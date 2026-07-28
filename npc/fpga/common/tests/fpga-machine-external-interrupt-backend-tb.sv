module FpgaMachineExternalInterruptBackendTb;
  logic clock = 0;
  logic reset = 1;
  logic io_interrupt = 0;
  logic [31:0] io_interruptPc = 0;
  logic io_dispatch_valid = 0;
  logic io_dispatch_ready;
  logic [31:0] io_dispatch_bits_pc = 0;
  logic [31:0] io_dispatch_bits_instruction = 0;
  logic [31:0] io_dispatch_bits_immediate = 0;
  logic [4:0] io_dispatch_bits_rd = 0;
  logic [4:0] io_dispatch_bits_rs1 = 0;
  logic [4:0] io_dispatch_bits_rs2 = 0;
  logic [2:0] io_dispatch_bits_funct3 = 0;
  logic [11:0] io_dispatch_bits_csrAddress = 0;
  logic io_dispatch_bits_branch = 0;
  logic io_dispatch_bits_loadEnable = 0;
  logic io_dispatch_bits_writebackFromMemory = 0;
  logic io_dispatch_bits_storeEnable = 0;
  logic io_dispatch_bits_useImmediate = 0;
  logic io_dispatch_bits_registerWriteEnable = 0;
  logic io_dispatch_bits_usesRs1 = 0;
  logic io_dispatch_bits_usesRs2 = 0;
  logic [1:0] io_dispatch_bits_executionUnit = 0;
  logic [4:0] io_dispatch_bits_aluCtrl = 0;
  logic io_dispatch_bits_trapEnable = 0;
  logic [31:0] io_dispatch_bits_trapCause = 0;
  logic io_dispatch_bits_mretEnable = 0;
  logic io_dispatch_bits_csrEnable = 0;
  logic [1:0] io_dispatch_bits_csrOperation = 0;
  logic io_dispatch_bits_csrUseImmediate = 0;
  logic io_dispatch_bits_csrReadWritebackEnable = 0;
  logic io_axi_aw_ready = 1;
  logic io_axi_w_ready = 1;
  logic io_axi_b_valid = 0;
  logic [1:0] io_axi_b_bits_resp = 0;
  logic io_axi_ar_ready = 1;
  logic io_axi_r_valid = 0;
  logic [31:0] io_axi_r_bits_data = 0;
  logic [1:0] io_axi_r_bits_resp = 0;
  logic io_redirectValid;
  logic [31:0] io_redirectTarget;
  logic [31:0] io_debug_mstatus;
  logic [31:0] io_debug_mcause;
  logic [31:0] io_debug_mepc;
  logic io_debug_commitValid;
  logic io_debug_completionCommitValid;
  logic [31:0] io_debug_completionCommitPc;
  logic [31:0] io_debug_completionCommitNextPc;
  logic saw_commit_boundary_interrupt;
  logic saw_completion_commit;

  always #5 clock = ~clock;

  NpcBackend dut (
    .clock,
    .reset,
    .io_interrupt,
    .io_interruptPc,
    .io_dispatch_valid,
    .io_dispatch_ready,
    .io_dispatch_bits_pc,
    .io_dispatch_bits_instruction,
    .io_dispatch_bits_immediate,
    .io_dispatch_bits_rd,
    .io_dispatch_bits_rs1,
    .io_dispatch_bits_rs2,
    .io_dispatch_bits_funct3,
    .io_dispatch_bits_csrAddress,
    .io_dispatch_bits_branch,
    .io_dispatch_bits_loadEnable,
    .io_dispatch_bits_writebackFromMemory,
    .io_dispatch_bits_storeEnable,
    .io_dispatch_bits_useImmediate,
    .io_dispatch_bits_registerWriteEnable,
    .io_dispatch_bits_usesRs1,
    .io_dispatch_bits_usesRs2,
    .io_dispatch_bits_executionUnit,
    .io_dispatch_bits_aluCtrl,
    .io_dispatch_bits_trapEnable,
    .io_dispatch_bits_trapCause,
    .io_dispatch_bits_mretEnable,
    .io_dispatch_bits_csrEnable,
    .io_dispatch_bits_csrOperation,
    .io_dispatch_bits_csrUseImmediate,
    .io_dispatch_bits_csrReadWritebackEnable,
    .io_axi_aw_ready,
    .io_axi_w_ready,
    .io_axi_b_valid,
    .io_axi_b_bits_resp,
    .io_axi_ar_ready,
    .io_axi_r_valid,
    .io_axi_r_bits_data,
    .io_axi_r_bits_resp,
    .io_redirectValid,
    .io_redirectTarget,
    .io_debug_mstatus,
    .io_debug_mcause,
    .io_debug_mepc,
    .io_debug_commitValid,
    .io_debug_completionCommitValid,
    .io_debug_completionCommitPc,
    .io_debug_completionCommitNextPc
  );

  always @(posedge clock) begin
    if (io_debug_completionCommitValid) begin
      saw_completion_commit = 1;
      if (io_debug_completionCommitPc !== 32'h8000_0014)
        $fatal(1, "completion commit PC mismatch: %h", io_debug_completionCommitPc);
      if (io_debug_completionCommitNextPc !== 32'h8000_0018)
        $fatal(1, "mtestexit did not retire normally: %h",
          io_debug_completionCommitNextPc);
    end
  end

  task automatic clear_dispatch;
    begin
      io_dispatch_bits_pc = 0;
      io_dispatch_bits_instruction = 0;
      io_dispatch_bits_immediate = 0;
      io_dispatch_bits_rd = 0;
      io_dispatch_bits_rs1 = 0;
      io_dispatch_bits_rs2 = 0;
      io_dispatch_bits_funct3 = 0;
      io_dispatch_bits_csrAddress = 0;
      io_dispatch_bits_branch = 0;
      io_dispatch_bits_loadEnable = 0;
      io_dispatch_bits_writebackFromMemory = 0;
      io_dispatch_bits_storeEnable = 0;
      io_dispatch_bits_useImmediate = 0;
      io_dispatch_bits_registerWriteEnable = 0;
      io_dispatch_bits_usesRs1 = 0;
      io_dispatch_bits_usesRs2 = 0;
      io_dispatch_bits_executionUnit = 0;
      io_dispatch_bits_aluCtrl = 0;
      io_dispatch_bits_trapEnable = 0;
      io_dispatch_bits_trapCause = 0;
      io_dispatch_bits_mretEnable = 0;
      io_dispatch_bits_csrEnable = 0;
      io_dispatch_bits_csrOperation = 0;
      io_dispatch_bits_csrUseImmediate = 0;
      io_dispatch_bits_csrReadWritebackEnable = 0;
    end
  endtask

  task automatic wait_for_commit;
    begin
      do @(negedge clock); while (!io_debug_commitValid);
    end
  endtask

  task automatic issue_lui(input logic [31:0] pc, input logic [4:0] rd, input logic [31:0] value);
    begin
      @(negedge clock);
      clear_dispatch();
      io_dispatch_bits_pc = pc;
      io_dispatch_bits_rd = rd;
      io_dispatch_bits_immediate = value;
      io_dispatch_bits_useImmediate = 1;
      io_dispatch_bits_registerWriteEnable = 1;
      io_dispatch_bits_aluCtrl = 5'd17; // NpcAluOp.Integer.LUI
      io_dispatch_valid = 1;
      do @(posedge clock); while (!io_dispatch_ready);
      @(negedge clock);
      io_dispatch_valid = 0;
    end
  endtask

  task automatic issue_csr_write(
    input logic [31:0] pc,
    input logic [11:0] csr,
    input logic immediate,
    input logic [4:0] source
  );
    begin
      @(negedge clock);
      clear_dispatch();
      io_dispatch_bits_pc = pc;
      io_dispatch_bits_rs1 = source;
      io_dispatch_bits_csrAddress = csr;
      io_dispatch_bits_csrEnable = 1;
      io_dispatch_bits_csrUseImmediate = immediate;
      io_dispatch_valid = 1;
      do @(posedge clock); while (!io_dispatch_ready);
      @(negedge clock);
      io_dispatch_valid = 0;
    end
  endtask

  task automatic issue_mret(input logic [31:0] pc);
    begin
      @(negedge clock);
      clear_dispatch();
      io_dispatch_bits_pc = pc;
      io_dispatch_bits_mretEnable = 1;
      io_dispatch_valid = 1;
      do @(posedge clock); while (!io_dispatch_ready);
      @(negedge clock);
      io_dispatch_valid = 0;
    end
  endtask

  task automatic issue_addi(input logic [31:0] pc, input logic [4:0] rd, input logic [31:0] value);
    begin
      @(negedge clock);
      clear_dispatch();
      io_dispatch_bits_pc = pc;
      io_dispatch_bits_rd = rd;
      io_dispatch_bits_immediate = value;
      io_dispatch_bits_useImmediate = 1;
      io_dispatch_bits_registerWriteEnable = 1;
      io_dispatch_bits_aluCtrl = 5'd0; // NpcAluOp.Integer.ADD
      io_dispatch_valid = 1;
      do @(posedge clock); while (!io_dispatch_ready);
      @(negedge clock);
      io_dispatch_valid = 0;
    end
  endtask

  task automatic issue_ebreak(input logic [31:0] pc);
    begin
      @(negedge clock);
      clear_dispatch();
      io_dispatch_bits_pc = pc;
      io_dispatch_bits_instruction = 32'h0010_0073;
      io_dispatch_bits_trapEnable = 1;
      io_dispatch_bits_trapCause = 3;
      io_dispatch_valid = 1;
      do @(posedge clock); while (!io_dispatch_ready);
      @(negedge clock);
      io_dispatch_valid = 0;
    end
  endtask

  task automatic issue_mtestexit(input logic [31:0] pc);
    begin
      @(negedge clock);
      clear_dispatch();
      io_dispatch_bits_pc = pc;
      io_dispatch_bits_instruction = 32'h7c05_1073; // csrw 0x7c0, a0
      io_dispatch_bits_rs1 = 5'd10;
      io_dispatch_bits_csrAddress = 12'h7c0;
      io_dispatch_bits_csrEnable = 1;
      io_dispatch_valid = 1;
      do @(posedge clock); while (!io_dispatch_ready);
      @(negedge clock);
      io_dispatch_valid = 0;
    end
  endtask

  initial begin
    clear_dispatch();
    repeat (3) @(negedge clock);
    reset = 0;

    // x1 = MEIE, then set mstatus.MIE and mie.MEIE through the normal CSR path.
    issue_lui(32'h8000_0000, 5'd1, 32'h0000_0800);
    wait_for_commit();
    issue_csr_write(32'h8000_0004, 12'h300, 1'b1, 5'd8);
    wait_for_commit();
    issue_csr_write(32'h8000_0008, 12'h304, 1'b0, 5'd1);
    wait_for_commit();

    io_interruptPc = 32'h8000_000c;
    @(negedge clock);
    io_interrupt = 1;
    #1;
    if (io_dispatch_ready) $fatal(1, "enabled interrupt did not close dispatch");
    if (!io_redirectValid || io_redirectTarget !== 32'h0)
      $fatal(1, "idle interrupt did not redirect to mtvec: valid=%b target=%h",
        io_redirectValid, io_redirectTarget);

    @(negedge clock);
    #1;
    if (io_debug_mcause !== 32'h8000_000b) $fatal(1, "backend mcause mismatch: %h", io_debug_mcause);
    if (io_debug_mepc !== 32'h8000_000c) $fatal(1, "idle interrupt mepc mismatch: %h", io_debug_mepc);
    if (io_debug_mstatus[3] || !io_debug_mstatus[7] || io_debug_mstatus[12:11] !== 2'b11)
      $fatal(1, "backend trap mstatus transition mismatch: %h", io_debug_mstatus);

    // MRET reenables MIE. Assert the next interrupt after an ADDI has entered the
    // pipeline, so the trap must wait for that instruction and save its next PC.
    io_interrupt = 0;
    issue_mret(32'h8000_000c);
    wait_for_commit();
    issue_addi(32'h8000_000c, 5'd2, 32'h1);
    io_interrupt = 1;
    saw_commit_boundary_interrupt = 0;
    repeat (16) begin
      @(negedge clock);
      if (io_debug_mcause === 32'h8000_000b && io_debug_mepc === 32'h8000_0010)
        saw_commit_boundary_interrupt = 1;
    end
    if (!saw_commit_boundary_interrupt)
      $fatal(1, "commit-boundary interrupt did not save the retiring instruction next PC");

    saw_completion_commit = 0;
    issue_ebreak(32'h8000_0010);
    repeat (16) @(negedge clock);
    if (saw_completion_commit)
      $fatal(1, "ebreak incorrectly emitted a completion record");
    if (io_debug_mcause !== 32'h3 || io_debug_mepc !== 32'h8000_0010)
      $fatal(1, "ebreak did not retain its normal breakpoint trap state");

    issue_mtestexit(32'h8000_0014);
    repeat (16) @(negedge clock);
    if (!saw_completion_commit)
      $fatal(1, "mtestexit did not emit a commit-boundary completion record");

    $display("Machine external interrupt backend RTL tests passed");
    $finish;
  end
endmodule
