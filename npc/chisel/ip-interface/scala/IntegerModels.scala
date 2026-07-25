package npc.ip.arithmetic

import chisel3._
import chisel3.util._

object IntegerMultiplyOperation extends ChiselEnum {
  val low, signedHigh, signedUnsignedHigh, unsignedHigh, wordLow = Value
}

object IntegerDivideOperation extends ChiselEnum {
  val signedQuotient, unsignedQuotient, signedRemainder, unsignedRemainder,
    signedWordQuotient, unsignedWordQuotient, signedWordRemainder, unsignedWordRemainder = Value
}

final class IntegerMultiplierModel(width: Int, tagWidth: Int, timing: ArithmeticIpTiming)
    extends ArithmeticIpModel(width, tagWidth, timing) {
  require(width == 32 || width == 64, s"整数乘法模型只支持 RV32/RV64，实际 width=$width")
  driveComputedResult(IntegerMultiplierModel.result(width, io.req.bits.operandA,
    io.req.bits.operandB, io.req.bits.operation))
}

object IntegerMultiplierModel {
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
    } else Mux(op === IntegerMultiplyOperation.low.asUInt, lowProduct, highProduct)
  }
}

final class IntegerDividerModel(width: Int, tagWidth: Int, timing: ArithmeticIpTiming)
    extends ArithmeticIpModel(width, tagWidth, timing) {
  require(width == 32 || width == 64, s"整数除法模型只支持 RV32/RV64，实际 width=$width")
  driveComputedResult(IntegerDividerModel.result(width, io.req.bits.operandA,
    io.req.bits.operandB, io.req.bits.operation))
}

object IntegerDividerModel {
  def result(width: Int, operandA: UInt, operandB: UInt, op: UInt): UInt = {
    require(width == 32 || width == 64, s"整数除法模型只支持 RV32/RV64，实际 width=$width")

    def negateActive(value: UInt, wordOperation: Bool): UInt = {
      val fullWidthNegated = (~value + 1.U)(width - 1, 0)
      if (width == 64) {
        val wordNegated = Cat(0.U(32.W), (~value(31, 0) + 1.U)(31, 0))
        Mux(wordOperation, wordNegated, fullWidthNegated)
      } else fullWidthNegated
    }

    def formatResult(value: UInt, wordOperation: Bool): UInt =
      if (width == 64) Mux(wordOperation, Cat(Fill(32, value(31)), value(31, 0)), value) else value

    val wordOperation = if (width == 64) {
      op === IntegerDivideOperation.signedWordQuotient.asUInt ||
        op === IntegerDivideOperation.unsignedWordQuotient.asUInt ||
        op === IntegerDivideOperation.signedWordRemainder.asUInt ||
        op === IntegerDivideOperation.unsignedWordRemainder.asUInt
    } else false.B
    val isRemainder = op === IntegerDivideOperation.signedRemainder.asUInt ||
      op === IntegerDivideOperation.unsignedRemainder.asUInt ||
      (if (width == 64) op === IntegerDivideOperation.signedWordRemainder.asUInt ||
        op === IntegerDivideOperation.unsignedWordRemainder.asUInt else false.B)
    val signedDivide = op === IntegerDivideOperation.signedQuotient.asUInt ||
      op === IntegerDivideOperation.signedRemainder.asUInt ||
      (if (width == 64) op === IntegerDivideOperation.signedWordQuotient.asUInt ||
        op === IntegerDivideOperation.signedWordRemainder.asUInt else false.B)
    val activeOperandA = if (width == 64) Mux(wordOperation, Cat(0.U(32.W), operandA(31, 0)), operandA) else operandA
    val activeOperandB = if (width == 64) Mux(wordOperation, Cat(0.U(32.W), operandB(31, 0)), operandB) else operandB
    val aSignBit = if (width == 64) Mux(wordOperation, operandA(31), operandA(63)) else operandA(31)
    val bSignBit = if (width == 64) Mux(wordOperation, operandB(31), operandB(63)) else operandB(31)
    val aNegative = aSignBit && signedDivide
    val bNegative = bSignBit && signedDivide
    val aMagnitude = Mux(aNegative, negateActive(activeOperandA, wordOperation), activeOperandA)
    val bMagnitude = Mux(bNegative, negateActive(activeOperandB, wordOperation), activeOperandB)
    val activeAllOnes = if (width == 64) {
      Mux(wordOperation, Cat(0.U(32.W), Fill(32, 1.U(1.W))), Fill(64, 1.U(1.W)))
    } else Fill(32, 1.U(1.W))
    val activeSignedMin = if (width == 64) {
      Mux(wordOperation, Cat(0.U(32.W), (BigInt(1) << 31).U(32.W)), (BigInt(1) << 63).U(64.W))
    } else (BigInt(1) << 31).U(32.W)
    val divideByZero = activeOperandB === 0.U
    val signedDivideOverflow = signedDivide && activeOperandA === activeSignedMin && activeOperandB === activeAllOnes
    val unsignedQuotient = aMagnitude / bMagnitude
    val unsignedRemainder = aMagnitude % bMagnitude
    val unsignedResult = Mux(isRemainder, unsignedRemainder, unsignedQuotient)
    val negateResult = signedDivide && Mux(isRemainder, aNegative, aNegative ^ bNegative)
    val signedResult = Mux(negateResult, negateActive(unsignedResult, wordOperation), unsignedResult)
    val exceptionalResult = Mux(divideByZero,
      Mux(isRemainder, activeOperandA, activeAllOnes),
      Mux(signedDivideOverflow, Mux(isRemainder, 0.U(width.W), activeOperandA), signedResult))
    formatResult(exceptionalResult, wordOperation)
  }
}
