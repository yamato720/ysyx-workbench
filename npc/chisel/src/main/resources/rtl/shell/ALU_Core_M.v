module ALU_Core_M(
  input         clk,
  input         rst,
  input  [63:0] a,
  input  [63:0] b,
  input   [4:0] sel, // total 21
  output reg [63:0] result
);

  // DPI-C function imports (output-argument form required by Verilator for >32-bit)
  import "DPI-C" function void mul_unit_32  (input longint unsigned a, input longint unsigned b, output longint unsigned result);
  import "DPI-C" function void mul_unit_64  (input longint unsigned a, input longint unsigned b, output longint unsigned result);
  import "DPI-C" function void mulh_unit_32 (input longint unsigned a, input longint unsigned b, output longint unsigned result);
  import "DPI-C" function void mulh_unit_64 (input longint unsigned a, input longint unsigned b, output longint unsigned result);
  import "DPI-C" function void mulhsu_unit_32(input longint unsigned a, input longint unsigned b, output longint unsigned result);
  import "DPI-C" function void mulhsu_unit_64(input longint unsigned a, input longint unsigned b, output longint unsigned result);
  import "DPI-C" function void mulhu_unit_32 (input longint unsigned a, input longint unsigned b, output longint unsigned result);
  import "DPI-C" function void mulhu_unit_64 (input longint unsigned a, input longint unsigned b, output longint unsigned result);
  import "DPI-C" function void div_unit_32  (input longint unsigned a, input longint unsigned b, output longint unsigned result);
  import "DPI-C" function void div_unit_64  (input longint unsigned a, input longint unsigned b, output longint unsigned result);
  import "DPI-C" function void divu_unit_32 (input longint unsigned a, input longint unsigned b, output longint unsigned result);
  import "DPI-C" function void divu_unit_64 (input longint unsigned a, input longint unsigned b, output longint unsigned result);
  import "DPI-C" function void rem_unit_32  (input longint unsigned a, input longint unsigned b, output longint unsigned result);
  import "DPI-C" function void rem_unit_64  (input longint unsigned a, input longint unsigned b, output longint unsigned result);
  import "DPI-C" function void remu_unit_32 (input longint unsigned a, input longint unsigned b, output longint unsigned result);
  import "DPI-C" function void remu_unit_64 (input longint unsigned a, input longint unsigned b, output longint unsigned result);
  import "DPI-C" function void mulw_unit    (input longint unsigned a, input longint unsigned b, output longint unsigned result);
  import "DPI-C" function void divw_unit    (input longint unsigned a, input longint unsigned b, output longint unsigned result);
  import "DPI-C" function void divuw_unit   (input longint unsigned a, input longint unsigned b, output longint unsigned result);
  import "DPI-C" function void remw_unit    (input longint unsigned a, input longint unsigned b, output longint unsigned result);
  import "DPI-C" function void remuw_unit   (input longint unsigned a, input longint unsigned b, output longint unsigned result);

  // Temp variable for DPI call results
  reg [63:0] _dpi_tmp;

  always @(posedge clk) begin
    if(rst) begin
      result <= 64'b0;
    end else begin
      case (sel)
        5'b00000: begin mul_unit_32  (a, b, _dpi_tmp); result <= _dpi_tmp; end
        5'b00001: begin mul_unit_64  (a, b, _dpi_tmp); result <= _dpi_tmp; end
        5'b00010: begin mulh_unit_32 (a, b, _dpi_tmp); result <= _dpi_tmp; end
        5'b00011: begin mulh_unit_64 (a, b, _dpi_tmp); result <= _dpi_tmp; end
        5'b00100: begin mulhsu_unit_32(a, b, _dpi_tmp); result <= _dpi_tmp; end
        5'b00101: begin mulhsu_unit_64(a, b, _dpi_tmp); result <= _dpi_tmp; end
        5'b00110: begin mulhu_unit_32 (a, b, _dpi_tmp); result <= _dpi_tmp; end
        5'b00111: begin mulhu_unit_64 (a, b, _dpi_tmp); result <= _dpi_tmp; end
        5'b01000: begin div_unit_32  (a, b, _dpi_tmp); result <= _dpi_tmp; end
        5'b01001: begin div_unit_64  (a, b, _dpi_tmp); result <= _dpi_tmp; end
        5'b01010: begin divu_unit_32 (a, b, _dpi_tmp); result <= _dpi_tmp; end
        5'b01011: begin divu_unit_64 (a, b, _dpi_tmp); result <= _dpi_tmp; end
        5'b01100: begin rem_unit_32  (a, b, _dpi_tmp); result <= _dpi_tmp; end
        5'b01101: begin rem_unit_64  (a, b, _dpi_tmp); result <= _dpi_tmp; end
        5'b01110: begin remu_unit_32 (a, b, _dpi_tmp); result <= _dpi_tmp; end
        5'b01111: begin remu_unit_64 (a, b, _dpi_tmp); result <= _dpi_tmp; end
        5'b10000: begin mulw_unit    (a, b, _dpi_tmp); result <= _dpi_tmp; end
        5'b10001: begin divw_unit    (a, b, _dpi_tmp); result <= _dpi_tmp; end
        5'b10010: begin remw_unit    (a, b, _dpi_tmp); result <= _dpi_tmp; end
        5'b10011: begin remuw_unit   (a, b, _dpi_tmp); result <= _dpi_tmp; end
        5'b10100: begin divuw_unit   (a, b, _dpi_tmp); result <= _dpi_tmp; end
        default: result <= 64'b0;
      endcase
    end
  end

endmodule




