module MemoryFaultDpi (
  input        clk,
  input        rst,
  input        valid,
  input [31:0] addr,
  input        write,
  input [3:0]  len,
  input [2:0]  reason
);
  reg reported;

  import "DPI-C" function void memory_fault(
    input int addr, input bit write, input int len, input int reason
  );

  always @(posedge clk) begin
    if (rst) begin
      reported <= 1'b0;
    end else if (valid && !reported) begin
      memory_fault(addr, write, len, reason);
      reported <= 1'b1;
    end
  end
endmodule
