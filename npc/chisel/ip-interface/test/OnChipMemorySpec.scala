package npc.ip.memory

import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

final class OnChipMemorySpec extends AnyFlatSpec with Matchers {
  "真双口片上 RAM 合同" should "展开两个独立的同步读写口" in {
    val chirrtl = ChiselStage.emitCHIRRTL(
      new OnChipTrueDualPortMemory(1024, 64, OnChipMemoryPrimitive.UltraRam))

    chirrtl should include("module OnChipTrueDualPortMemory")
    chirrtl should include("NpcOnChipTrueDualPortMemory")
    chirrtl should include("io.a.enable")
    chirrtl should include("io.a.write")
    chirrtl should include("io.a.address")
    chirrtl should include("io.a.wdata")
    chirrtl should include("io.a.rdata")
    chirrtl should include("io.b.enable")
    chirrtl should include("io.b.rdata")
  }

  it should "拒绝非法几何" in {
    assertThrows[IllegalArgumentException](
      new OnChipTrueDualPortMemory(depth = 3, dataWidth = 64))
    assertThrows[IllegalArgumentException](
      new OnChipTrueDualPortMemory(depth = 1024, dataWidth = 96))
  }
}
