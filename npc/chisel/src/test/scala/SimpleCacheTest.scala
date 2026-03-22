package scpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class SimpleCacheTest extends AnyFlatSpec with ChiselScalatestTester {
  "insCacheL1" should "perform basic write and read" in {
    test(new insCacheL1()).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      // 禁用端口 B
      dut.io.enb.poke(false.B)
      dut.io.web.poke(false.B)
      dut.io.addrb.poke(0.U)
      dut.io.dinb.poke(0.U)

      // Step 0: 初始状态
      dut.io.ena.poke(true.B)
      dut.io.wea.poke(true.B)
      dut.io.addra.poke(5.U)
      dut.io.dina.poke(0xAB.U)
      
      // Step 1: 写入地址 5，值 0xAB
      dut.clock.step(1)
      println(s"After write cycle, douta = ${dut.io.douta.peek().litValue}")
      
      // Step 2: 读取地址 5
      dut.io.wea.poke(false.B)
      dut.io.addra.poke(5.U)
      dut.clock.step(1)
      println(s"After read request, douta = ${dut.io.douta.peek().litValue}")
      
      // Step 3: 现在输出应该有效
      dut.io.douta.expect(0xAB.U)
    }
  }
}
