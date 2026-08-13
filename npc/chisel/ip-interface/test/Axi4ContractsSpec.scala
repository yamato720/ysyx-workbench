package npc.ip.axi

import circt.stage.ChiselStage
import chisel3._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

private final class Axi4ContractBoundary extends Module {
  val io = IO(new Bundle {
    val read = new Axi4ReadMasterIO(64, 512, 4)
    val write = new Axi4WriteMasterIO(64, 512, 4)
    val readWrite = new Axi4ReadWriteMasterIO(64, 512, 4)
  })

  io.read.ar.valid := false.B
  io.read.ar.bits := 0.U.asTypeOf(io.read.ar.bits)
  io.read.r.ready := false.B
  io.write.aw.valid := false.B
  io.write.aw.bits := 0.U.asTypeOf(io.write.aw.bits)
  io.write.w.valid := false.B
  io.write.w.bits := 0.U.asTypeOf(io.write.w.bits)
  io.write.b.ready := false.B
  io.readWrite.aw.valid := false.B
  io.readWrite.aw.bits := 0.U.asTypeOf(io.readWrite.aw.bits)
  io.readWrite.w.valid := false.B
  io.readWrite.w.bits := 0.U.asTypeOf(io.readWrite.w.bits)
  io.readWrite.b.ready := false.B
  io.readWrite.ar.valid := false.B
  io.readWrite.ar.bits := 0.U.asTypeOf(io.readWrite.ar.bits)
  io.readWrite.r.ready := false.B
}

final class Axi4ContractsSpec extends AnyFlatSpec with Matchers {
  "公共 AXI4 契约" should "独立展开只读、只写和完整读写主端口" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new Axi4ContractBoundary)

    chirrtl should include("io.read.ar")
    chirrtl should include("io.read.r")
    chirrtl should include("io.write.aw")
    chirrtl should include("io.write.w")
    chirrtl should include("io.write.b")
    chirrtl should include("io.readWrite.ar")
    chirrtl should include("io.readWrite.r")
    chirrtl should include("io.readWrite.aw")
    chirrtl should include("io.readWrite.w")
    chirrtl should include("io.readWrite.b")
  }

  it should "拒绝非法总线几何" in {
    assertThrows[IllegalArgumentException](new Axi4ReadMasterIO(0, 512, 4))
    assertThrows[IllegalArgumentException](new Axi4WriteMasterIO(64, 96, 4))
    assertThrows[IllegalArgumentException](new Axi4ReadWriteMasterIO(64, 512, 0))
  }
}
