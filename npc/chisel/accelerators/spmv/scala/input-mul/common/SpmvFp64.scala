package accelerators.spmv.inputmul.common

import accelerators.spmv.SpmvFp64MulProvider
import chisel3._
import chisel3.util.{HasBlackBoxInline, log2Ceil}
import npc.ip.arithmetic.{ArithmeticIpModel, ArithmeticIpTiming, ArithmeticOperatorEndpoint}

/** Mixed-V3：把 slot 里的 FP32 提升为 FP64 位型。 */
private[common] final class SpmvFp32ToFp64 extends BlackBox with HasBlackBoxInline {
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

/** U55C `floating_point v7.1` 的 Binary64 multiply 核心。
  *
  * 该 XCI 由 Vitis 包装阶段在构造目录内生成。这里仅保留稳定的 AXI-Stream 位型端口，
  * 不把 Xilinx 生成文件放进 Chisel classpath。
  */
private final class SpmvFp64MulXilinxCore extends BlackBox {
  override def desiredName: String = "SpmvFp64MulXilinxCore"

  val io = IO(new Bundle {
    val aclk = Input(Clock())
    val s_axis_a_tvalid = Input(Bool())
    val s_axis_a_tdata = Input(UInt(64.W))
    val s_axis_b_tvalid = Input(Bool())
    val s_axis_b_tdata = Input(UInt(64.W))
    val m_axis_result_tvalid = Output(Bool())
    val m_axis_result_tdata = Output(UInt(64.W))
  })
}

/** Xilinx Binary64 multiply 的 req/resp 适配器。
  *
  * 当前乘法-only 顶层始终无背压地消费 product，因此 vendor core 的无 ready 输出可以
  * 直接映射为响应；tag 以冻结 latency 的移位寄存器对齐。Vitis Tcl 同时把 IP 的
  * latency 固定成这一合同，任一侧偏离都会由 `illegal` 置位并最终反映为 `mulError`。
  */
private final class SpmvFp64MulXilinx(timing: ArithmeticIpTiming, tagWidth: Int)
    extends ArithmeticOperatorEndpoint(64, tagWidth) {
  require(timing.initiationInterval == 1,
    s"Xilinx FP64 multiply 适配器只支持 II=1，实际为 ${timing.initiationInterval}")
  private val core = Module(new SpmvFp64MulXilinxCore)
  private val tagValid = RegInit(VecInit(Seq.fill(timing.latency)(false.B)))
  private val tagData = Reg(Vec(timing.latency, UInt(tagWidth.W)))

  core.io.aclk := clock
  core.io.s_axis_a_tvalid := io.req.valid
  core.io.s_axis_a_tdata := io.req.bits.operandA
  core.io.s_axis_b_tvalid := io.req.valid
  core.io.s_axis_b_tdata := io.req.bits.operandB
  io.req.ready := true.B

  for (index <- timing.latency - 1 to 1 by -1) {
    tagValid(index) := tagValid(index - 1)
    when(tagValid(index - 1)) {
      tagData(index) := tagData(index - 1)
    }
  }
  tagValid(0) := io.req.fire
  when(io.req.fire) {
    tagData(0) := io.req.bits.tag
  }

  io.resp.valid := core.io.m_axis_result_tvalid
  io.resp.bits.result := core.io.m_axis_result_tdata
  io.resp.bits.tag := tagData(timing.latency - 1)
  io.resp.bits.illegal := !tagValid(timing.latency - 1)
}

/** 带 req/resp、固定 latency/II、响应背压和 tag 的 FP64 乘法 IP 端点。 */
private final class SpmvFp64MulSimulationEndpoint(timing: ArithmeticIpTiming, tagWidth: Int)
    extends ArithmeticIpModel(64, tagWidth, timing) {
  private val simulation = Module(new SpmvFp64MulSimulation)
  simulation.io.a := io.req.bits.operandA
  simulation.io.b := io.req.bits.operandB
  driveComputedResult(simulation.io.out)
}

private[common] final class SpmvFp64Mul(
  timing: ArithmeticIpTiming,
  tagWidth: Int,
  provider: SpmvFp64MulProvider
) extends ArithmeticOperatorEndpoint(64, tagWidth) {
  provider match {
    case SpmvFp64MulProvider.Simulation =>
      io <> Module(new SpmvFp64MulSimulationEndpoint(timing, tagWidth)).io
    case SpmvFp64MulProvider.XilinxFloatingPointV71 =>
      io <> Module(new SpmvFp64MulXilinx(timing, tagWidth)).io
  }
}
