package scpu

import chisel3._
import chisel3.util._
import npc.ip.arithmetic._
import scpu.protocol.ArithmeticAssistPort

/** 标量 F 执行外壳的架构译码和时序配置。 */
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
    require(tagWidth >= 1, s"浮点 tagWidth 必须为正数，实际为 $tagWidth")
  }

  def isAddSub(op: UInt): Bool = op === NpcAluOp.Floating.FADD.asUInt || op === NpcAluOp.Floating.FSUB.asUInt
  def isMultiply(op: UInt): Bool = op === NpcAluOp.Floating.FMUL.asUInt
  def isDivide(op: UInt): Bool = op === NpcAluOp.Floating.FDIV.asUInt
  def isFma(op: UInt): Bool = op === NpcAluOp.Floating.FMADD.asUInt || op === NpcAluOp.Floating.FMSUB.asUInt ||
    op === NpcAluOp.Floating.FNMSUB.asUInt || op === NpcAluOp.Floating.FNMADD.asUInt
  def isSqrt(op: UInt): Bool = op === NpcAluOp.Floating.FSQRT.asUInt
  def isConvert(op: UInt): Bool = op === NpcAluOp.Floating.FCVT_W.asUInt || op === NpcAluOp.Floating.FCVT_WU.asUInt ||
    op === NpcAluOp.Floating.FCVT_L.asUInt || op === NpcAluOp.Floating.FCVT_LU.asUInt ||
    op === NpcAluOp.Floating.FCVT_S_W.asUInt || op === NpcAluOp.Floating.FCVT_S_WU.asUInt ||
    op === NpcAluOp.Floating.FCVT_S_L.asUInt || op === NpcAluOp.Floating.FCVT_S_LU.asUInt
  def isCompareOrMove(op: UInt): Bool = op === NpcAluOp.Floating.FSGNJ.asUInt || op === NpcAluOp.Floating.FSGNJN.asUInt ||
    op === NpcAluOp.Floating.FSGNJX.asUInt || op === NpcAluOp.Floating.FMIN.asUInt ||
    op === NpcAluOp.Floating.FMAX.asUInt || op === NpcAluOp.Floating.FEQ.asUInt ||
    op === NpcAluOp.Floating.FLT.asUInt || op === NpcAluOp.Floating.FLE.asUInt ||
    op === NpcAluOp.Floating.FMV_X_W.asUInt || op === NpcAluOp.Floating.FCLASS.asUInt ||
    op === NpcAluOp.Floating.FMV_W_X.asUInt
}

