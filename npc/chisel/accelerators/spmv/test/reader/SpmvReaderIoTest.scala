package accelerators.spmv.reader

import _root_.circt.stage.ChiselStage
import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

/** 检查 A/X reader 的公共 AXI4 端口和连续读时序。 */
class SpmvReaderIoTest extends AnyFlatSpec {
  private def assertAxiReadPort(chirrtl: String, moduleName: String): Unit = {
    assert(chirrtl.contains(s"module $moduleName"))
    assert(chirrtl.contains("io.axi.ar.bits.len"))
    assert(chirrtl.contains("flip r :"))
    assert(chirrtl.contains("data : UInt<512>"))
  }

  "A reader" should "展开公共 AXI4 读接口" in {
    assertAxiReadPort(ChiselStage.emitCHIRRTL(new SpmvAReader()), "SpmvAReader")
  }

  "X reader" should "展开独立的公共 AXI4 读接口" in {
    assertAxiReadPort(ChiselStage.emitCHIRRTL(new SpmvXReader()), "SpmvXReader")
  }

  "SPMV reader" should "在 4 KiB 边界拆分 burst 并把下游反压传回 HBM" in {
    simulate(new SpmvAReader()) { dut =>
      dut.io.request.valid.poke(false.B)
      dut.io.request.bits.address.poke(0.U)
      dut.io.request.bits.beats.poke(0.U)
      dut.io.axi.ar.ready.poke(false.B)
      dut.io.axi.r.valid.poke(false.B)
      dut.io.axi.r.bits.id.poke(0.U)
      dut.io.axi.r.bits.data.poke(0.U)
      dut.io.axi.r.bits.resp.poke(0.U)
      dut.io.axi.r.bits.last.poke(false.B)
      dut.io.output.ready.poke(false.B)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      // 0xfc0 只容纳一个 64-byte beat，因此三拍请求必须拆成 1 + 2。
      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.address.poke(0xfc0.U)
      dut.io.request.bits.beats.poke(3.U)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)

      dut.io.axi.ar.valid.expect(true.B)
      dut.io.axi.ar.bits.addr.expect(0xfc0.U)
      dut.io.axi.ar.bits.len.expect(0.U)
      dut.io.axi.ar.bits.size.expect(6.U)
      dut.clock.step(2)
      dut.io.axi.ar.bits.addr.expect(0xfc0.U)
      dut.io.axi.ar.ready.poke(true.B)
      dut.clock.step()
      dut.io.axi.ar.ready.poke(false.B)

      dut.io.axi.r.valid.poke(true.B)
      dut.io.axi.r.bits.data.poke(0x11.U)
      dut.io.axi.r.bits.last.poke(true.B)
      dut.io.axi.r.ready.expect(false.B)
      dut.io.output.valid.expect(true.B)
      dut.clock.step(2)
      dut.io.output.bits.data.expect(0x11.U)
      dut.io.output.ready.poke(true.B)
      dut.clock.step()
      dut.io.axi.r.valid.poke(false.B)
      dut.io.output.ready.poke(false.B)

      dut.io.axi.ar.valid.expect(true.B)
      dut.io.axi.ar.bits.addr.expect(0x1000.U)
      dut.io.axi.ar.bits.len.expect(1.U)
      dut.io.axi.ar.ready.poke(true.B)
      dut.clock.step()
      dut.io.axi.ar.ready.poke(false.B)

      for ((data, index) <- Seq(BigInt(0x22), BigInt(0x33)).zipWithIndex) {
        dut.io.axi.r.valid.poke(true.B)
        dut.io.axi.r.bits.data.poke(data.U)
        dut.io.axi.r.bits.last.poke((index == 1).B)
        dut.io.output.ready.poke(true.B)
        dut.io.output.bits.last.expect((index == 1).B)
        dut.clock.step()
      }
      dut.io.axi.r.valid.poke(false.B)
      dut.io.output.ready.poke(false.B)
      dut.io.done.expect(true.B)
      dut.io.idle.expect(true.B)
      dut.io.error.expect(false.B)
      dut.clock.step()
      dut.io.done.expect(false.B)
    }
  }

  it should "提前发出下一笔 AR 并跨 4 KiB 边界连续接收 R" in {
    simulate(new SpmvAReader()) { dut =>
      dut.io.request.valid.poke(false.B)
      dut.io.request.bits.address.poke(0.U)
      dut.io.request.bits.beats.poke(0.U)
      dut.io.axi.ar.ready.poke(true.B)
      dut.io.axi.r.valid.poke(false.B)
      dut.io.axi.r.bits.id.poke(0.U)
      dut.io.axi.r.bits.data.poke(0.U)
      dut.io.axi.r.bits.resp.poke(0.U)
      dut.io.axi.r.bits.last.poke(false.B)
      dut.io.output.ready.poke(true.B)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.address.poke(0.U)
      dut.io.request.bits.beats.poke(66.U)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)

      dut.io.axi.ar.valid.expect(true.B)
      dut.io.axi.ar.bits.addr.expect(0.U)
      dut.io.axi.ar.bits.len.expect(63.U)
      dut.clock.step()

      // 第一笔 R 尚未返回时，第二笔 AR 已经可以进入 outstanding 队列。
      dut.io.axi.ar.valid.expect(true.B)
      dut.io.axi.ar.bits.addr.expect(0x1000.U)
      dut.io.axi.ar.bits.len.expect(1.U)
      dut.clock.step()
      dut.io.axi.ar.valid.expect(false.B)

      for (index <- 0 until 66) {
        dut.io.axi.r.valid.poke(true.B)
        dut.io.axi.r.bits.data.poke(index.U)
        dut.io.axi.r.bits.last.poke((index == 63 || index == 65).B)
        dut.io.axi.r.ready.expect(true.B)
        dut.io.output.valid.expect(true.B)
        dut.io.output.bits.last.expect((index == 65).B)
        dut.clock.step()
      }
      dut.io.axi.r.valid.poke(false.B)
      dut.io.done.expect(true.B)
      dut.io.idle.expect(true.B)
      dut.io.error.expect(false.B)
    }
  }
}
