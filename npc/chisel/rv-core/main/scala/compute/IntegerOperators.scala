package scpu

import chisel3._
import chisel3.util._

/** 算子私有乘法控制码；刻意不复用后端的 `NpcAluOp` 编码。 */
object IntegerMultiplyOperation extends ChiselEnum {
  val low, signedHigh, signedUnsignedHigh, unsignedHigh, wordLow = Value
}

/** 算子私有除法/余数控制码。 */
object IntegerDivideOperation extends ChiselEnum {
  val signedQuotient, unsignedQuotient, signedRemainder, unsignedRemainder,
    signedWordQuotient, unsignedWordQuotient, signedWordRemainder, unsignedWordRemainder = Value
}

/** 可复用 RV32M/RV64M 乘法端点。它不了解流水级或退休；执行 ALU 提供已译码的
  * 操作控制，并负责该算子周围的发射/响应调度。
  */
class IntegerMultiplierOperator(
  val width: Int,
  implementation: ComputeUnitConfig,
  tagWidth: Int,
  timing: ArithmeticIpTiming,
  adapterModuleName: String
) extends Module {
  require(width == 32 || width == 64, s"IntegerMultiplierOperator supports RV32/RV64, got width=$width")
  val io = IO(new ArithmeticOperatorIO(width, tagWidth))

  implementation.backend match {
    case ComputeBackend.IP | ComputeBackend.FPGA =>
      val impl = Module(new ExternalArithmeticAdapter(
      adapterModuleName, width, tagWidth, timing.latency))
      impl.io.clock := clock
      impl.io.reset := reset.asBool
      io <> impl.io.arithmetic
    case _ =>
      val impl = Module(new ArithmeticIpModel(width, tagWidth, timing) {
        val result = IntegerMultiplierOperator.result(width, this.io.req.bits.operandA, this.io.req.bits.operandB,
          this.io.req.bits.operation)
        driveComputedResult(result)
      })
      io <> impl.io
  }
}

object IntegerMultiplierOperator {
  /** 无符号乘积加上所有 MUL 变体所需的 RISC-V 高半部修正。 */
  def result(width: Int, operandA: UInt, operandB: UInt, op: UInt): UInt = {
    val signedProduct = (operandA.asSInt * operandB.asSInt).asUInt
    val signedUnsignedProduct = (operandA.asSInt * Cat(0.U(1.W), operandB).asSInt).asUInt
    val unsignedProduct = operandA * operandB
    val lowProduct = unsignedProduct(width - 1, 0)
    val highProduct = MuxLookup(op, 0.U(width.W))(Seq(
      IntegerMultiplyOperation.signedHigh.asUInt -> signedProduct(2 * width - 1, width),
      IntegerMultiplyOperation.signedUnsignedHigh.asUInt -> signedUnsignedProduct(2 * width - 1, width),
      IntegerMultiplyOperation.unsignedHigh.asUInt -> unsignedProduct(2 * width - 1, width)
    ))
    if (width == 64) {
      val wordProduct = (operandA(31, 0).asSInt * operandB(31, 0).asSInt).asUInt
      val wordResult = Cat(Fill(32, wordProduct(31)), wordProduct(31, 0))
      Mux(op === IntegerMultiplyOperation.wordLow.asUInt, wordResult,
        Mux(op === IntegerMultiplyOperation.low.asUInt, lowProduct, highProduct))
    } else {
      Mux(op === IntegerMultiplyOperation.low.asUInt, lowProduct, highProduct)
    }
  }
}

/** 可复用 RV32M/RV64M 除法/余数端点。ISA 发射策略归 `MulDivAlu` 所有；
  * 本模块只计算已接受的请求。
  */
class IntegerDividerOperator(
  val width: Int,
  implementation: ComputeUnitConfig,
  tagWidth: Int,
  timing: ArithmeticIpTiming,
  adapterModuleName: String
) extends Module {
  require(width == 32 || width == 64, s"IntegerDividerOperator supports RV32/RV64, got width=$width")
  val io = IO(new ArithmeticOperatorIO(width, tagWidth))

  implementation.backend match {
    case ComputeBackend.IP | ComputeBackend.FPGA =>
      val impl = Module(new ExternalArithmeticAdapter(
      adapterModuleName, width, tagWidth, timing.latency))
      impl.io.clock := clock
      impl.io.reset := reset.asBool
      io <> impl.io.arithmetic
    case _ =>
      val impl = Module(new ArithmeticIpModel(width, tagWidth, timing) {
        val result = IntegerDividerOperator.result(width, this.io.req.bits.operandA, this.io.req.bits.operandB,
          this.io.req.bits.operation)
        driveComputedResult(result)
      })
      io <> impl.io
  }
}

