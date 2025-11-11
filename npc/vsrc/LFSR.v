module LFSR (
    input wire clk,
    input wire rst,
    output reg [7:0] lfsr_out
);  
    always @(posedge clk or posedge rst) begin
        if (rst) begin
            lfsr_out <= 8'h1; // Initial value
        end else begin
            lfsr_out <= {
                lfsr_out[4] ^ lfsr_out[3] ^ lfsr_out[2] ^ lfsr_out[0],
                lfsr_out[7:1]
            };
        end
    end



endmodule