/** 公共 ISA 到算术协议的映射。实际端点由 [[ArithmeticIpProvider]] 提供。 */
class FloatingAlu(
  width: Int,
  config: FloatingAlu.Config = FloatingAlu.Config(),
  routes: OperatorRouteConfig = OperatorRouteConfig(),
  provider: ArithmeticIpProvider = SimulationIpComponents
) extends Module {
  require(width == 32 || width == 64, s"FloatingAlu 只支持 RV32/RV64，实际 width=$width")
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new AluRequest(width, config.tagWidth)))
    val resp = Decoupled(new ArithmeticResponse(width, config.tagWidth))
    val assist = new ArithmeticAssistPort(width)
  })

  private val operatorOperation = MuxLookup(io.req.bits.aluOp, 0.U(ArithmeticOperation.width.W))(Seq(
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

  private val routeGroups = Seq(
    (Seq(ArithmeticRouteOperation.Fadd -> NpcAluOp.Floating.FADD, ArithmeticRouteOperation.Fsub -> NpcAluOp.Floating.FSUB), config.addSubTiming, config.addSubAdapterModuleName),
    (Seq(ArithmeticRouteOperation.Fmul -> NpcAluOp.Floating.FMUL), config.multiplyTiming, config.multiplyAdapterModuleName),
    (Seq(ArithmeticRouteOperation.Fdiv -> NpcAluOp.Floating.FDIV), config.divideTiming, config.dividerAdapterModuleName),
    (Seq(ArithmeticRouteOperation.Fmadd -> NpcAluOp.Floating.FMADD, ArithmeticRouteOperation.Fmsub -> NpcAluOp.Floating.FMSUB,
      ArithmeticRouteOperation.Fnmsub -> NpcAluOp.Floating.FNMSUB, ArithmeticRouteOperation.Fnmadd -> NpcAluOp.Floating.FNMADD), config.fmaTiming, config.fmaAdapterModuleName),
    (Seq(ArithmeticRouteOperation.Fsqrt -> NpcAluOp.Floating.FSQRT), config.sqrtTiming, config.sqrtAdapterModuleName),
    (Seq(ArithmeticRouteOperation.FcvtW -> NpcAluOp.Floating.FCVT_W, ArithmeticRouteOperation.FcvtWu -> NpcAluOp.Floating.FCVT_WU,
      ArithmeticRouteOperation.FcvtL -> NpcAluOp.Floating.FCVT_L, ArithmeticRouteOperation.FcvtLu -> NpcAluOp.Floating.FCVT_LU,
      ArithmeticRouteOperation.FcvtSW -> NpcAluOp.Floating.FCVT_S_W, ArithmeticRouteOperation.FcvtSWu -> NpcAluOp.Floating.FCVT_S_WU,
      ArithmeticRouteOperation.FcvtSL -> NpcAluOp.Floating.FCVT_S_L, ArithmeticRouteOperation.FcvtSLu -> NpcAluOp.Floating.FCVT_S_LU), config.convertTiming, config.convertAdapterModuleName),
    (Seq(ArithmeticRouteOperation.Fsgnj -> NpcAluOp.Floating.FSGNJ, ArithmeticRouteOperation.Fsgnjn -> NpcAluOp.Floating.FSGNJN,
      ArithmeticRouteOperation.Fsgnjx -> NpcAluOp.Floating.FSGNJX, ArithmeticRouteOperation.Fmin -> NpcAluOp.Floating.FMIN,
      ArithmeticRouteOperation.Fmax -> NpcAluOp.Floating.FMAX, ArithmeticRouteOperation.Feq -> NpcAluOp.Floating.FEQ,
      ArithmeticRouteOperation.Flt -> NpcAluOp.Floating.FLT, ArithmeticRouteOperation.Fle -> NpcAluOp.Floating.FLE,
      ArithmeticRouteOperation.FmvXW -> NpcAluOp.Floating.FMV_X_W, ArithmeticRouteOperation.Fclass -> NpcAluOp.Floating.FCLASS,
      ArithmeticRouteOperation.FmvWX -> NpcAluOp.Floating.FMV_W_X), config.compareTiming, config.compareAdapterModuleName)
  )
  private val routeEnabled = routes.routes.nonEmpty

  private def selected(group: Seq[(ArithmeticRouteOperation, NpcAluOp.Floating.Type)], target: OperatorRouteTarget): Bool =
    group.collect { case (operation, code) if routes.route(operation).target == target => io.req.bits.aluOp === code.asUInt }
      .reduceOption(_ || _).getOrElse(false.B)

  private def hasTarget(group: Seq[(ArithmeticRouteOperation, NpcAluOp.Floating.Type)], target: OperatorRouteTarget): Boolean =
    group.exists { case (operation, _) => routes.route(operation).target == target }

  private def specFor(target: OperatorRouteTarget, moduleName: String): ArithmeticEndpointSpec = target match {
    case OperatorRouteTarget.Model => ArithmeticEndpointSpec(ArithmeticEndpointImplementation.SoftFloatDpi)
    case OperatorRouteTarget.VendorIp => ArithmeticEndpointSpec(ArithmeticEndpointImplementation.External, moduleName)
    case OperatorRouteTarget.DirectLogic => ArithmeticEndpointSpec(ArithmeticEndpointImplementation.FloatingDirect)
    case other => throw new IllegalArgumentException(s"浮点端点不能直接选择 $other")
  }

  private val endpointEntries = if (!routeEnabled) {
    routeGroups.map { case (group, timing, moduleName) =>
      val endpoint = provider.makeFloating(width, config.tagWidth, timing,
        config.implementation.backend match {
          case ComputeBackend.IP => ArithmeticEndpointSpec(ArithmeticEndpointImplementation.External, moduleName)
          case _ => ArithmeticEndpointSpec(ArithmeticEndpointImplementation.SoftFloatDpi)
        })
      endpoint -> group.map(_._2).map(code => io.req.bits.aluOp === code.asUInt).reduce(_ || _)
    }
  } else {
    routeGroups.flatMap { case (group, timing, moduleName) =>
      Seq(OperatorRouteTarget.Model, OperatorRouteTarget.VendorIp, OperatorRouteTarget.DirectLogic).collect {
        case target if hasTarget(group, target) =>
          provider.makeFloating(width, config.tagWidth, timing, specFor(target, moduleName)) -> selected(group, target)
      }
    }
  }

  private val allFloatingOperations = routeGroups.flatMap(_._1)
  private val fallbackSelected = if (routeEnabled)
    allFloatingOperations.collect { case (operation, code) if routes.route(operation).target == OperatorRouteTarget.HostFallback =>
      io.req.bits.aluOp === code.asUInt
    }.reduceOption(_ || _).getOrElse(false.B)
  else false.B
  private val fallback = if (routeEnabled && allFloatingOperations.exists {
    case (operation, _) => routes.route(operation).target == OperatorRouteTarget.HostFallback
  }) {
    val reason = allFloatingOperations.collectFirst {
      case (operation, _) if routes.route(operation).target == OperatorRouteTarget.HostFallback => routes.route(operation).fallbackReason
    }.get
    Some(Module(new HostFallbackOperator(width, config.tagWidth, ArithmeticRouteDomain.Floating, reason)))
  } else None

  endpointEntries.foreach { case (endpoint, selectedOperation) =>
    endpoint.io.req.valid := io.req.valid && selectedOperation
    endpoint.io.req.bits.operandA := io.req.bits.operandA
    endpoint.io.req.bits.operandB := io.req.bits.operandB
    endpoint.io.req.bits.operandC := io.req.bits.operandC
    endpoint.io.req.bits.operation := operatorOperation
    endpoint.io.req.bits.roundingMode := io.req.bits.roundingMode
    endpoint.io.req.bits.pc := io.req.bits.pc
    endpoint.io.req.bits.instruction := io.req.bits.instruction
    endpoint.io.req.bits.fcsr := io.req.bits.fcsr
    endpoint.io.req.bits.tag := io.req.bits.tag
  }
  fallback.foreach { endpoint =>
    endpoint.io.arithmetic.req.valid := io.req.valid && fallbackSelected
    endpoint.io.arithmetic.req.bits.operandA := io.req.bits.operandA
    endpoint.io.arithmetic.req.bits.operandB := io.req.bits.operandB
    endpoint.io.arithmetic.req.bits.operandC := io.req.bits.operandC
    endpoint.io.arithmetic.req.bits.operation := io.req.bits.aluOp
    endpoint.io.arithmetic.req.bits.roundingMode := io.req.bits.roundingMode
    endpoint.io.arithmetic.req.bits.pc := io.req.bits.pc
    endpoint.io.arithmetic.req.bits.instruction := io.req.bits.instruction
    endpoint.io.arithmetic.req.bits.fcsr := io.req.bits.fcsr
    endpoint.io.arithmetic.req.bits.tag := io.req.bits.tag
  }
  io.req.ready := MuxCase(false.B,
    endpointEntries.map { case (endpoint, selectedOperation) => selectedOperation -> endpoint.io.req.ready } ++
      fallback.map(endpoint => fallbackSelected -> endpoint.io.arithmetic.req.ready))

  private val responseSources = endpointEntries.map(_._1.io.resp) ++ fallback.map(_.io.arithmetic.resp)
  private val responses = Module(new RRArbiter(new ArithmeticResponse(width, config.tagWidth), responseSources.size))
  responseSources.zipWithIndex.foreach { case (source, index) => responses.io.in(index) <> source }
  io.resp <> responses.io.out

  fallback match {
    case Some(endpoint) =>
      io.assist.request.valid := endpoint.io.assist.request.valid
      io.assist.request.bits := endpoint.io.assist.request.bits
      endpoint.io.assist.request.ready := io.assist.request.ready
      endpoint.io.assist.response.valid := io.assist.response.valid
      endpoint.io.assist.response.bits := io.assist.response.bits
      io.assist.response.ready := endpoint.io.assist.response.ready
      io.assist.busy := endpoint.io.assist.busy
    case None =>
      io.assist.request.valid := false.B
      io.assist.request.bits := 0.U.asTypeOf(io.assist.request.bits)
      io.assist.response.ready := true.B
      io.assist.busy := false.B
  }
}
