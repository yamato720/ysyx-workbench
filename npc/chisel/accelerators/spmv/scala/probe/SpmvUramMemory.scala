package accelerators.spmv.probe

import chisel3._
import chisel3.util._
import chisel3.util.HasBlackBoxInline

private[probe] final class SpmvUramMemory(depth: Int, width: Int) extends BlackBox(Map(
  "DEPTH" -> depth,
  "WIDTH" -> width
)) with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val aWriteEnable = Input(Bool())
    val aWriteAddress = Input(UInt(math.max(1, log2Ceil(depth)).W))
    val aWriteData = Input(UInt(width.W))
    val aReadEnable = Input(Bool())
    val aReadAddress = Input(UInt(math.max(1, log2Ceil(depth)).W))
    val aReadData = Output(UInt(width.W))
    val bWriteEnable = Input(Bool())
    val bWriteAddress = Input(UInt(math.max(1, log2Ceil(depth)).W))
    val bWriteData = Input(UInt(width.W))
    val bReadEnable = Input(Bool())
    val bReadAddress = Input(UInt(math.max(1, log2Ceil(depth)).W))
    val bReadData = Output(UInt(width.W))
  })

  override def desiredName: String = "SpmvUramMemory"

  setInline("SpmvUramMemory.sv",
    """module SpmvUramMemory #(
      |  parameter integer DEPTH = 2048,
      |  parameter integer WIDTH = 64,
      |  parameter integer ADDR_WIDTH = (DEPTH <= 1) ? 1 : $clog2(DEPTH)
      |) (
      |  input  wire                  clock,
      |  input  wire                  aWriteEnable,
      |  input  wire [ADDR_WIDTH-1:0] aWriteAddress,
      |  input  wire [WIDTH-1:0]      aWriteData,
      |  input  wire                  aReadEnable,
      |  input  wire [ADDR_WIDTH-1:0] aReadAddress,
      |  output reg  [WIDTH-1:0]      aReadData,
      |  input  wire                  bWriteEnable,
      |  input  wire [ADDR_WIDTH-1:0] bWriteAddress,
      |  input  wire [WIDTH-1:0]      bWriteData,
      |  input  wire                  bReadEnable,
      |  input  wire [ADDR_WIDTH-1:0] bReadAddress,
      |  output reg  [WIDTH-1:0]      bReadData
      |);
      |`ifdef VERILATOR
      |  // 仿真使用行为模型；综合分支直接实例化 U55C 的 URAM288 原语。
      |  reg [WIDTH-1:0] memory [0:DEPTH-1];
      |  always @(posedge clock) begin
      |    if (aWriteEnable) memory[aWriteAddress] <= aWriteData;
      |    if (aReadEnable) aReadData <= memory[aReadAddress];
      |    if (bWriteEnable) memory[bWriteAddress] <= bWriteData;
      |    if (bReadEnable) bReadData <= memory[bReadAddress];
      |  end
      |`else
      |  wire [71:0] aDout;
      |  wire [71:0] bDout;
      |  URAM288_BASE #(.BWE_MODE_A("PARITY_INTERLEAVED"), .BWE_MODE_B("PARITY_INTERLEAVED"),
      |    .EN_ECC_RD_A("FALSE"), .EN_ECC_RD_B("FALSE"), .EN_ECC_WR_A("FALSE"),
      |    .EN_ECC_WR_B("FALSE"), .OREG_A("FALSE"), .OREG_B("FALSE")) uram (
      |    .ADDR_A({12'b0, aWriteAddress}), .ADDR_B({12'b0, bWriteAddress}),
      |    .BWE_A({9{aWriteEnable}}), .BWE_B({9{bWriteEnable}}), .CLK(clock),
      |    .DIN_A({8'b0, aWriteData}), .DIN_B({8'b0, bWriteData}),
      |    .EN_A(aWriteEnable | aReadEnable), .EN_B(bWriteEnable | bReadEnable),
      |    .INJECT_DBITERR_A(1'b0), .INJECT_DBITERR_B(1'b0),
      |    .INJECT_SBITERR_A(1'b0), .INJECT_SBITERR_B(1'b0),
      |    .OREG_CE_A(aReadEnable), .OREG_CE_B(bReadEnable),
      |    .OREG_ECC_CE_A(1'b0), .OREG_ECC_CE_B(1'b0),
      |    .RDB_WR_A(aWriteEnable), .RDB_WR_B(bWriteEnable),
      |    .RST_A(1'b0), .RST_B(1'b0), .SLEEP(1'b0),
      |    .DOUT_A(aDout), .DOUT_B(bDout), .DBITERR_A(), .DBITERR_B(),
      |    .SBITERR_A(), .SBITERR_B());
      |  assign aReadData = aDout[WIDTH-1:0];
      |  assign bReadData = bDout[WIDTH-1:0];
      |`endif
      |endmodule
      |""".stripMargin)
}
