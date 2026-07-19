package scpu.fpga

import chisel3._
import chisel3.util._
import scpu._
import scpu.protocol.ArithmeticAssistPort

/** 只允许一个未完成请求；响应退休前冻结更年轻指令的发射。 */
class FpgaFloatingFallbackOperator(width: Int, tagWidth: Int) extends Module {
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
  when(io.assist.request.fire) { requestSent := true.B }

  val matchingResponse = io.assist.response.bits.sequence === sequence
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
class FpgaFloatingAlu(width: Int, config: FloatingAlu.Config)
    extends FloatingAluBase(width, config) {
  private val direct = Module(new FpgaFloatingDirectOperator(
    width, config.tagWidth, config.compareTiming))
  private val fallback = Module(new FpgaFloatingFallbackOperator(width, config.tagWidth))

  connectRequest(direct.io, compareOrMoveSelected)
  connectRequest(fallback.io.arithmetic, !compareOrMoveSelected)
  io.req.ready := Mux(compareOrMoveSelected, direct.io.req.ready, fallback.io.arithmetic.req.ready)

  private val responses = Module(new RRArbiter(new ArithmeticResponse(width, config.tagWidth), 2))
  responses.io.in(0) <> direct.io.resp
  responses.io.in(1) <> fallback.io.arithmetic.resp
  io.resp <> responses.io.out

  io.assist.request.valid := fallback.io.assist.request.valid
  io.assist.request.bits := fallback.io.assist.request.bits
  fallback.io.assist.request.ready := io.assist.request.ready
  fallback.io.assist.response.valid := io.assist.response.valid
  fallback.io.assist.response.bits := io.assist.response.bits
  io.assist.response.ready := fallback.io.assist.response.ready
  io.assist.busy := fallback.io.assist.busy
}
