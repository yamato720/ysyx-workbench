module MMIO_Core (
    input         clk,
    input         rst,
    input  [31:0] addr,
    input  [63:0]  din,
    input          we,
    input          re,
    input  [4:0]   len,
    output reg [63:0]  dout
);

    // DPI-C function imports
    import "DPI-C" function void mmio_read_impl(input int addr , input int len, output longint unsigned result);
    import "DPI-C" function void mmio_write_impl(input int addr, input int len, input longint unsigned data);
    
    // MMIO logic
    always @(posedge clk) begin
        if (rst) begin
        dout <= 64'h0;
        end else begin
        if (we) begin
            mmio_write_impl(addr, len, din[63:0]);
        end else if (re) begin
            mmio_read_impl(addr, len, dout[63:0]);
        end
        end
    end
    
endmodule

