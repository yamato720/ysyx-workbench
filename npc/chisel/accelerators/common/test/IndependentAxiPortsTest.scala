package accelerators.common

import _root_.circt.stage.ChiselStage
import chisel3._
import chisel3.util._
import org.scalatest.flatspec.AnyFlatSpec

private final class TestRequest extends Bundle {
  val address = UInt(64.W)
}

private final class TestBeat extends Bundle {
  val data = UInt(512.W)
}

private final class TestReadPort extends Module {
  val io = IO(new IndependentAxiReadPortIO(
    () => new TestRequest,
    () => new TestBeat,
    64,
    512,
    4
  ))

  io.request.ready := true.B
  io.axi.ar.valid := false.B
  io.axi.ar.bits := 0.U.asTypeOf(io.axi.ar.bits)
  io.axi.r.ready := false.B
  io.output.valid := false.B
  io.output.bits := 0.U.asTypeOf(io.output.bits)
  io.idle := true.B
  io.busy := false.B
  io.done := false.B
  io.error := false.B
}

private final class TestWritePort extends Module {
  val io = IO(new IndependentAxiWritePortIO(
    () => new TestRequest,
    () => new TestBeat,
    64,
    512,
    4
  ))

  io.request.ready := true.B
  io.input.ready := true.B
  io.axi.aw.valid := false.B
  io.axi.aw.bits := 0.U.asTypeOf(io.axi.aw.bits)
  io.axi.w.valid := false.B
  io.axi.w.bits := 0.U.asTypeOf(io.axi.w.bits)
  io.axi.b.ready := false.B
  io.idle := true.B
  io.busy := false.B
  io.done := false.B
  io.error := false.B
}

private final class TestReadWritePort extends Module {
  val io = IO(new IndependentAxiReadWritePortIO(
    () => new TestRequest,
    () => new TestBeat,
    () => new TestRequest,
    () => new TestBeat,
    64,
    512,
    4
  ))

  io.readRequest.ready := true.B
  io.readOutput.valid := false.B
  io.readOutput.bits := 0.U.asTypeOf(io.readOutput.bits)
  io.writeRequest.ready := true.B
  io.writeInput.ready := true.B
  io.axi.aw.valid := false.B
  io.axi.aw.bits := 0.U.asTypeOf(io.axi.aw.bits)
  io.axi.w.valid := false.B
  io.axi.w.bits := 0.U.asTypeOf(io.axi.w.bits)
  io.axi.b.ready := false.B
  io.axi.ar.valid := false.B
  io.axi.ar.bits := 0.U.asTypeOf(io.axi.ar.bits)
  io.axi.r.ready := false.B
  io.readIdle := true.B
  io.readBusy := false.B
  io.readDone := false.B
  io.readError := false.B
  io.writeIdle := true.B
  io.writeBusy := false.B
  io.writeDone := false.B
  io.writeError := false.B
}

private final class TestReadPorts extends IndependentAxiReadPorts[TestRequest, TestBeat](
  3,
  () => new TestRequest,
  () => new TestBeat,
  64,
  512,
  4,
  () => new TestReadPort
)

private final class TestWritePorts extends IndependentAxiWritePorts[TestRequest, TestBeat](
  3,
  () => new TestRequest,
  () => new TestBeat,
  64,
  512,
  4,
  () => new TestWritePort
)

private final class TestReadWritePorts
  extends IndependentAxiReadWritePorts[TestRequest, TestBeat, TestRequest, TestBeat](
    3,
    () => new TestRequest,
    () => new TestBeat,
    () => new TestRequest,
    () => new TestBeat,
    64,
    512,
    4,
    () => new TestReadWritePort
  )

/** 检查公共基础模块可以独立展开读、写和读写 AXI4 端口。 */
class IndependentAxiPortsTest extends AnyFlatSpec {
  private def assertThreePorts(chirrtl: String, channel: String): Unit = {
    assert(chirrtl.contains("ports_0"))
    assert(chirrtl.contains("ports_1"))
    assert(chirrtl.contains("ports_2"))
    assert(chirrtl.contains(s"io.axi[2].$channel"))
  }

  "IndependentAxiReadPorts" should "展开多路独立 AR/R 端口" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new TestReadPorts)
    assertThreePorts(chirrtl, "ar")
    assert(chirrtl.contains("io.axi[2].r"))
  }

  "IndependentAxiWritePorts" should "展开多路独立 AW/W/B 端口" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new TestWritePorts)
    assertThreePorts(chirrtl, "aw")
    assert(chirrtl.contains("io.axi[2].w"))
    assert(chirrtl.contains("io.axi[2].b"))
  }

  "IndependentAxiReadWritePorts" should "展开多路独立 AXI4 五通道端口" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new TestReadWritePorts)
    assertThreePorts(chirrtl, "aw")
    Seq("w", "b", "ar", "r").foreach(channel =>
      assert(chirrtl.contains(s"io.axi[2].$channel")))
  }
}
