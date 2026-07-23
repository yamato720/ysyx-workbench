module MMIOCore #(
  parameter integer DATA_WIDTH = 64,
  parameter integer WORD_BYTES = DATA_WIDTH / 8
) (
  input                         clk,
  input                         rst,
  input  [31:0]                 addr,
  input  [DATA_WIDTH-1:0]       din,
  input  [WORD_BYTES-1:0]       strb,
  input                         we,
  input                         re,
  input  [4:0]                  len,
  output reg [DATA_WIDTH-1:0]   dout
);

  longint unsigned read_word;

  import "DPI-C" function void mmio_read_word(
    input int addr, input int len, input int word_bytes, output longint unsigned word_data
  );
  import "DPI-C" function void mmio_write_word(
    input int addr, input int len, input int word_bytes,
    input longint unsigned word_data, input byte unsigned strb
  );

  always @(posedge clk) begin
    if (rst) begin
      dout <= '0;
    end else if (we) begin
      mmio_write_word(addr, len, WORD_BYTES, din, strb);
    end else if (re) begin
      mmio_read_word(addr, len, WORD_BYTES, read_word);
      dout <= read_word[DATA_WIDTH-1:0];
    end
  end
endmodule
