package scpu

import java.nio.file.Paths
import org.scalatest.flatspec.AnyFlatSpec

class ConfigCatalogTest extends AnyFlatSpec {
  private def withConfig[T](value: String)(body: => T): T = {
    val previous = sys.props.get("npc.config")
    System.setProperty("npc.config", value)
    try body
    finally previous.fold(System.clearProperty("npc.config"))(System.setProperty("npc.config", _))
  }

  "ConfigCatalog" should "resolve both a short name and its canonical FQCN" in {
    val byShortName = ConfigCatalog.resolve("NpcDpiConfig", Set("npc"))
    val byClassName = ConfigCatalog.resolve("scpu.NpcDpiConfig", Set("npc"))
    assert(byShortName == byClassName)
    assert(byShortName.className == "scpu.NpcDpiConfig")
  }

  it should "reject unknown entries and scope mismatches" in {
    assertThrows[IllegalArgumentException](ConfigCatalog.resolve("MissingConfig", Set("npc")))
    assertThrows[IllegalArgumentException](ConfigCatalog.resolve("YsyxSimulationConfig", Set("npc")))
  }

  "ConfigCatalogGenerator" should "discover the complete Make Configs from Scala sources" in {
    val root = ConfigCatalogGenerator.locateNpcRoot(Paths.get(".").toAbsolutePath.normalize).get
    val generated = ConfigCatalogGenerator.discover(root)
    val names = generated.map(_.shortName).toSet

    assert(names.contains("NpcStandaloneConfig"))
    assert(names.contains("YsyxSimulationConfig"))
    assert(names.contains("U55cYsyxSocFpgaConfig"))
    assert(names.contains("Zcu102NpcFpgaConfig"))
    assert(names.contains("NpcFullIsa64PipelineDualForwardingDpiConfig"))
    assert(names.contains("U55cFullIsa64NpcFpgaConfig"))
    assert(!names.contains("NpcPipelineCheckConfig"))
    assert(generated.exists(_.shortName == "NpcPipelineDpiConfig"))
  }

  "NpcConfigResolver" should "instantiate only registered complete NPC configurations" in {
    withConfig("NpcPipelineDpiConfig") {
      val (entry, construction) = NpcConfigResolver.resolve("NpcDpiConfig")
      assert(entry.className == "scpu.NpcPipelineDpiConfig")
      assert(construction.config.pipeline.enablePipeline)
      assert(construction.capability == "verilator-npc")
    }
  }

  "ConstructionProfile" should "derive stable host and protocol ABIs from the terminal capability" in {
    val entry = ConfigCatalog.resolve("NpcDpiConfig", Set("npc"))
    val config = new NpcDpiConfig().config
    val values = ConstructionProfile.values(entry, "verilator-npc", config).toMap

    assert(values("HOST_ABI") == "nemu-construction-v1")
    assert(values("PROTOCOL_ABI") == "npc-dpi-v1")
    assert(values("ISA_STRING") == "rv64im_zicsr")
  }

  it should "describe the RV64IMF forwarding comparison configuration exactly" in {
    val entry = ConfigCatalog.resolve("NpcFullIsa64PipelineDualForwardingDpiConfig", Set("npc"))
    val config = new NpcFullIsa64PipelineDualForwardingDpiConfig().config
    val values = ConstructionProfile.values(entry, "verilator-npc", config).toMap

    assert(values("ISA_STRING") == "rv64imf_zicsr")
    assert(values("PIPELINE") == "1")
    assert(values("ID_FWD") == "1")
    assert(values("EX_FWD") == "1")
  }
}
