module FpgaCacheDrainTb;
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
  logic core_reset;
  logic dispatch_permit;
  logic cache_drain_request;
  logic cache_drained = 0;
  logic completion_commit_valid = 0;
  logic mailbox_interrupt;
  logic [63:0] icache_hits = 64'h0000_0001_0000_0001;
  logic [63:0] icache_misses = 64'h0000_0002_0000_0000;
  logic [63:0] icache_refills = 64'h0000_0003_0000_0000;
  logic [63:0] icache_evictions = 64'h0000_0004_0000_0000;
  logic [63:0] dcache_hits = 64'h0000_0010_0000_0000;
  logic [63:0] dcache_misses = 64'h0000_0020_0000_0000;
  logic [63:0] dcache_refills = 64'h0000_0030_0000_0000;
  logic [63:0] dcache_writebacks = 64'h0000_0040_0000_0000;
  logic [63:0] dcache_evictions = 64'h0000_0050_0000_0000;

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
    .io_runtime_commitValid(1'b0),
    .io_runtime_completionCommitValid(completion_commit_valid),
    .io_runtime_completionCommitPc(32'h8000_0000),
    .io_runtime_completionCommitNextPc(32'h8000_0004),
    .io_runtime_completionCode(32'h0000_005a),
    .io_runtime_sdb_coreBusy(1'b0),
    .io_runtime_sdb_dispatchFire(1'b0),
    .io_putch_valid(1'b0),
    .io_cache_instruction_statistics_hits(icache_hits),
    .io_cache_instruction_statistics_misses(icache_misses),
    .io_cache_instruction_statistics_refills(icache_refills),
    .io_cache_instruction_statistics_evictions(icache_evictions),
    .io_cache_data_statistics_hits(dcache_hits),
    .io_cache_data_statistics_misses(dcache_misses),
    .io_cache_data_statistics_refills(dcache_refills),
    .io_cache_data_statistics_writebacks(dcache_writebacks),
    .io_cache_data_statistics_evictions(dcache_evictions),
    .io_cacheDrainRequest(cache_drain_request),
    .io_cacheDrained(cache_drained),
    .io_coreReset(core_reset),
    .io_dispatchPermit(dispatch_permit),
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

  initial begin
    repeat (2) @(negedge clock);
    reset = 0;
    write_register(32'h80, 0);
    if (core_reset) $fatal(1, "reset release did not start the core");

    completion_commit_valid = 1;
    @(negedge clock);
    completion_commit_valid = 0;
    if (core_reset) $fatal(1, "cache completion reset the core before drain");
    if (!cache_drain_request) $fatal(1, "cache completion did not request drain");
    if (dispatch_permit) $fatal(1, "cache completion did not stop dispatch");
    if (mailbox_interrupt) $fatal(1, "completion became visible before drain");

    repeat (2) @(negedge clock);
    if (core_reset) $fatal(1, "core reset while cache drain was pending");
    cache_drained = 1;
    @(negedge clock);
    cache_drained = 0;
    // The acknowledgement must not reset the core until the following cycle,
    // when the final writeback counter value can be captured.
    if (core_reset) $fatal(1, "core reset before cache counters were snapshotted");
    if (!cache_drain_request) $fatal(1, "cache drain request ended before snapshot");
    if (mailbox_interrupt) $fatal(1, "completion became visible before snapshot");
    dcache_writebacks = 64'hfeed_face_0000_0001;
    @(negedge clock);
    if (!core_reset) $fatal(1, "core did not reset after cache drain");
    if (cache_drain_request) $fatal(1, "cache drain request remained asserted after acknowledgement");
    if (!mailbox_interrupt) $fatal(1, "drained completion did not notify the host");

    // Model the resettable core's cache counters returning to zero. Mailbox
    // reads must keep returning the completed run's values instead.
    icache_hits = 0;
    icache_misses = 0;
    icache_refills = 0;
    icache_evictions = 0;
    dcache_hits = 0;
    dcache_misses = 0;
    dcache_refills = 0;
    dcache_writebacks = 0;
    dcache_evictions = 0;
    begin
      logic [31:0] low;
      logic [31:0] high;
      read_register(32'h1ac, low);
      read_register(32'h1b0, high);
      if ({high, low} != 64'h0000_0001_0000_0001)
        $fatal(1, "I$ hit counter was not preserved across reset");
      read_register(32'h200, low);
      read_register(32'h204, high);
      if ({high, low} != 64'hfeed_face_0000_0001)
        $fatal(1, "final D$ writeback counter was not preserved across reset");
    end

    // Starting a new run must expose its live zeroed counters rather than a
    // stale previous-run snapshot.
    write_register(32'h80, 0);
    begin
      logic [31:0] low;
      read_register(32'h1ac, low);
      if (low != 0) $fatal(1, "starting a new run did not clear cache snapshot");
    end

    $display("FPGA cache drain/snapshot RTL tests passed");
    $finish;
  end
endmodule
