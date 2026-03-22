package scpu

import chisel3._
import chiseltest._
import firrtl.Utils.True
import org.scalatest.flatspec.AnyFlatSpec

class BRAMDirectTest extends AnyFlatSpec with ChiselScalatestTester {
  "insCacheL1" should "load correct bytes from hex file" in {
    test(new insCacheL1(initFile = Some("init_data/program.hex"))) { c =>
      c.reset.poke(true.B)
      c.clock.step(100)
      c.reset.poke(false.B)

      println("\n=== Testing direct BRAM reads ===")
      var ins = Array.fill(8)("")

      // 读取前 16 个字节（4 条指令）
      var addr = 0
      var insIDX = 0
      while(addr < 16) {
        c.io.ena.poke(true.B)
        c.io.addra.poke(addr.U)
        c.io.wea.poke(false.B)
        c.io.enb.poke(true.B)
        c.io.addrb.poke((addr + 1).U)
        println(f"Reading addresses ${addr}%02d and ${addr + 1}%02d")
        c.io.web.poke(false.B)
        c.clock.step(1)
        val data1 = c.io.douta.peek().litValue
        val data2 = c.io.doutb.peek().litValue
        println(f"Address ${addr}%02d: Data1 = ${data1}%02x, Data2 = ${data2}%02x")
        if(addr % 4 == 0) {
          ins(insIDX) = f"${data2}%02x" + f"${data1}%02x"
          insIDX += 1
        } else {
          ins(insIDX - 1) =  f"${data2}%02x" + f"${data1}%02x" + ins(insIDX - 1)
        }

        addr += 2
      }
      for(i <- 0 until 4) {
        println(f"Instruction ${i}%d: 0x${ins(i)}%s")
      }

      println("\n=== Expected values (first 4 instructions in little-endian) ===")
      println(s"Instruction ${ins(0)} should be: 0C800093 at addresses 0-3")
      println(s"Instruction ${ins(1)} should be: 07F00113 at addresses 4-7")
      println(s"Instruction ${ins(2)} should be: F8000193 at addresses 8-11")
      println(s"Instruction ${ins(3)} should be: 0FF00213 at addresses 12-15")
    }
  }
}

