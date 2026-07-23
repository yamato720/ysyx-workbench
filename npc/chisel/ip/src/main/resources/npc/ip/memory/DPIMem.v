module DPIMem #(
  parameter integer DATA_WIDTH = 64,
  parameter integer WORD_BYTES = DATA_WIDTH / 8
) (
  input                         clk,
  input                         rst,
  input  [31:0]                 addr,
  input  [DATA_WIDTH-1:0]       din,
  output reg [DATA_WIDTH-1:0]   dout,
  input  [WORD_BYTES-1:0]       wstrb,
  input                         ren,
  input                         wen
);

  longint unsigned read_word;

  import "DPI-C" function void pmem_read_word(
    input int addr, input int word_bytes, output longint unsigned data
  );
  import "DPI-C" function void pmem_write_word(
    input int addr, input int word_bytes, input longint unsigned data, input byte unsigned strb
  );

  always @(posedge clk) begin
    if (rst) begin
      dout <= '0;
    end else begin
      if (wen) begin
        pmem_write_word(addr, WORD_BYTES, din, wstrb);
      end
      if (ren) begin
        pmem_read_word(addr, WORD_BYTES, read_word);
        dout <= read_word[DATA_WIDTH-1:0];
      end
    end
  end
endmodule
