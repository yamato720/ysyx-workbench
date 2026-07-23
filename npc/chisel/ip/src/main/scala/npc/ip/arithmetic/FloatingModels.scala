package npc.ip.arithmetic

import chisel3._
import chisel3.experimental._
import chisel3.util._

/** binary32 端点的私有操作码，不复用任何 ISA 译码编码。 */
object FloatingOperation extends ChiselEnum {
  val add, subtract, multiply, divide, sqrt, multiplyAdd, multiplySubtract,
    negateMultiplySubtract, negateMultiplyAdd, signInject, signInjectNegate,
    signInjectXor, minimum, maximum, equal, lessThan, lessOrEqual,
    convertWord, convertWordUnsigned, convertLong, convertLongUnsigned,
    convertSingleWord, convertSingleWordUnsigned, convertSingleLong,
    convertSingleLongUnsigned, moveToInteger, classify, moveFromInteger = Value
}

private final class NpcFloatingPointDpi extends BlackBox with HasBlackBoxResource {
  override def desiredName: String = "NpcFloatingPointDpi"

  val io = IO(new Bundle {
    val valid = Input(Bool())
    val operandA = Input(UInt(64.W))
    val operandB = Input(UInt(64.W))
    val operandC = Input(UInt(64.W))
    val operation = Input(UInt(ArithmeticOperation.width.W))
    val roundingMode = Input(UInt(3.W))
    val xlen = Input(UInt(7.W))
    val result = Output(UInt(64.W))
    val exceptionFlags = Output(UInt(5.W))
  })

  addResource("/npc/ip/arithmetic/NpcFloatingPointDpi.sv")
}

/** Berkeley SoftFloat DPI 组合模型及公共传输时序。 */
final class FloatingDpiOperator(width: Int, tagWidth: Int, timing: ArithmeticIpTiming)
    extends ArithmeticIpModel(width, tagWidth, timing) {
  private val dpi = Module(new NpcFloatingPointDpi)
  private def extend(value: UInt): UInt = if (width == 64) value else Cat(0.U((64 - width).W), value)

  dpi.io.valid := io.req.fire
  dpi.io.operandA := extend(io.req.bits.operandA)
  dpi.io.operandB := extend(io.req.bits.operandB)
  dpi.io.operandC := extend(io.req.bits.operandC)
  dpi.io.operation := io.req.bits.operation
  dpi.io.roundingMode := io.req.bits.roundingMode
  dpi.io.xlen := width.U(7.W)

  val result = if (width == 64) dpi.io.result else dpi.io.result(31, 0)
  driveComputedResult(result, dpi.io.exceptionFlags)
}

/** 不需要动态舍入的 binary32 可综合直接逻辑。 */
final class FloatingDirectOperator(width: Int, tagWidth: Int, timing: ArithmeticIpTiming)
    extends ArithmeticIpModel(width, tagWidth, timing) {
  require(width == 32 || width == 64)

  private val rawA = io.req.bits.operandA
  private val rawB = io.req.bits.operandB
  private val op = io.req.bits.operation
  private val canonicalNaN = "h7fc00000".U(32.W)
  private val a = if (width == 64) Mux(rawA(63, 32).andR, rawA(31, 0), canonicalNaN) else rawA(31, 0)
  private val b = if (width == 64) Mux(rawB(63, 32).andR, rawB(31, 0), canonicalNaN) else rawB(31, 0)
  private def exponent(value: UInt): UInt = value(30, 23)
  private def fraction(value: UInt): UInt = value(22, 0)
  private def isZero(value: UInt): Bool = value(30, 0) === 0.U
  private def isSubnormal(value: UInt): Bool = exponent(value) === 0.U && fraction(value) =/= 0.U
  private def isNormal(value: UInt): Bool = exponent(value) =/= 0.U && exponent(value) =/= "hff".U
  private def isInfinity(value: UInt): Bool = exponent(value) === "hff".U && fraction(value) === 0.U
  private def isNaN(value: UInt): Bool = exponent(value) === "hff".U && fraction(value) =/= 0.U
  private def isSignalingNaN(value: UInt): Bool = isNaN(value) && !value(22)

  private val bothZero = isZero(a) && isZero(b)
  private val equal = !isNaN(a) && !isNaN(b) && (a === b || bothZero)
  private val orderedLess = Mux(a(31) =/= b(31), a(31) && !bothZero,
    Mux(a(31), a(30, 0) > b(30, 0), a(30, 0) < b(30, 0)))
  private val unordered = isNaN(a) || isNaN(b)
  private val minValue = Mux(isNaN(a) && isNaN(b), canonicalNaN,
    Mux(isNaN(a), b, Mux(isNaN(b), a, Mux(bothZero, a | b, Mux(orderedLess, a, b)))))
  private val maxValue = Mux(isNaN(a) && isNaN(b), canonicalNaN,
    Mux(isNaN(a), b, Mux(isNaN(b), a, Mux(bothZero, a & b, Mux(orderedLess, b, a)))))
  private val classification = Cat(
    isNaN(a) && a(22), isSignalingNaN(a), isInfinity(a) && !a(31), isNormal(a) && !a(31),
    isSubnormal(a) && !a(31), isZero(a) && !a(31), isZero(a) && a(31),
    isSubnormal(a) && a(31), isNormal(a) && a(31), isInfinity(a) && a(31)
  )
  private val rawResult = MuxLookup(op, 0.U(32.W))(Seq(
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
  private val integerResult = op === FloatingOperation.equal.asUInt ||
    op === FloatingOperation.lessThan.asUInt || op === FloatingOperation.lessOrEqual.asUInt ||
    op === FloatingOperation.classify.asUInt
  private val result = if (width == 64) {
    Mux(op === FloatingOperation.moveToInteger.asUInt,
      Cat(Fill(32, rawA(31)), rawA(31, 0)),
      Mux(integerResult, Cat(0.U(32.W), rawResult), Cat(Fill(32, 1.U(1.W)), rawResult)))
  } else rawResult
  private val signalingInvalid = isSignalingNaN(a) || isSignalingNaN(b)
  private val orderedCompare = op === FloatingOperation.lessThan.asUInt || op === FloatingOperation.lessOrEqual.asUInt
  private val equalCompare = op === FloatingOperation.equal.asUInt
  private val minMax = op === FloatingOperation.minimum.asUInt || op === FloatingOperation.maximum.asUInt
  private val invalid = (orderedCompare && unordered) || ((equalCompare || minMax) && signalingInvalid)
  driveComputedResult(result, Cat(invalid, 0.U(4.W)))
}
