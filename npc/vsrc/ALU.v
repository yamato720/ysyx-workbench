module ALU
(   
    input  wire        rst,
    input  wire [3:0] a,
    input  wire [3:0] b,
    input  wire [2:0]  alu_control,
    output reg  [3:0]  alu_result,
    output reg         zero,
    output reg         cout,
    output reg         overflow
);




reg [4:0] result_temp;

always @(*) begin
    if(rst) begin
        result_temp = 5'd0;
        alu_result = 4'd0;
        zero = 1'b1;
        cout = 1'b0;
        overflow = 1'b0;
    end
    else begin
        case (alu_control)
            3'b000: begin   // ADD
                result_temp = a + b;
                zero = (result_temp == 5'd0);
                cout = result_temp[4];
                overflow = ((a[3] == b[3]) && (result_temp[3] != a[3]));
                alu_result = result_temp[3:0];
            end
            3'b001: begin   // SUB
                result_temp = a + {1'b0, ~b} + 5'b1;
                zero = (result_temp == 5'd0);
                cout = result_temp[4];
                overflow = ((a[3] != b[3]) && (result_temp[3] != a[3]));
                alu_result = result_temp[3:0];
            end
            3'b010: begin // Not A
                result_temp = {1'b0, ~a};
                zero = (result_temp == 5'd0);
                cout = result_temp[4];
                overflow = 1'b0;
                alu_result = result_temp[3:0];
            end
            3'b011: begin // A AND B
                result_temp = {1'b0, a & b};
                zero = (result_temp == 5'd0);
                cout = result_temp[4];
                overflow = 1'b0;
                alu_result = result_temp[3:0];
            end
            3'b100: begin // A OR B
                result_temp = {1'b0, a | b};
                zero = (result_temp == 5'd0);
                cout = result_temp[4];
                overflow = 1'b0;
                alu_result = result_temp[3:0];
            end
            3'b101: begin // A XOR B
                result_temp = {1'b0, a ^ b};
                zero = (result_temp == 5'd0);
                cout = result_temp[4];
                overflow = 1'b0;
                alu_result = result_temp[3:0];
            end
            3'b110: begin // SLT
                result_temp = a + ({1'b0, ~b} + 5'b1);
                zero = (result_temp == 5'd0);
                cout = result_temp[4];
                overflow = ((a[3] != b[3]) && (result_temp[3] != a[3]));
                alu_result = (((a[3] != b[3]) && (result_temp[3] != a[3])) ? ~result_temp[3] : result_temp[3]) ? 4'd1 : 4'd0;
            end
            3'b111: begin
                result_temp = a + ({1'b0, ~b} + 5'b1);
                zero = (result_temp == 5'd0);
                cout = result_temp[4];
                overflow = 1'b0;
                alu_result = {3'b0, !zero};
            end
            default: begin
                result_temp = 5'd0;
                alu_result = 4'd0;
                zero = 1'b1;
                cout = 1'b0;
                overflow = 1'b0;
            end
        endcase
    end
end





endmodule


