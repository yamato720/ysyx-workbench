package scpu

import chisel3._
import chisel3.util._

/** RV32M/RV64M 执行外壳。它译码架构 ALU 操作，发射可复用乘除法算子并汇聚响应。
  * 算子本身位于 `compute/`，可供其他模块复用。
  */
object MulDivAlu {
  case class Config(
    implementation: ComputeUnitConfig = ComputeUnitConfig(),
    completionCycles: Int = 8,
    multiplyTiming: ArithmeticIpTiming = ArithmeticIpTiming(latency = 3),
    dividerInitiationInterval: Int = 1,
    tagWidth: Int = 4,
    multiplyAdapterModuleName: String = "npc_int_multiplier_adapter",
    dividerAdapterModuleName: String = "npc_int_divider_adapter",
  ) {
    require(completionCycles >= 1, s"MulDivAlu completionCycles must be positive, got $completionCycles")
    require(tagWidth >= 1, s"MulDivAlu tagWidth must be positive, got $tagWidth")
    require(multiplyAdapterModuleName.nonEmpty, "Integer multiplier adapter module name must not be empty")
    require(dividerAdapterModuleName.nonEmpty, "Integer divider adapter module name must not be empty")

    def divideTiming: ArithmeticIpTiming = ArithmeticIpTiming(
      latency = completionCycles,
      initiationInterval = dividerInitiationInterval,
      responseFifoDepth = implementation.ip.outputFifoDepth
    )
  }

  def isMultiply(op: UInt): Bool =
    op === NpcAluOp.MulDiv.MUL.asUInt || op === NpcAluOp.MulDiv.MULH.asUInt || op === NpcAluOp.MulDiv.MULHSU.asUInt ||
      op === NpcAluOp.MulDiv.MULHU.asUInt || op === NpcAluOp.MulDiv.MULW.asUInt

  def isDivide(op: UInt): Bool =
    op === NpcAluOp.MulDiv.DIV.asUInt || op === NpcAluOp.MulDiv.DIVU.asUInt || op === NpcAluOp.MulDiv.REM.asUInt ||
      op === NpcAluOp.MulDiv.REMU.asUInt || op === NpcAluOp.MulDiv.DIVW.asUInt || op === NpcAluOp.MulDiv.DIVUW.asUInt ||
      op === NpcAluOp.MulDiv.REMW.asUInt || op === NpcAluOp.MulDiv.REMUW.asUInt
}

class MulDivAlu(width: Int, config: MulDivAlu.Config = MulDivAlu.Config()) extends Module {
  require(width == 32 || width == 64, s"MulDivAlu supports RV32/RV64, got width=$width")
  val io = IO(new AluIO(width, config.tagWidth))

  private val multiplier = Module(new IntegerMultiplierOperator(
    width, config.implementation, config.tagWidth, config.multiplyTiming, config.multiplyAdapterModuleName))
  private val divider = Module(new IntegerDividerOperator(
    width, config.implementation, config.tagWidth, config.divideTiming, config.dividerAdapterModuleName))
  private val multiplySelected = MulDivAlu.isMultiply(io.req.bits.aluOp)
  private val divideSelected = MulDivAlu.isDivide(io.req.bits.aluOp)
  private val multiplyOperation = MuxLookup(io.req.bits.aluOp, 0.U(ArithmeticOperation.width.W))(Seq(
    NpcAluOp.MulDiv.MUL.asUInt -> IntegerMultiplyOperation.low.asUInt,
    NpcAluOp.MulDiv.MULH.asUInt -> IntegerMultiplyOperation.signedHigh.asUInt,
    NpcAluOp.MulDiv.MULHSU.asUInt -> IntegerMultiplyOperation.signedUnsignedHigh.asUInt,
    NpcAluOp.MulDiv.MULHU.asUInt -> IntegerMultiplyOperation.unsignedHigh.asUInt,
    NpcAluOp.MulDiv.MULW.asUInt -> IntegerMultiplyOperation.wordLow.asUInt
  ))
  private val divideOperation = MuxLookup(io.req.bits.aluOp, 0.U(ArithmeticOperation.width.W))(Seq(
    NpcAluOp.MulDiv.DIV.asUInt -> IntegerDivideOperation.signedQuotient.asUInt,
    NpcAluOp.MulDiv.DIVU.asUInt -> IntegerDivideOperation.unsignedQuotient.asUInt,
    NpcAluOp.MulDiv.REM.asUInt -> IntegerDivideOperation.signedRemainder.asUInt,
    NpcAluOp.MulDiv.REMU.asUInt -> IntegerDivideOperation.unsignedRemainder.asUInt,
    NpcAluOp.MulDiv.DIVW.asUInt -> IntegerDivideOperation.signedWordQuotient.asUInt,
    NpcAluOp.MulDiv.DIVUW.asUInt -> IntegerDivideOperation.unsignedWordQuotient.asUInt,
    NpcAluOp.MulDiv.REMW.asUInt -> IntegerDivideOperation.signedWordRemainder.asUInt,
    NpcAluOp.MulDiv.REMUW.asUInt -> IntegerDivideOperation.unsignedWordRemainder.asUInt
  ))

  private def forwardRequest(endpoint: ArithmeticOperatorIO, selected: Bool, operation: UInt): Unit = {
    endpoint.req.valid := io.req.valid && selected
    endpoint.req.bits.operandA := io.req.bits.operandA
    endpoint.req.bits.operandB := io.req.bits.operandB
    endpoint.req.bits.operandC := io.req.bits.operandC
    endpoint.req.bits.operation := operation
    endpoint.req.bits.roundingMode := io.req.bits.roundingMode
    endpoint.req.bits.pc := io.req.bits.pc
    endpoint.req.bits.instruction := io.req.bits.instruction
    endpoint.req.bits.fcsr := io.req.bits.fcsr
    endpoint.req.bits.tag := io.req.bits.tag
  }
  forwardRequest(multiplier.io, multiplySelected, multiplyOperation)
  forwardRequest(divider.io, divideSelected, divideOperation)
  io.req.ready := Mux(multiplySelected, multiplier.io.req.ready,
    Mux(divideSelected, divider.io.req.ready, false.B))

  private val responses = Module(new RRArbiter(new ArithmeticResponse(width, config.tagWidth), 2))
  responses.io.in(0) <> multiplier.io.resp
  responses.io.in(1) <> divider.io.resp
  io.resp <> responses.io.out
}
