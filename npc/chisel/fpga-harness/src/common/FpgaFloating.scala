package scpu.fpga

import chisel3._
import chisel3.util._
import scpu._
import scpu.protocol.ArithmeticAssistPort

/** 只允许一个未完成请求；响应退休前冻结更年轻指令的发射。 */
class FpgaFloatingFallbackOperator(
  width: Int,
  tagWidth: Int,
  reason: OperatorFallbackReason = OperatorFallbackReason.FpoRiscvIncompatible
) extends Module {
  val io = IO(new Bundle {
    val arithmetic = new ArithmeticOperatorIO(width, tagWidth)
    val assist = new ArithmeticAssistPort(width)
  })

  val pending = RegInit(false.B)
  val requestSent = RegInit(false.B)
  val nextSequence = RegInit(1.U(32.W))
  val sequence = Reg(UInt(32.W))
  val request = Reg(new ArithmeticRequest(width, tagWidth))

  io.arithmetic.req.ready := !pending
  when(io.arithmetic.req.fire) {
    request := io.arithmetic.req.bits
    sequence := nextSequence
    nextSequence := nextSequence + 1.U
    pending := true.B
    requestSent := false.B
  }

  io.assist.request.valid := pending && !requestSent
  io.assist.request.bits.sequence := sequence
  io.assist.request.bits.pc := request.pc
  io.assist.request.bits.instruction := request.instruction
  io.assist.request.bits.operandA := request.operandA
  io.assist.request.bits.operandB := request.operandB
  io.assist.request.bits.operandC := request.operandC
  io.assist.request.bits.fcsr := request.fcsr
  io.assist.request.bits.operation := request.operation
  io.assist.request.bits.roundingMode := request.roundingMode
  io.assist.request.bits.domain := ArithmeticRouteDomain.Floating.id.U
  io.assist.request.bits.fallbackReason := reason.id.U
  when(io.assist.request.fire) { requestSent := true.B }

  val matchingResponse = io.assist.response.bits.sequence === sequence &&
    io.assist.response.bits.domain === ArithmeticRouteDomain.Floating.id.U
  io.arithmetic.resp.valid := pending && requestSent && io.assist.response.valid && matchingResponse
  io.arithmetic.resp.bits.result := io.assist.response.bits.result
  io.arithmetic.resp.bits.exceptionFlags := io.assist.response.bits.exceptionFlags
  io.arithmetic.resp.bits.illegal := io.assist.response.bits.illegal
  io.arithmetic.resp.bits.tag := request.tag

  // 过期响应会被消费，但不能完成当前架构槽位。
  io.assist.response.ready := pending && requestSent &&
    (!matchingResponse || io.arithmetic.resp.ready)
  when(io.arithmetic.resp.fire) {
    pending := false.B
    requestSent := false.B
  }
  io.assist.busy := pending
}

