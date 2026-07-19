package scpu

import chisel3._
import chisel3.util._
import scpu.protocol.ArithmeticAssistPort

/** 标量 binary32 执行外壳的架构译码和时序配置。 */
object FloatingAlu {
  case class Config(
    implementation: ComputeUnitConfig = ComputeUnitConfig(),
    addSubTiming: ArithmeticIpTiming = ArithmeticIpTiming(latency = 3),
    multiplyTiming: ArithmeticIpTiming = ArithmeticIpTiming(latency = 4),
    divideTiming: ArithmeticIpTiming = ArithmeticIpTiming(latency = 29),
    fmaTiming: ArithmeticIpTiming = ArithmeticIpTiming(latency = 4),
    sqrtTiming: ArithmeticIpTiming = ArithmeticIpTiming(latency = 29),
    convertTiming: ArithmeticIpTiming = ArithmeticIpTiming(latency = 4),
    compareTiming: ArithmeticIpTiming = ArithmeticIpTiming(latency = 3),
    tagWidth: Int = 4,
    addSubAdapterModuleName: String = "npc_fp_addsub_adapter",
    multiplyAdapterModuleName: String = "npc_fp_multiplier_adapter",
    dividerAdapterModuleName: String = "npc_fp_divider_adapter",
    fmaAdapterModuleName: String = "npc_fp_fma_adapter",
    sqrtAdapterModuleName: String = "npc_fp_sqrt_adapter",
    convertAdapterModuleName: String = "npc_fp_convert_adapter",
    compareAdapterModuleName: String = "npc_fp_compare_adapter",
  ) {
    require(tagWidth >= 1, s"Floating-point tagWidth must be positive, got $tagWidth")
  }

  def isAddSub(op: UInt): Bool =
    op === NpcAluOp.Floating.FADD.asUInt || op === NpcAluOp.Floating.FSUB.asUInt
  def isMultiply(op: UInt): Bool = op === NpcAluOp.Floating.FMUL.asUInt
  def isDivide(op: UInt): Bool = op === NpcAluOp.Floating.FDIV.asUInt
  def isFma(op: UInt): Bool =
    op === NpcAluOp.Floating.FMADD.asUInt || op === NpcAluOp.Floating.FMSUB.asUInt ||
      op === NpcAluOp.Floating.FNMSUB.asUInt || op === NpcAluOp.Floating.FNMADD.asUInt
  def isSqrt(op: UInt): Bool = op === NpcAluOp.Floating.FSQRT.asUInt
  def isConvert(op: UInt): Bool =
    op === NpcAluOp.Floating.FCVT_W.asUInt || op === NpcAluOp.Floating.FCVT_WU.asUInt ||
      op === NpcAluOp.Floating.FCVT_L.asUInt || op === NpcAluOp.Floating.FCVT_LU.asUInt ||
      op === NpcAluOp.Floating.FCVT_S_W.asUInt || op === NpcAluOp.Floating.FCVT_S_WU.asUInt ||
      op === NpcAluOp.Floating.FCVT_S_L.asUInt || op === NpcAluOp.Floating.FCVT_S_LU.asUInt
  def isCompareOrMove(op: UInt): Bool =
    op === NpcAluOp.Floating.FSGNJ.asUInt || op === NpcAluOp.Floating.FSGNJN.asUInt ||
      op === NpcAluOp.Floating.FSGNJX.asUInt || op === NpcAluOp.Floating.FMIN.asUInt ||
      op === NpcAluOp.Floating.FMAX.asUInt || op === NpcAluOp.Floating.FEQ.asUInt ||
      op === NpcAluOp.Floating.FLT.asUInt || op === NpcAluOp.Floating.FLE.asUInt ||
      op === NpcAluOp.Floating.FMV_X_W.asUInt || op === NpcAluOp.Floating.FCLASS.asUInt ||
      op === NpcAluOp.Floating.FMV_W_X.asUInt
}

/**
  * 浮点 ISA 外壳的公共部分。
  *
  * 它只承担 ALU 操作到稳定算术协议的映射。模型/DPI 与 FPGA/IP 的实际端点由
  * [[NpcCoreComponents]] 分别实例化，避免核心依赖任何平台专属模块。
  */
