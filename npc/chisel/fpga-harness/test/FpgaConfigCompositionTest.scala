package scpu.fpga

import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import scpu.NpcExternalAxiConfig
import scpu.fpga.u55c.U55cNpcFpgaConfig

class FpgaConfigCompositionTest extends AnyFlatSpec {
  private def withConfig[T](value: String)(body: => T): T = {
    val previous = sys.props.get("npc.config")
    System.setProperty("npc.config", value)
    try body
    finally previous.fold(System.clearProperty("npc.config"))(System.setProperty("npc.config", _))
  }

  "U55cNpcFpgaConfig" should "select the FPGA NPC policy and board clock" in {
    implicit val parameters: Parameters =
      new U55cNpcFpgaConfig

    val npcConfig = FpgaConfigParameters.npcCoreConfig
    assert(npcConfig.isa.xlen == 32)
    assert(npcConfig.axi.useExternalMaster)
    assert(npcConfig.debug.enableDispatchControl)
    assert(FpgaConfigParameters.board.contains(FpgaBoard.U55c))
    assert(FpgaConfigParameters.platform.clockMHz == 300)
  }

  it should "allow a left-side core fragment to override the default NPC" in {
    val customNpc = new NpcExternalAxiConfig().config
    implicit val parameters: Parameters =
      new WithNpcCoreConfig(customNpc) ++
        new U55cNpcFpgaConfig

    assert(FpgaConfigParameters.npcCoreConfig == customNpc)
    assert(!FpgaConfigParameters.npcCoreConfig.debug.enableDispatchControl)
    assert(FpgaConfigParameters.platform.clockMHz == 300)
  }

  it should "resolve registered terminal Configs and let the CDE board win" in {
    withConfig("scpu.fpga.u55c.U55cNpcFpgaConfig") {
      val (entry, construction) = CdeConfigResolver.resolve("Zcu102NpcFpgaConfig", Set("fpga-npc"))
      implicit val parameters: Parameters = construction

      assert(entry.board.contains("u55c"))
      assert(FpgaConfigParameters.board.contains(FpgaBoard.U55c))
      assert(FpgaConfigParameters.platform.board == FpgaBoard.U55c)
      assert(FpgaConfigParameters.platform.clockMHz == 300)
    }
  }
}
