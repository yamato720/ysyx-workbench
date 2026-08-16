package npc

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import npc.ip.arithmetic.{ArithmeticIpTiming, FloatingDpiOperator, IntegerMultiplierModel}
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
    assert(mulDiv.contains("module IntegerMultiplierModel"))
    assert(mulDiv.contains("module IntegerDividerModel"))
    assert(!mulDiv.contains("RRArbiter"))
    assert(floating.contains("module FloatingAlu"))
    assert(floating.contains("module FloatingFmaOperator"))
    assert(floating.contains("module FloatingCompareOperator"))
    assert(floating.contains("module FloatingDpiOperator"))
    assert(!floating.contains("RRArbiter"))
  }

  "Reusable arithmetic operators" should "elaborate independently from the execution backend" in {
    val multiply = _root_.circt.stage.ChiselStage.emitCHIRRTL(new IntegerMultiplierModel(
      32, 4, ArithmeticIpTiming(latency = 2)))
    val divide = _root_.circt.stage.ChiselStage.emitCHIRRTL(new FloatingDpiOperator(
      32, 4, ArithmeticIpTiming(latency = 1)))

    assert(multiply.contains("module IntegerMultiplierModel"))
    assert(divide.contains("module FloatingDpiOperator"))
    assert(divide.contains("NpcFloatingPointDpi"))
    assert(!divide.contains("DivSqrtRecFN_small"))
  }

  it should "accept independent multiply requests in consecutive cycles" in {
    simulate(new MulDivAlu(32, MulDivAlu.Config(
      completionCycles = 3,
      multiplyTiming = ArithmeticIpTiming(latency = 3)
    ))) { dut =>
      def driveRequest(tag: Int, left: Int, right: Int): Unit = {
        dut.io.req.bits.operandA.poke(left.U)
        dut.io.req.bits.operandB.poke(right.U)
        dut.io.req.bits.operandC.poke(0.U)
        dut.io.req.bits.aluOp.poke(NpcAluOp.MulDiv.MUL.asUInt)
        dut.io.req.bits.roundingMode.poke(0.U)
        dut.io.req.bits.pc.poke(0.U)
        dut.io.req.bits.instruction.poke(0.U)
        dut.io.req.bits.fcsr.poke(0.U)
        dut.io.req.bits.tag.poke(tag.U)
      }

      dut.io.req.valid.poke(false)
      dut.io.resp.ready.poke(true.B)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      driveRequest(tag = 1, left = 3, right = 7)
      dut.io.req.valid.poke(true.B)
      dut.io.req.ready.expect(true.B)
      dut.clock.step()
      driveRequest(tag = 2, left = 5, right = 11)
      dut.io.req.ready.expect(true.B)
      dut.clock.step()
      dut.io.req.valid.poke(false.B)

      var completed = Vector.empty[(BigInt, BigInt)]
      var guard = 0
      while (completed.size < 2 && guard < 20) {
        if (dut.io.resp.valid.peek().litToBoolean) {
          completed :+= dut.io.resp.bits.tag.peek().litValue -> dut.io.resp.bits.result.peek().litValue
        }
        dut.clock.step()
        guard += 1
      }
      assert(completed == Vector(BigInt(1) -> BigInt(21), BigInt(2) -> BigInt(55)))
    }
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
