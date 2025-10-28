module encoder_8_3 (
    input  wire [7:0]   din,
    input  wire         en ,
    output reg  [2:0]   dout,
    output reg          valid,
    output wire [7:0]   seg_out
);

reg [3:0] seg_in ;


always @(*)
begin
    if(en) begin
        casez (din)
            8'b00000001: begin dout = 3'b000; valid = 1'b1; end
            8'b0000001?: begin dout = 3'b001; valid = 1'b1; end
            8'b000001??: begin dout = 3'b010; valid = 1'b1; end
            8'b00001???: begin dout = 3'b011; valid = 1'b1; end
            8'b0001????: begin dout = 3'b100; valid = 1'b1; end
            8'b001?????: begin dout = 3'b101; valid = 1'b1; end
            8'b01??????: begin dout = 3'b110; valid = 1'b1; end
            8'b1???????: begin dout = 3'b111; valid = 1'b1; end
            default: begin dout = 3'b000; valid = 1'b0; end
        endcase
    end 
    else begin
        dout = 3'b000;
        valid = 1'b0;

    end
    
end




seg u_seg (
    .b(dout),
    .h(seg_out)
);




endmodule


module seg(
  input  [2:0] b,
  output reg [7:0] h
);
// detailed implementation ...
always @(*) begin
  case (b)
    3'b000: h = 8'b00111111; // 0
    3'b001: h = 8'b00000110; // 1
    3'b010: h = 8'b01011011; // 2
    3'b011: h = 8'b01001111; // 3
    3'b100: h = 8'b01100110; // 4
    3'b101: h = 8'b01101101; // 5
    3'b110: h = 8'b01111101; // 6
    3'b111: h = 8'b00000011; // 7
    default: h = 8'b00111111; // Off, 0
  endcase
end

endmodule