/** 不需要舍入或宿主回退的可综合 F 扩展操作。 */
class FpgaFloatingDirectOperator(width: Int, tagWidth: Int, timing: ArithmeticIpTiming)
    extends ArithmeticIpModel(width, tagWidth, timing) {
  require(width == 32 || width == 64)

  val rawA = io.req.bits.operandA
  val rawB = io.req.bits.operandB
  val op = io.req.bits.operation
  val canonicalNaN = "h7fc00000".U(32.W)
  val a = if (width == 64) Mux(rawA(63, 32).andR, rawA(31, 0), canonicalNaN) else rawA(31, 0)
  val b = if (width == 64) Mux(rawB(63, 32).andR, rawB(31, 0), canonicalNaN) else rawB(31, 0)
  def exponent(value: UInt): UInt = value(30, 23)
  def fraction(value: UInt): UInt = value(22, 0)
  def isZero(value: UInt): Bool = value(30, 0) === 0.U
  def isSubnormal(value: UInt): Bool = exponent(value) === 0.U && fraction(value) =/= 0.U
  def isNormal(value: UInt): Bool = exponent(value) =/= 0.U && exponent(value) =/= "hff".U
  def isInfinity(value: UInt): Bool = exponent(value) === "hff".U && fraction(value) === 0.U
  def isNaN(value: UInt): Bool = exponent(value) === "hff".U && fraction(value) =/= 0.U
  def isSignalingNaN(value: UInt): Bool = isNaN(value) && !value(22)

  val bothZero = isZero(a) && isZero(b)
  val equal = !isNaN(a) && !isNaN(b) && (a === b || bothZero)
  val orderedLess = Mux(a(31) =/= b(31), a(31) && !bothZero,
    Mux(a(31), a(30, 0) > b(30, 0), a(30, 0) < b(30, 0)))
  val unordered = isNaN(a) || isNaN(b)

  val minValue = Mux(isNaN(a) && isNaN(b), canonicalNaN,
    Mux(isNaN(a), b, Mux(isNaN(b), a,
      Mux(bothZero, a | b, Mux(orderedLess, a, b)))))
  val maxValue = Mux(isNaN(a) && isNaN(b), canonicalNaN,
    Mux(isNaN(a), b, Mux(isNaN(b), a,
      Mux(bothZero, a & b, Mux(orderedLess, b, a)))))

  val classification = Cat(
    isNaN(a) && a(22),
    isSignalingNaN(a),
    isInfinity(a) && !a(31),
    isNormal(a) && !a(31),
    isSubnormal(a) && !a(31),
    isZero(a) && !a(31),
    isZero(a) && a(31),
    isSubnormal(a) && a(31),
    isNormal(a) && a(31),
    isInfinity(a) && a(31)
  )

  val rawResult = MuxLookup(op, 0.U(32.W))(Seq(
    FloatingOperation.signInject.asUInt -> Cat(b(31), a(30, 0)),
    FloatingOperation.signInjectNegate.asUInt -> Cat(!b(31), a(30, 0)),
    FloatingOperation.signInjectXor.asUInt -> Cat(a(31) ^ b(31), a(30, 0)),
    FloatingOperation.minimum.asUInt -> minValue,
    FloatingOperation.maximum.asUInt -> maxValue,
    FloatingOperation.equal.asUInt -> (!unordered && equal),
    FloatingOperation.lessThan.asUInt -> (!unordered && orderedLess),
    FloatingOperation.lessOrEqual.asUInt -> (!unordered && (orderedLess || equal)),
    FloatingOperation.moveToInteger.asUInt -> rawA(31, 0),
    FloatingOperation.classify.asUInt -> classification,
    FloatingOperation.moveFromInteger.asUInt -> rawA(31, 0)
  ))

  val integerResult = op === FloatingOperation.equal.asUInt ||
    op === FloatingOperation.lessThan.asUInt || op === FloatingOperation.lessOrEqual.asUInt ||
    op === FloatingOperation.classify.asUInt
  val result = if (width == 64) {
    Mux(op === FloatingOperation.moveToInteger.asUInt,
      Cat(Fill(32, rawA(31)), rawA(31, 0)),
      Mux(integerResult, Cat(0.U(32.W), rawResult), Cat(Fill(32, 1.U(1.W)), rawResult)))
  } else rawResult

  val signalingInvalid = isSignalingNaN(a) || isSignalingNaN(b)
  val orderedCompare = op === FloatingOperation.lessThan.asUInt || op === FloatingOperation.lessOrEqual.asUInt
  val equalCompare = op === FloatingOperation.equal.asUInt
  val minMax = op === FloatingOperation.minimum.asUInt || op === FloatingOperation.maximum.asUInt
  val invalid = (orderedCompare && unordered) ||
    ((equalCompare || minMax) && signalingInvalid)
  driveComputedResult(result, Cat(invalid, 0.U(4.W)))
}

/**
  * FPGA 平台的浮点组件。
  *
  * 可直接综合的比较、搬移和分类留在 PL；其余操作经中立的算术辅助端口交给运行时
  * 邮箱服务。ISA 映射和按序完成仍由 rv-core 的公共外壳承担。
  */