abstract class FloatingAluBase(width: Int, config: FloatingAlu.Config) extends Module {
  require(width == 32 || width == 64, s"FloatingAlu supports RV32/RV64, got width=$width")

  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new AluRequest(width, config.tagWidth)))
    val resp = Decoupled(new ArithmeticResponse(width, config.tagWidth))
    val assist = new ArithmeticAssistPort(width)
  })

  protected final val addSubSelected = FloatingAlu.isAddSub(io.req.bits.aluOp)
  protected final val multiplySelected = FloatingAlu.isMultiply(io.req.bits.aluOp)
  protected final val divideSelected = FloatingAlu.isDivide(io.req.bits.aluOp)
  protected final val fmaSelected = FloatingAlu.isFma(io.req.bits.aluOp)
  protected final val sqrtSelected = FloatingAlu.isSqrt(io.req.bits.aluOp)
  protected final val convertSelected = FloatingAlu.isConvert(io.req.bits.aluOp)
  protected final val compareOrMoveSelected = FloatingAlu.isCompareOrMove(io.req.bits.aluOp)
  protected final val operatorOperation = MuxLookup(io.req.bits.aluOp, 0.U(ArithmeticOperation.width.W))(Seq(
    NpcAluOp.Floating.FADD.asUInt -> FloatingOperation.add.asUInt,
    NpcAluOp.Floating.FSUB.asUInt -> FloatingOperation.subtract.asUInt,
    NpcAluOp.Floating.FMUL.asUInt -> FloatingOperation.multiply.asUInt,
    NpcAluOp.Floating.FDIV.asUInt -> FloatingOperation.divide.asUInt,
    NpcAluOp.Floating.FSQRT.asUInt -> FloatingOperation.sqrt.asUInt,
    NpcAluOp.Floating.FMADD.asUInt -> FloatingOperation.multiplyAdd.asUInt,
    NpcAluOp.Floating.FMSUB.asUInt -> FloatingOperation.multiplySubtract.asUInt,
    NpcAluOp.Floating.FNMSUB.asUInt -> FloatingOperation.negateMultiplySubtract.asUInt,
    NpcAluOp.Floating.FNMADD.asUInt -> FloatingOperation.negateMultiplyAdd.asUInt,
    NpcAluOp.Floating.FSGNJ.asUInt -> FloatingOperation.signInject.asUInt,
    NpcAluOp.Floating.FSGNJN.asUInt -> FloatingOperation.signInjectNegate.asUInt,
    NpcAluOp.Floating.FSGNJX.asUInt -> FloatingOperation.signInjectXor.asUInt,
    NpcAluOp.Floating.FMIN.asUInt -> FloatingOperation.minimum.asUInt,
    NpcAluOp.Floating.FMAX.asUInt -> FloatingOperation.maximum.asUInt,
    NpcAluOp.Floating.FEQ.asUInt -> FloatingOperation.equal.asUInt,
    NpcAluOp.Floating.FLT.asUInt -> FloatingOperation.lessThan.asUInt,
    NpcAluOp.Floating.FLE.asUInt -> FloatingOperation.lessOrEqual.asUInt,
    NpcAluOp.Floating.FCVT_W.asUInt -> FloatingOperation.convertWord.asUInt,
    NpcAluOp.Floating.FCVT_WU.asUInt -> FloatingOperation.convertWordUnsigned.asUInt,
    NpcAluOp.Floating.FCVT_L.asUInt -> FloatingOperation.convertLong.asUInt,
    NpcAluOp.Floating.FCVT_LU.asUInt -> FloatingOperation.convertLongUnsigned.asUInt,
    NpcAluOp.Floating.FCVT_S_W.asUInt -> FloatingOperation.convertSingleWord.asUInt,
    NpcAluOp.Floating.FCVT_S_WU.asUInt -> FloatingOperation.convertSingleWordUnsigned.asUInt,
    NpcAluOp.Floating.FCVT_S_L.asUInt -> FloatingOperation.convertSingleLong.asUInt,
    NpcAluOp.Floating.FCVT_S_LU.asUInt -> FloatingOperation.convertSingleLongUnsigned.asUInt,
    NpcAluOp.Floating.FMV_X_W.asUInt -> FloatingOperation.moveToInteger.asUInt,
    NpcAluOp.Floating.FCLASS.asUInt -> FloatingOperation.classify.asUInt,
    NpcAluOp.Floating.FMV_W_X.asUInt -> FloatingOperation.moveFromInteger.asUInt
  ))

  protected final def connectRequest(endpoint: ArithmeticOperatorIO, selected: Bool): Unit = {
    endpoint.req.valid := io.req.valid && selected
    endpoint.req.bits.operandA := io.req.bits.operandA
    endpoint.req.bits.operandB := io.req.bits.operandB
    endpoint.req.bits.operandC := io.req.bits.operandC
    endpoint.req.bits.operation := operatorOperation
    endpoint.req.bits.roundingMode := io.req.bits.roundingMode
    endpoint.req.bits.pc := io.req.bits.pc
    endpoint.req.bits.instruction := io.req.bits.instruction
    endpoint.req.bits.fcsr := io.req.bits.fcsr
    endpoint.req.bits.tag := io.req.bits.tag
  }

  /** 模型端点不使用平台算术服务；丢弃错误的外部响应以避免上游阻塞。 */
  protected final def tieOffAssist(): Unit = {
    io.assist.request.valid := false.B
    io.assist.request.bits := 0.U.asTypeOf(io.assist.request.bits)
    io.assist.response.ready := true.B
    io.assist.busy := false.B
  }
}