object IntegerDividerOperator {
  def result(width: Int, operandA: UInt, operandB: UInt, op: UInt): UInt = {
    require(width == 32 || width == 64, s"IntegerDividerOperator supports RV32/RV64, got width=$width")

  def negateActive(value: UInt, wordOperation: Bool): UInt = {
    val fullWidthNegated = (~value + 1.U)(width - 1, 0)
    if (width == 64) {
      val wordNegated = Cat(0.U(32.W), (~value(31, 0) + 1.U)(31, 0))
      Mux(wordOperation, wordNegated, fullWidthNegated)
    } else {
      fullWidthNegated
    }
  }

  def formatResult(value: UInt, wordOperation: Bool): UInt = {
    if (width == 64) {
      Mux(wordOperation, Cat(Fill(32, value(31)), value(31, 0)), value)
    } else {
      value
    }
  }

  val wordOperation = if (width == 64) {
    op === IntegerDivideOperation.signedWordQuotient.asUInt || op === IntegerDivideOperation.unsignedWordQuotient.asUInt ||
      op === IntegerDivideOperation.signedWordRemainder.asUInt || op === IntegerDivideOperation.unsignedWordRemainder.asUInt
  } else {
    false.B
  }
  val isRemainder = op === IntegerDivideOperation.signedRemainder.asUInt ||
    op === IntegerDivideOperation.unsignedRemainder.asUInt ||
    (if (width == 64) op === IntegerDivideOperation.signedWordRemainder.asUInt ||
      op === IntegerDivideOperation.unsignedWordRemainder.asUInt else false.B)
  val signedDivide = op === IntegerDivideOperation.signedQuotient.asUInt ||
    op === IntegerDivideOperation.signedRemainder.asUInt ||
    (if (width == 64) op === IntegerDivideOperation.signedWordQuotient.asUInt ||
      op === IntegerDivideOperation.signedWordRemainder.asUInt else false.B)

  val activeOperandA = if (width == 64) {
    Mux(wordOperation, Cat(0.U(32.W), operandA(31, 0)), operandA)
  } else {
    operandA
  }
  val activeOperandB = if (width == 64) {
    Mux(wordOperation, Cat(0.U(32.W), operandB(31, 0)), operandB)
  } else {
    operandB
  }
  val aSignBit = if (width == 64) Mux(wordOperation, operandA(31), operandA(63)) else operandA(31)
  val bSignBit = if (width == 64) Mux(wordOperation, operandB(31), operandB(63)) else operandB(31)
  val aNegative = aSignBit && signedDivide
  val bNegative = bSignBit && signedDivide
  val aMagnitude = Mux(aNegative, negateActive(activeOperandA, wordOperation), activeOperandA)
  val bMagnitude = Mux(bNegative, negateActive(activeOperandB, wordOperation), activeOperandB)

  val activeAllOnes = if (width == 64) {
    Mux(wordOperation, Cat(0.U(32.W), Fill(32, 1.U(1.W))), Fill(64, 1.U(1.W)))
  } else {
    Fill(32, 1.U(1.W))
  }
  val activeSignedMin = if (width == 64) {
    Mux(wordOperation, Cat(0.U(32.W), (BigInt(1) << 31).U(32.W)), (BigInt(1) << 63).U(64.W))
  } else {
    (BigInt(1) << 31).U(32.W)
  }
  val divideByZero = activeOperandB === 0.U
  val signedDivideOverflow = signedDivide && activeOperandA === activeSignedMin && activeOperandB === activeAllOnes
  val unsignedQuotient = activeOperandA / activeOperandB
  val unsignedRemainder = activeOperandA % activeOperandB
  val unsignedResult = Mux(isRemainder, unsignedRemainder, unsignedQuotient)
  val negateResult = signedDivide && Mux(isRemainder, aNegative, aNegative ^ bNegative)
  val signedResult = Mux(negateResult, negateActive(unsignedResult, wordOperation), unsignedResult)
  val exceptionalResult = Mux(divideByZero,
    Mux(isRemainder, activeOperandA, activeAllOnes),
    Mux(signedDivideOverflow, Mux(isRemainder, 0.U(width.W), activeOperandA), signedResult))
  formatResult(exceptionalResult, wordOperation)
  }
}
