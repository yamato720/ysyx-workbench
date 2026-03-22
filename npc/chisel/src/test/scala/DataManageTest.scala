package scpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class InsCacheL1Test extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "insCacheL1"

  it should "correctly read and write through port A" in {
    test(new insCacheL1()).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      // 初始化端口 B（保持不活动）
      dut.io.enb.poke(false.B)
      dut.io.web.poke(false.B)
      dut.io.addrb.poke(0.U)
      dut.io.dinb.poke(0.U)

      // 通过端口 A 写入数据
      dut.io.ena.poke(true.B)
      dut.io.wea.poke(true.B)
      dut.io.addra.poke(0.U)
      dut.io.dina.poke(0x93.U)
      dut.clock.step(1)

      dut.io.addra.poke(1.U)
      dut.io.dina.poke(0x00.U)
      dut.clock.step(1)

      dut.io.addra.poke(2.U)
      dut.io.dina.poke(0xF0.U)
      dut.clock.step(1)

      dut.io.addra.poke(3.U)
      dut.io.dina.poke(0x00.U)
      dut.clock.step(1)

      // 切换到读模式
      dut.io.wea.poke(false.B)
      
      // 读取地址 0
      dut.io.addra.poke(0.U)
      dut.clock.step(1)
      dut.io.douta.expect(0x93.U)

      // 读取地址 1
      dut.io.addra.poke(1.U)
      dut.clock.step(1)
      dut.io.douta.expect(0x00.U)

      // 读取地址 2
      dut.io.addra.poke(2.U)
      dut.clock.step(1)
      dut.io.douta.expect(0xF0.U)

      // 读取地址 3
      dut.io.addra.poke(3.U)
      dut.clock.step(1)
      dut.io.douta.expect(0x00.U)
    }
  }

  it should "correctly read and write through port B" in {
    test(new insCacheL1()).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      // 初始化端口 A（保持不活动）
      dut.io.ena.poke(false.B)
      dut.io.wea.poke(false.B)
      dut.io.addra.poke(0.U)
      dut.io.dina.poke(0.U)

      // 通过端口 B 写入数据
      dut.io.enb.poke(true.B)
      dut.io.web.poke(true.B)
      dut.io.addrb.poke(100.U)
      dut.io.dinb.poke(0xAA.U)
      dut.clock.step(1)

      dut.io.addrb.poke(101.U)
      dut.io.dinb.poke(0xBB.U)
      dut.clock.step(1)

      // 切换到读模式
      dut.io.web.poke(false.B)
      
      // 读取刚才写入的数据
      dut.io.addrb.poke(100.U)
      dut.clock.step(1)
      dut.io.doutb.expect(0xAA.U)
      
      dut.io.addrb.poke(101.U)
      dut.clock.step(1)
      dut.io.doutb.expect(0xBB.U)
    }
  }

  it should "support dual-port concurrent access" in {
    test(new insCacheL1()).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      // 通过端口 A 写入
      dut.io.ena.poke(true.B)
      dut.io.wea.poke(true.B)
      dut.io.addra.poke(10.U)
      dut.io.dina.poke(0x11.U)

      // 同时通过端口 B 写入不同地址
      dut.io.enb.poke(true.B)
      dut.io.web.poke(true.B)
      dut.io.addrb.poke(20.U)
      dut.io.dinb.poke(0x22.U)

      dut.clock.step(1)

      // 切换到读模式
      dut.io.wea.poke(false.B)
      dut.io.web.poke(false.B)

      // 端口 A 读取自己写入的数据
      dut.io.addra.poke(10.U)
      // 端口 B 读取端口 A 写入的数据
      dut.io.addrb.poke(10.U)
      dut.clock.step(1)
      dut.io.douta.expect(0x11.U)
      dut.io.doutb.expect(0x11.U)
      
      // 两个端口都读取端口 B 写入的数据
      dut.io.addra.poke(20.U)
      dut.io.addrb.poke(20.U)
      dut.clock.step(1)
      dut.io.douta.expect(0x22.U)
      dut.io.doutb.expect(0x22.U)
    }
  }
}

class DataCacheL1Test extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "dataCacheL1"

  it should "correctly read and write through port A" in {
    test(new dataCacheL1()).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      // 初始化端口 B
      dut.io.enb.poke(false.B)
      dut.io.web.poke(false.B)
      dut.io.addrb.poke(0.U)
      dut.io.dinb.poke(0.U)

      // 通过端口 A 写入数据
      dut.io.ena.poke(true.B)
      dut.io.wea.poke(true.B)
      
      for (i <- 0 until 10) {
        dut.io.addra.poke(i.U)
        dut.io.dina.poke((i * 10).U)
        dut.clock.step(1)
      }

      // 切换到读模式并验证数据
      dut.io.wea.poke(false.B)
      
      for (i <- 0 until 10) {
        dut.io.addra.poke(i.U)
        dut.clock.step(1)
        dut.io.douta.expect((i * 10).U)
      }
    }
  }

  it should "correctly read and write through port B" in {
    test(new dataCacheL1()).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      // 初始化端口 A
      dut.io.ena.poke(false.B)
      dut.io.wea.poke(false.B)
      dut.io.addra.poke(0.U)
      dut.io.dina.poke(0.U)

      // 通过端口 B 写入数据
      dut.io.enb.poke(true.B)
      dut.io.web.poke(true.B)
      
      val testData = Seq(0xFF, 0x00, 0xAA, 0x55, 0x12, 0x34, 0x56, 0x78)
      for ((value, i) <- testData.zipWithIndex) {
        dut.io.addrb.poke((500 + i).U)
        dut.io.dinb.poke(value.U)
        dut.clock.step(1)
      }

      // 切换到读模式并验证数据
      dut.io.web.poke(false.B)
      
      for ((value, i) <- testData.zipWithIndex) {
        dut.io.addrb.poke((500 + i).U)
        dut.clock.step(1)
        dut.io.doutb.expect(value.U)
      }
    }
  }

  it should "initialize memory to zero" in {
    test(new dataCacheL1()).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      dut.io.ena.poke(true.B)
      dut.io.wea.poke(false.B)

      dut.io.enb.poke(false.B)
      dut.io.web.poke(false.B)

      // 读取几个随机地址，应该都是 0
      val testAddresses = Seq(0, 100, 500, 1000, 2000, 4000)
      
      for (addr <- testAddresses) {
        dut.io.addra.poke(addr.U)
        dut.clock.step(1)
        dut.io.douta.expect(0.U, s"Address $addr should be initialized to 0")
      }
    }
  }
}
