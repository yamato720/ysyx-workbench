// Simple RISC-V CPU for NPC
// Minimal implementation for testing
/* verilator lint_off UNUSEDSIGNAL */
/* verilator lint_off UNUSEDPARAM */
/* verilator lint_off CASEINCOMPLETE */

module cpu (
    input clk,
    input rst
);

    // Program counter
    reg [31:0] pc;
    
    // Instruction
    reg [31:0] inst;
    
    // Instruction fetch via DPI-C
    import "DPI-C" function int pmem_read(input int addr);
    import "DPI-C" function void pmem_write(input int addr, input int data, input byte wmask);
    
    // Fetch instruction
    always @(*) begin
        inst = pmem_read(pc);
    end
    
    // Decode
    wire [6:0] opcode = inst[6:0];
    
    // Simple state machine
    reg state;
    localparam FETCH = 1'b0;
    localparam EXEC  = 1'b1;
    
    integer i;
    initial begin
        pc = 32'h80000000;  // Start address
        state = FETCH;
    end
    
    // Main CPU logic
    always @(posedge clk) begin
        if (rst) begin
            pc <= 32'h80000000;
            state <= FETCH;
        end else begin
            if (state == FETCH) begin
                // Instruction fetched via combinational logic
                state <= EXEC;
            end else begin // EXEC
                // Display every instruction
                $display("[%0d] PC=0x%08x INST=0x%08x", $time/2, pc, inst);
                
                // Check for EBREAK
                if (inst == 32'h00100073) begin
                    $display("*** EBREAK encountered - Halting simulation ***");
                    $finish;
                end
                
                // Increment PC (naive - doesn't handle branches)
                pc <= pc + 4;
                state <= FETCH;
            end
        end
    end

endmodule
/* verilator lint_on UNUSEDSIGNAL */
/* verilator lint_on UNUSEDPARAM */
/* verilator lint_on CASEINCOMPLETE */
