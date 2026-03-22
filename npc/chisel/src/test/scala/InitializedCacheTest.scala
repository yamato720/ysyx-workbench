package scpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class InitializedCacheTest extends AnyFlatSpec with ChiselScalatestTester {
  
  "insCacheL1" should "load data from initialization file" in {
    test(new insCacheL1(useBlackBox = false, initFile = Some("init_data/test_program.hex")))
      .withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      
      dut.io.ena.poke(true.B)
      dut.io.wea.poke(false.B)
      dut.io.enb.poke(false.B)
      
      // 读取初始化文件中的前几个字节
      // 0x93, 0x00, 0xf0, 0x00
      dut.io.addra.poke(0.U)
      dut.clock.step(1)
      dut.io.douta.expect(0x93.U, "Address 0 should be 0x93")
      
      dut.io.addra.poke(1.U)
      dut.clock.step(1)
      dut.io.douta.expect(0x00.U, "Address 1 should be 0x00")
      
      dut.io.addra.poke(2.U)
      dut.clock.step(1)
      dut.io.douta.expect(0xf0.U, "Address 2 should be 0xf0")
      
      dut.io.addra.poke(3.U)
      dut.clock.step(1)
      dut.io.douta.expect(0x00.U, "Address 3 should be 0x00")
      
      // 验证可以读取后面的指令
      dut.io.addra.poke(4.U)
      dut.clock.step(1)
      dut.io.douta.expect(0x13.U, "Address 4 should be 0x13")
    }
  }
  
  "dataCacheL1" should "work without initialization file" in {
    test(new dataCacheL1()).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      dut.io.ena.poke(true.B)
      dut.io.wea.poke(false.B)
      dut.io.enb.poke(false.B)
      
      // 未初始化的内存应该都是 0
      for (addr <- Seq(0, 10, 100, 1000)) {
        dut.io.addra.poke(addr.U)
        dut.clock.step(1)
        dut.io.douta.expect(0.U, s"Address $addr should be 0")
      }
    }
  }
  
  "insCacheL1" should "allow writing over initialized data" in {
    test(new insCacheL1(initFile = Some("init_data/test_program.hex")))
      .withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      
      dut.io.ena.poke(true.B)
      dut.io.enb.poke(false.B)
      
      // 读取初始值
      dut.io.wea.poke(false.B)
      dut.io.addra.poke(0.U)
      dut.clock.step(1)
      dut.io.douta.expect(0x93.U, "Initial value at address 0")
      
      // 覆盖写入
      dut.io.wea.poke(true.B)
      dut.io.addra.poke(0.U)
      dut.io.dina.poke(0xAA.U)
      dut.clock.step(1)
      
      // 读取新值
      dut.io.wea.poke(false.B)
      dut.io.addra.poke(0.U)
      dut.clock.step(1)
      dut.io.douta.expect(0xAA.U, "New value at address 0 after write")
    }
  }
}
