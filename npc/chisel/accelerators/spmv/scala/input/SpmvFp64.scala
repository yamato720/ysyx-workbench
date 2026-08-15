package accelerators.spmv.input

import chisel3._
import chisel3.util.HasBlackBoxInline
import npc.ip.arithmetic.{ArithmeticIpModel, ArithmeticIpTiming}

/** Mixed-V3：把 slot 里的 FP32 提升为 FP64 位型。 */
private[input] final class SpmvFp32ToFp64 extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val in = Input(UInt(32.W))
    val out = Output(UInt(64.W))
  })

  override def desiredName: String = "SpmvFp32ToFp64"

  setInline("SpmvFp32ToFp64.sv",
    """module SpmvFp32ToFp64(
      |  input  wire [31:0] in,
      |  output wire [63:0] out
      |);
      |  // $bitstoshortreal 在本仿真器里不是 IEEE 提升，因此按位构造 FP64。
      |  wire             sign = in[31];
      |  wire [7:0]       exp32 = in[30:23];
      |  wire [22:0]      frac32 = in[22:0];
      |  wire             isZero = exp32 == 8'h00 && frac32 == 23'h0;
      |  wire             isSubnormal = exp32 == 8'h00 && frac32 != 23'h0;
      |  wire             isInfNan = exp32 == 8'hff;
      |  wire [10:0]      expNormal = {3'b0, exp32} + 11'd896;
      |  function automatic [4:0] lzc23;
      |    input [22:0] value;
      |    integer index;
      |    begin
      |      lzc23 = 5'd23;
      |      for (index = 22; index >= 0; index = index - 1) begin
      |        if (value[index] && lzc23 == 5'd23) lzc23 = 5'(22 - index);
      |      end
      |    end
      |  endfunction
      |  wire [4:0]       subShift = lzc23(frac32);
      |  wire [22:0]      subFrac = frac32 << subShift;
      |  wire [10:0]      expSub = 11'd896 - {6'b0, subShift};
      |  assign out = isZero ? {sign, 63'b0} :
      |               isInfNan ? {sign, 11'h7ff, frac32, 29'b0} :
      |               isSubnormal ? {sign, expSub, subFrac[21:0], 30'b0} :
      |               {sign, expNormal, frac32, 29'b0};
      |endmodule
      |""".stripMargin)
}

/** 公共 IP 接口后面的本地 FP64 数值模型。 */
private final class SpmvFp64MulSimulation extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val a = Input(UInt(64.W))
    val b = Input(UInt(64.W))
    val out = Output(UInt(64.W))
  })

  override def desiredName: String = "SpmvFp64MulSimulation"

  setInline("SpmvFp64MulSimulation.sv",
    """module SpmvFp64MulSimulation(
      |  input  wire [63:0] a,
      |  input  wire [63:0] b,
      |  output wire [63:0] out
      |);
      |  assign out = $realtobits($bitstoreal(a) * $bitstoreal(b));
      |endmodule
      |""".stripMargin)
}

/** 带 req/resp、固定 latency/II、响应背压和 tag 的 FP64 乘法 IP 端点。 */
private[input] final class SpmvFp64Mul(timing: ArithmeticIpTiming, tagWidth: Int)
    extends ArithmeticIpModel(64, tagWidth, timing) {
  private val simulation = Module(new SpmvFp64MulSimulation)
  simulation.io.a := io.req.bits.operandA
  simulation.io.b := io.req.bits.operandB
  driveComputedResult(simulation.io.out)
}