class FpgaFloatingAlu(width: Int, config: FloatingAlu.Config, routes: OperatorRouteConfig)
    extends FloatingAluBase(width, config) {
  private val routeEnabled = routes.routes.nonEmpty
  private val routeOperations = Seq(
    ArithmeticRouteOperation.Fadd -> NpcAluOp.Floating.FADD.asUInt,
    ArithmeticRouteOperation.Fsub -> NpcAluOp.Floating.FSUB.asUInt,
    ArithmeticRouteOperation.Fmul -> NpcAluOp.Floating.FMUL.asUInt,
    ArithmeticRouteOperation.Fdiv -> NpcAluOp.Floating.FDIV.asUInt,
    ArithmeticRouteOperation.Fsqrt -> NpcAluOp.Floating.FSQRT.asUInt,
    ArithmeticRouteOperation.Fmadd -> NpcAluOp.Floating.FMADD.asUInt,
    ArithmeticRouteOperation.Fmsub -> NpcAluOp.Floating.FMSUB.asUInt,
    ArithmeticRouteOperation.Fnmsub -> NpcAluOp.Floating.FNMSUB.asUInt,
    ArithmeticRouteOperation.Fnmadd -> NpcAluOp.Floating.FNMADD.asUInt,
    ArithmeticRouteOperation.Fsgnj -> NpcAluOp.Floating.FSGNJ.asUInt,
    ArithmeticRouteOperation.Fsgnjn -> NpcAluOp.Floating.FSGNJN.asUInt,
    ArithmeticRouteOperation.Fsgnjx -> NpcAluOp.Floating.FSGNJX.asUInt,
    ArithmeticRouteOperation.Fmin -> NpcAluOp.Floating.FMIN.asUInt,
    ArithmeticRouteOperation.Fmax -> NpcAluOp.Floating.FMAX.asUInt,
    ArithmeticRouteOperation.Feq -> NpcAluOp.Floating.FEQ.asUInt,
    ArithmeticRouteOperation.Flt -> NpcAluOp.Floating.FLT.asUInt,
    ArithmeticRouteOperation.Fle -> NpcAluOp.Floating.FLE.asUInt,
    ArithmeticRouteOperation.FcvtW -> NpcAluOp.Floating.FCVT_W.asUInt,
    ArithmeticRouteOperation.FcvtWu -> NpcAluOp.Floating.FCVT_WU.asUInt,
    ArithmeticRouteOperation.FcvtL -> NpcAluOp.Floating.FCVT_L.asUInt,
    ArithmeticRouteOperation.FcvtLu -> NpcAluOp.Floating.FCVT_LU.asUInt,
    ArithmeticRouteOperation.FcvtSW -> NpcAluOp.Floating.FCVT_S_W.asUInt,
    ArithmeticRouteOperation.FcvtSWu -> NpcAluOp.Floating.FCVT_S_WU.asUInt,
    ArithmeticRouteOperation.FcvtSL -> NpcAluOp.Floating.FCVT_S_L.asUInt,
    ArithmeticRouteOperation.FcvtSLu -> NpcAluOp.Floating.FCVT_S_LU.asUInt,
    ArithmeticRouteOperation.FmvXW -> NpcAluOp.Floating.FMV_X_W.asUInt,
    ArithmeticRouteOperation.Fclass -> NpcAluOp.Floating.FCLASS.asUInt,
    ArithmeticRouteOperation.FmvWX -> NpcAluOp.Floating.FMV_W_X.asUInt
  )
  private def selectedFor(target: OperatorRouteTarget): Bool = routeOperations.collect {
    case (operation, code) if routes.route(operation).target == target => io.req.bits.aluOp === code
  }.reduceOption(_ || _).getOrElse(false.B)
  private val directSelected = if (routeEnabled) selectedFor(OperatorRouteTarget.DirectLogic)
    else compareOrMoveSelected
  private val fallbackSelected = if (routeEnabled) selectedFor(OperatorRouteTarget.HostFallback)
    else !compareOrMoveSelected
  private val fallbackReason = routeOperations.collectFirst {
    case (operation, _) if routes.route(operation).target == OperatorRouteTarget.HostFallback =>
      routes.route(operation).fallbackReason
  }.getOrElse(OperatorFallbackReason.FpoRiscvIncompatible)

  private val direct = Module(new FpgaFloatingDirectOperator(
    width, config.tagWidth, config.compareTiming))
  private val fallback = if (!routeEnabled || routeOperations.exists {
    case (operation, _) => routes.route(operation).target == OperatorRouteTarget.HostFallback
  }) Some(Module(new FpgaFloatingFallbackOperator(width, config.tagWidth, fallbackReason))) else None

  connectRequest(direct.io, directSelected)
  fallback.foreach(endpoint => connectRequest(endpoint.io.arithmetic, fallbackSelected))
  io.req.ready := MuxCase(false.B, Seq(
    Some(directSelected -> direct.io.req.ready),
    fallback.map(endpoint => fallbackSelected -> endpoint.io.arithmetic.req.ready)
  ).flatten)

  private val responses = Module(new RRArbiter(new ArithmeticResponse(width, config.tagWidth), 1 + fallback.size))
  responses.io.in(0) <> direct.io.resp
  fallback.foreach(endpoint => responses.io.in(1) <> endpoint.io.arithmetic.resp)
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