/** 普通仿真组件使用的 SoftFloat/DPI 或外部模型浮点端点。 */
class FloatingAlu(width: Int, config: FloatingAlu.Config = FloatingAlu.Config())
    extends FloatingAluBase(width, config) {
  require(config.implementation.backend != ComputeBackend.FPGA,
    "FPGA floating implementation must be selected through FpgaCoreComponents")

  private val addSub = Module(new FloatingAddSubOperator(width,
    config.implementation, config.tagWidth, config.addSubTiming, config.addSubAdapterModuleName))
  private val multiplier = Module(new FloatingMultiplierOperator(width,
    config.implementation, config.tagWidth, config.multiplyTiming, config.multiplyAdapterModuleName))
  private val divider = Module(new FloatingDividerOperator(width,
    config.implementation, config.tagWidth, config.divideTiming, config.dividerAdapterModuleName))
  private val fma = Module(new FloatingFmaOperator(width,
    config.implementation, config.tagWidth, config.fmaTiming, config.fmaAdapterModuleName))
  private val sqrt = Module(new FloatingSqrtOperator(width,
    config.implementation, config.tagWidth, config.sqrtTiming, config.sqrtAdapterModuleName))
  private val convert = Module(new FloatingConvertOperator(width,
    config.implementation, config.tagWidth, config.convertTiming, config.convertAdapterModuleName))
  private val compareOrMove = Module(new FloatingCompareOperator(width,
    config.implementation, config.tagWidth, config.compareTiming, config.compareAdapterModuleName))

  connectRequest(addSub.io, addSubSelected)
  connectRequest(multiplier.io, multiplySelected)
  connectRequest(divider.io, divideSelected)
  connectRequest(fma.io, fmaSelected)
  connectRequest(sqrt.io, sqrtSelected)
  connectRequest(convert.io, convertSelected)
  connectRequest(compareOrMove.io, compareOrMoveSelected)

  io.req.ready := MuxCase(false.B, Seq(
    addSubSelected -> addSub.io.req.ready,
    multiplySelected -> multiplier.io.req.ready,
    divideSelected -> divider.io.req.ready,
    fmaSelected -> fma.io.req.ready,
    sqrtSelected -> sqrt.io.req.ready,
    convertSelected -> convert.io.req.ready,
    compareOrMoveSelected -> compareOrMove.io.req.ready
  ))

  private val responses = Module(new RRArbiter(new ArithmeticResponse(width, config.tagWidth), 7))
  responses.io.in(0) <> addSub.io.resp
  responses.io.in(1) <> multiplier.io.resp
  responses.io.in(2) <> divider.io.resp
  responses.io.in(3) <> fma.io.resp
  responses.io.in(4) <> sqrt.io.resp
  responses.io.in(5) <> convert.io.resp
  responses.io.in(6) <> compareOrMove.io.resp
  io.resp <> responses.io.out
  tieOffAssist()
}
