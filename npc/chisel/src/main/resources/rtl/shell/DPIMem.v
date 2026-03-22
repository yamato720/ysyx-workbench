module DPIMem(
  input         clk,
  input         rst,
  input  [31:0] addr_a,
  input  [7:0]  din_a,
  output reg [7:0]  dout_a,
  input         we_a,
  input         en_a,
  input  [31:0] addr_b,
  input  [7:0]  din_b,
  output reg [7:0]  dout_b,
  input         we_b,
  input         en_b
);

  // DPI-C function imports
  import "DPI-C" function byte pmem_read_a(input int addr);
  import "DPI-C" function void pmem_write_a(input int addr, input byte data);
  import "DPI-C" function byte pmem_read_b(input int addr);
  import "DPI-C" function void pmem_write_b(input int addr, input byte data);

  // Port A logic
  always @(posedge clk) begin
    if (rst) begin
      dout_a <= 8'h0;
    end else if (en_a) begin
      if (we_a) begin
        pmem_write_a(addr_a, din_a);
      end
      else begin
        dout_a <= pmem_read_a(addr_a);
      end
    end
  end

  // Port B logic
  always @(posedge clk) begin
    if (rst) begin
      dout_b <= 8'h0;
    end else if (en_b) begin
      if (we_b) begin
        pmem_write_b(addr_b, din_b);
      end
      else begin
        dout_b <= pmem_read_b(addr_b);
      end
    end
  end
endmodule



