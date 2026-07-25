module NpcFloatingPointDpi(
  input  logic        valid,
  input  logic [63:0] operandA,
  input  logic [63:0] operandB,
  input  logic [63:0] operandC,
  input  logic [4:0]  operation,
  input  logic [2:0]  roundingMode,
  input  logic [6:0]  xlen,
  output logic [63:0] result,
  output logic [4:0]  exceptionFlags
);

  import "DPI-C" function void npc_f32_execute(
    input  longint unsigned operandA,
    input  longint unsigned operandB,
    input  longint unsigned operandC,
    input  int unsigned operation,
    input  int unsigned roundingMode,
    input  int unsigned xlen,
    output longint unsigned result,
    output int unsigned exceptionFlags
  );

  longint unsigned dpiResult;
  int unsigned dpiExceptionFlags;

  always_comb begin
    result = '0;
    exceptionFlags = '0;
    dpiResult = '0;
    dpiExceptionFlags = '0;
    if (valid) begin
      npc_f32_execute(operandA, operandB, operandC, operation, roundingMode, xlen, dpiResult, dpiExceptionFlags);
      result = dpiResult;
      exceptionFlags = dpiExceptionFlags[4:0];
    end
  end
endmodule
