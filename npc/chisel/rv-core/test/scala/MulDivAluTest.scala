package scpu

import org.scalatest.flatspec.AnyFlatSpec

class MulDivAluTest extends AnyFlatSpec {
  "M and F ALU shells" should "own ISA operation selection above reusable operators" in {
    val mulDiv = _root_.circt.stage.ChiselStage.emitCHIRRTL(
      new MulDivAlu(32, MulDivAlu.Config(multiplyTiming = ArithmeticIpTiming(latency = 2)))
    )
    val floating = _root_.circt.stage.ChiselStage.emitCHIRRTL(new FloatingAlu(64))

    assert(mulDiv.contains("module MulDivAlu"))
    assert(mulDiv.contains("module IntegerMultiplierOperator"))
    assert(mulDiv.contains("module IntegerDividerOperator"))
    assert(floating.contains("module FloatingAlu"))
    assert(floating.contains("module FloatingFmaOperator"))
    assert(floating.contains("module FloatingCompareOperator"))
  }

  "Reusable arithmetic operators" should "elaborate independently from the execution backend" in {
    val multiply = _root_.circt.stage.ChiselStage.emitCHIRRTL(new IntegerMultiplierOperator(
      32, ComputeUnitConfig(), 4, ArithmeticIpTiming(latency = 2), "unused"))
    val divide = _root_.circt.stage.ChiselStage.emitCHIRRTL(new FloatingDividerOperator(
      32, ComputeUnitConfig(), 4, ArithmeticIpTiming(latency = 1), "unused"))

    assert(multiply.contains("module IntegerMultiplierOperator"))
    assert(divide.contains("module FloatingDividerOperator"))
    assert(divide.contains("NpcFloatingPointDpi"))
    assert(!divide.contains("DivSqrtRecFN_small"))
  }

  "外部算术端点" should "通过稳定的 BlackBox 适配器约定生成" in {
    val implementation = ComputeUnitConfig(backend = ComputeBackend.IP)
    val integer = _root_.circt.stage.ChiselStage.emitCHIRRTL(
      new MulDivAlu(32, MulDivAlu.Config(implementation = implementation))
    )
    val floating = _root_.circt.stage.ChiselStage.emitCHIRRTL(
      new FloatingAlu(32, FloatingAlu.Config(implementation = implementation))
    )

    assert(integer.contains("npc_int_multiplier_adapter"))
    assert(floating.contains("npc_fp_divider_adapter"))
  }
}
