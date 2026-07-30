package npc

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class FenceInstructionTest extends AnyFlatSpec {
  "NpcDecodeUnit" should "accept every standard FENCE pred/succ combination without Zifencei" in {
    simulate(new NpcDecodeUnit(ISAConfig(xlen = 64, Zifencei = false))) { dut =>
      // All pred/succ masks use the same conservative full-barrier path.
      for (predecessor <- 0 until 16; successor <- 0 until 16) {
        val instruction = (predecessor << 24) | (successor << 20) | 0x0f
        dut.io.instruction.poke(instruction.U)
        dut.io.signals.trapEnable.expect(false.B)
        dut.io.signals.privilegedInstruction.expect(false.B)
      }

      // FENCE.I remains gated by the explicit Zifencei Config selection.
      dut.io.instruction.poke("h0000100f".U)
      dut.io.signals.trapEnable.expect(true.B)
    }
  }

  it should "accept FENCE.I when Zifencei is enabled" in {
    simulate(new NpcDecodeUnit(ISAConfig(xlen = 64, Zifencei = true))) { dut =>
      dut.io.instruction.poke("h0000100f".U)
      dut.io.signals.trapEnable.expect(false.B)
    }
  }
}
