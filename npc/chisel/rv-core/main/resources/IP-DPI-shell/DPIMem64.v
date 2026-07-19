module DPIMem64(
  input         clk,
  input         rst,
  input  [31:0] addr,
  input  [63:0] din,
  output reg [63:0] dout,
  input  [7:0]  wstrb,
  input         ren,
  input         wen
);

  import "DPI-C" function void pmem_read_64(input int addr, output longint unsigned data);
  import "DPI-C" function void pmem_write_64(input int addr, input longint unsigned data, input byte wstrb);

  always @(posedge clk) begin
    if (rst) begin
      dout <= 64'h0;
    end else begin
      if (wen) begin
        pmem_write_64(addr, din, wstrb);
      end
      if (ren) begin
        pmem_read_64(addr, dout);
      end
    end
  end
endmodule
