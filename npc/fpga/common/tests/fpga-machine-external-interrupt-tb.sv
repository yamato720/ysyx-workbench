module FpgaMachineExternalInterruptTb;
`ifdef NPC_TEST_RV64
  localparam int XLEN = 64;
`else
  localparam int XLEN = 32;
`endif
  localparam logic [XLEN-1:0] mtvec_vectored = {{(XLEN - 32){1'b0}}, 32'h8000_0101};
  localparam logic [XLEN-1:0] mtvec_vectored_base = {{(XLEN - 32){1'b0}}, 32'h8000_0100};
  localparam logic [XLEN-1:0] mtvec_vectored_mei = {{(XLEN - 32){1'b0}}, 32'h8000_012c};
  localparam logic [XLEN-1:0] mtvec_direct = {{(XLEN - 32){1'b0}}, 32'h8000_0180};
  localparam logic [XLEN-1:0] trap_epc_value = {{(XLEN - 32){1'b0}}, 32'h8000_0040};

  logic clock = 0;
  logic reset = 1;
  logic [11:0] address = 0;
  logic [XLEN-1:0] write_data = 0;
  logic write_enable = 0;
  logic external_interrupt = 0;
  logic trap_enable = 0;
  logic [XLEN-1:0] trap_cause = 0;
  logic [XLEN-1:0] trap_epc = 0;
  logic mret = 0;
  logic [XLEN-1:0] read_data;
  logic [XLEN-1:0] trap_vector;
  logic [XLEN-1:0] external_interrupt_trap_vector;
  logic [XLEN-1:0] machine_exception_pc;
  logic machine_external_interrupt_pending;
  logic [XLEN-1:0] mstatus;
  logic [XLEN-1:0] mcause;

  always #5 clock = ~clock;

  CsrFile dut (
    .clock(clock),
    .reset(reset),
    .io_address(address),
    .io_writeData(write_data),
    .io_writeEnable(write_enable),
    .io_accessAllowed(1'b1),
    .io_readData(read_data),
    .io_externalInterrupt(external_interrupt),
    .io_trapEnable(trap_enable),
    .io_trapCause(trap_cause),
    .io_trapEpc(trap_epc),
    .io_trapVector(trap_vector),
    .io_externalInterruptTrapVector(external_interrupt_trap_vector),
    .io_machineExternalInterruptPending(machine_external_interrupt_pending),
    .io_mret(mret),
    .io_machineExceptionPc(machine_exception_pc),
    .io_floatingCommit(1'b0),
    .io_floatingExceptionFlags(5'b0),
    .io_mstatusOut(mstatus),
    .io_mcauseOut(mcause)
  );

  task automatic write_csr(input logic [11:0] csr, input logic [XLEN-1:0] value);
    @(negedge clock);
    address = csr;
    write_data = value;
    write_enable = 1;
    @(negedge clock);
    write_enable = 0;
  endtask

  task automatic read_csr(input logic [11:0] csr, output logic [XLEN-1:0] value);
    address = csr;
    #1;
    value = read_data;
  endtask

  logic [XLEN-1:0] value;
  logic [XLEN-1:0] expected_interrupt_cause;
  initial begin
    repeat (2) @(negedge clock);
    reset = 0;

    external_interrupt = 1;
    read_csr(12'h344, value);
    if ((value & 'h800) == 0) $fatal(1, "MEIP was not reflected in mip: %h", value);
    if (machine_external_interrupt_pending) $fatal(1, "interrupt bypassed global and local enables");

    write_csr(12'h300, 'h8);
    if (machine_external_interrupt_pending) $fatal(1, "interrupt bypassed MEIE");
    write_csr(12'h304, 'h800);
    if (!machine_external_interrupt_pending) $fatal(1, "MEIP/MEIE/MIE did not enable interrupt");

    write_csr(12'h305, mtvec_vectored);
    if (trap_vector !== mtvec_vectored_base) $fatal(1, "mtvec base was not aligned: %h", trap_vector);
    if (external_interrupt_trap_vector !== mtvec_vectored_mei)
      $fatal(1, "vectored MEI target mismatch: %h", external_interrupt_trap_vector);

    @(negedge clock);
    expected_interrupt_cause = {1'b1, {(XLEN - 1){1'b0}}} | 'hB;
    trap_cause = expected_interrupt_cause;
    trap_epc = trap_epc_value;
    trap_enable = 1;
    @(negedge clock);
    trap_enable = 0;
    read_csr(12'h342, value);
    if (value !== expected_interrupt_cause) $fatal(1, "machine external mcause mismatch: %h", value);
    read_csr(12'h341, value);
    if (value !== trap_epc_value) $fatal(1, "machine external mepc mismatch: %h", value);
    if (mstatus[3] || !mstatus[7] || mstatus[12:11] !== 2'b11)
      $fatal(1, "trap mstatus transition mismatch: %h", mstatus);

    @(negedge clock);
    mret = 1;
    @(negedge clock);
    mret = 0;
    if (!mstatus[3] || !mstatus[7] || mstatus[12:11] !== 2'b00)
      $fatal(1, "mret mstatus transition mismatch: %h", mstatus);

    write_csr(12'h305, mtvec_direct);
    if (external_interrupt_trap_vector !== mtvec_direct)
      $fatal(1, "direct MEI target mismatch: %h", external_interrupt_trap_vector);
    external_interrupt = 0;
    #1;
    if (machine_external_interrupt_pending) $fatal(1, "MEIP clear did not clear interrupt pending");

    $display("Machine external interrupt CSR tests passed");
    $finish;
  end
endmodule
