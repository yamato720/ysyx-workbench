package scpu

import chisel3._
import chiseltest._
import firrtl.Utils.True
import org.scalatest.flatspec.AnyFlatSpec

class CPUTest extends AnyFlatSpec with ChiselScalatestTester{
  "CPU" should "correctly perform instruction fetch and decode" in {
    val Width = 64  // You can change this to 64 to test 64-bit CPU
    test(new CPU(Width = Width, Debug = true)) { c =>
      var cycleMax = if (Width == 64) 29 else 25
      c.reset.poke(true.B)
      c.clock.step(4)
      c.reset.poke(false.B)

      println("\n=== Testing instruction fetch and decode ===")
      var write_over = false
      for(insnum <- 0 until 29 + 1) {
        for (cycle <- 0 until cycleMax) {
          c.clock.step(1)
          val pc = c.io.pc.get.peek().litValue
          val regs = for(i <- 0 until 32) yield c.io.regs_debug.get(i).peek().litValue
          val tick_memwb = c.io.tick_memwb_debug.get.peek().litToBoolean
          val instruction = c.io.instruction.get.peek().litValue
          val imm = c.io.imm.get.peek().litValue
//          println("========================================")
//          println(f"imm value: 0x$imm%08X, decimal: $imm%d")
          if(tick_memwb){
            write_over = true
          }else if(write_over) {
            println("----------------------------------------")
            println(f"After instruction ${insnum}%d execution:")
            println(f"PC: 0x$pc%08X")
            println(f"Instruction: 0x$instruction%08X")
            println("Registers:")
            for (i <- 0 until 32 by 4) {
//              println(f"x${i}%02d: 0x${regs(i)}%08X  x${i+1}%02d: 0x${regs(i+1)}%08X  x${i+2}%02d: 0x${regs(i+2)}%08X  x${i+3}%02d: 0x${regs(i+3)}%08X")
              // 转换为有符号32位整数
              val signed0 = if (regs(i) > 0x7FFFFFFFL) (regs(i).toLong - 0x100000000L).toInt else regs(i).toInt
              val signed1 = if (regs(i+1) > 0x7FFFFFFFL) (regs(i+1).toLong - 0x100000000L).toInt else regs(i+1).toInt
              val signed2 = if (regs(i+2) > 0x7FFFFFFFL) (regs(i+2).toLong - 0x100000000L).toInt else regs(i+2).toInt
              val signed3 = if (regs(i+3) > 0x7FFFFFFFL) (regs(i+3).toLong - 0x100000000L).toInt else regs(i+3).toInt
              println(f"x${i}%02d: $signed0%11d      x${i+1}%02d:$signed1%11d      x${i+2}%02d:$signed2%11d      x${i+3}%02d:$signed3%11d")
            }
            write_over = false
          }
        }
      }


    }
  }
}
