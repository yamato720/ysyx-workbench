module FpgaRuntimeTraceWriterTb;
  localparam int FIFO_RECORDS = 2048;
  localparam int BURST_RECORDS = 16;
  localparam int RECORD_BYTES = 32;
  localparam logic [63:0] TRACE_BASE = 64'h0000_0001_4000_0000;

  logic clock = 1'b0;
  logic reset = 1'b1;
  logic telemetry_commit_valid = 1'b0;
  logic [63:0] telemetry_commit_pc = '0;
  logic [31:0] telemetry_commit_instruction = '0;
  logic [63:0] telemetry_commit_cycle = '0;
  logic [63:0] telemetry_stages [0:4];
  logic telemetry_completion_valid = 1'b0;
  logic clear = 1'b0;
  logic aw_ready = 1'b0;
  logic w_ready = 1'b0;
  logic b_valid = 1'b0;

  wire aw_valid;
  wire [63:0] aw_addr;
  wire [7:0] aw_len;
  wire w_valid;
  wire [255:0] w_data;
  wire w_last;
  wire b_ready;
  wire [63:0] records;
  wire [63:0] dropped;
  wire drained;

  int unsigned accepted_writes = 0;
  int unsigned accepted_addresses = 0;
  int unsigned cycles = 0;

  always #5 clock = ~clock;

  FpgaRuntimeTraceWriter dut (
    .clock(clock),
    .reset(reset),
    .io_telemetry_commitValid(telemetry_commit_valid),
    .io_telemetry_commitPc(telemetry_commit_pc),
    .io_telemetry_commitInstruction(telemetry_commit_instruction),
    .io_telemetry_commitCycle(telemetry_commit_cycle),
    .io_telemetry_stages_0(telemetry_stages[0]),
    .io_telemetry_stages_1(telemetry_stages[1]),
    .io_telemetry_stages_2(telemetry_stages[2]),
    .io_telemetry_stages_3(telemetry_stages[3]),
    .io_telemetry_stages_4(telemetry_stages[4]),
    .io_telemetry_completionValid(telemetry_completion_valid),
    .io_traceBase(TRACE_BASE),
    .io_clear(clear),
    .io_axi_aw_ready(aw_ready),
    .io_axi_aw_valid(aw_valid),
    .io_axi_aw_bits_addr(aw_addr),
    .io_axi_aw_bits_len(aw_len),
    .io_axi_w_ready(w_ready),
    .io_axi_w_valid(w_valid),
    .io_axi_w_bits_data(w_data),
    .io_axi_w_bits_last(w_last),
    .io_axi_b_ready(b_ready),
    .io_axi_b_valid(b_valid),
    .io_records(records),
    .io_dropped(dropped),
    .io_drained(drained)
  );

  task automatic fail(input string message);
    $fatal(1, "FPGA trace writer test failed: %s", message);
  endtask

  task automatic drive_commit(input int unsigned index);
    longint unsigned index64;
    @(negedge clock);
    index64 = {32'd0, index};
    telemetry_commit_valid = 1'b1;
    telemetry_commit_pc = 64'h0000_0000_8000_0000 + index64 * 4;
    telemetry_commit_instruction = 32'h0000_0013 + index;
    telemetry_commit_cycle = 64'd100 + index64;
    telemetry_stages[0] = 64'd1 + index64;
    telemetry_stages[1] = 64'd2 + index64;
    telemetry_stages[2] = index == FIFO_RECORDS - 1 ? 64'd65536 : 64'd3 + index64;
    telemetry_stages[3] = 64'd4 + index64;
    telemetry_stages[4] = 64'd5 + index64;
    @(posedge clock);
  endtask

  always @(posedge clock) begin
    logic aw_fire;
    logic b_fire;
    aw_fire = aw_valid && aw_ready;
    b_fire = b_valid && b_ready;
    case ({aw_fire, b_fire})
      2'b10, 2'b11: b_valid <= 1'b1;
      2'b01: b_valid <= 1'b0;
      default: b_valid <= b_valid;
    endcase

    if (aw_fire) begin
      if (aw_len != 8'd15)
        fail($sformatf("burst %0d length is %0d", accepted_addresses, aw_len));
      if (aw_addr != TRACE_BASE + accepted_addresses * BURST_RECORDS * RECORD_BYTES)
        fail($sformatf("burst %0d address is %h", accepted_addresses, aw_addr));
      accepted_addresses <= accepted_addresses + 1;
    end

    if (w_valid && w_ready) begin
      logic [63:0] expected_pc;
      logic [31:0] expected_instruction;
      logic [63:0] expected_cycle;
      logic [15:0] expected_stage0;
      logic [15:0] expected_stage1;
      logic [15:0] expected_stage2;
      logic [15:0] expected_stage3;
      logic [15:0] expected_stage4;
      logic [7:0] expected_saturation;
      longint unsigned write_index;
      write_index = {32'd0, accepted_writes};
      expected_pc = 64'h0000_0000_8000_0000 + accepted_writes * 4;
      expected_instruction = 32'h0000_0013 + accepted_writes;
      expected_cycle = 64'd100 + write_index;
      expected_stage0 = 16'd1 + accepted_writes[15:0];
      expected_stage1 = 16'd2 + accepted_writes[15:0];
      expected_stage2 = accepted_writes == FIFO_RECORDS - 1 ? 16'hffff :
          16'd3 + accepted_writes[15:0];
      expected_stage3 = 16'd4 + accepted_writes[15:0];
      expected_stage4 = 16'd5 + accepted_writes[15:0];
      expected_saturation = accepted_writes == FIFO_RECORDS - 1 ? 8'h04 : 8'h00;
      if (w_data[63:0] != expected_pc ||
          w_data[95:64] != expected_instruction ||
          w_data[159:96] != expected_cycle ||
          w_data[175:160] != expected_stage0 ||
          w_data[191:176] != expected_stage1 ||
          w_data[207:192] != expected_stage2 ||
          w_data[223:208] != expected_stage3 ||
          w_data[239:224] != expected_stage4 ||
          w_data[247:240] != 8'h07 ||
          w_data[255:248] != expected_saturation)
        fail($sformatf("record %0d packing/order mismatch", accepted_writes));
      if (w_last != ((accepted_writes % BURST_RECORDS) == BURST_RECORDS - 1))
        fail($sformatf("record %0d has incorrect WLAST", accepted_writes));
      accepted_writes <= accepted_writes + 1;
    end
  end

  initial begin
    for (int stage = 0; stage < 5; stage++) telemetry_stages[stage] = '0;
    repeat (3) @(posedge clock);
    reset = 1'b0;

    // Stall HBM so all accepted commits occupy the URAM FIFO.  The following
    // commit must be discarded without any upstream backpressure.
    for (int index = 0; index < FIFO_RECORDS + 1; index++) drive_commit(index);
    @(negedge clock);
    telemetry_commit_valid = 1'b0;
    telemetry_completion_valid = 1'b1;
    @(posedge clock);
    @(negedge clock);
    telemetry_completion_valid = 1'b0;

    aw_ready = 1'b1;
    w_ready = 1'b1;
    while (drained !== 1'b1 && cycles < 20000) begin
      @(posedge clock);
      cycles++;
    end

    if (drained !== 1'b1) fail("writer did not drain after completion");
    if (records !== 64'd2048) fail($sformatf("records is %0d", records));
    if (dropped !== 64'd1) fail($sformatf("dropped is %0d", dropped));
    if (accepted_addresses != FIFO_RECORDS / BURST_RECORDS)
      fail($sformatf("address burst count is %0d", accepted_addresses));
    if (accepted_writes != FIFO_RECORDS)
      fail($sformatf("write count is %0d", accepted_writes));
    $display("FPGA runtime trace writer tests passed");
    $finish;
  end
endmodule
