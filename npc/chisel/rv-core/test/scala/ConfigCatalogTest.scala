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
    val byShortName = ConfigCatalog.resolve("SimulationConfig", Set("npc"))
    val byClassName = ConfigCatalog.resolve("scpu.SimulationConfig", Set("npc"))
    assert(byShortName == byClassName)
    assert(byShortName.className == "scpu.SimulationConfig")
  }

  it should "reject unknown entries and scope mismatches" in {
    assertThrows[IllegalArgumentException](ConfigCatalog.resolve("MissingConfig", Set("npc")))
    assertThrows[IllegalArgumentException](ConfigCatalog.resolve("YsyxSimulationConfig", Set("npc")))
  }

  "ConfigCatalogGenerator" should "discover the complete Make Configs from Scala sources" in {
    val root = ConfigCatalogGenerator.locateNpcRoot(Paths.get(".").toAbsolutePath.normalize).get
    val generated = ConfigCatalogGenerator.discover(root)
    val names = generated.map(_.shortName).toSet

    assert(names.contains("StandaloneConfig"))
    assert(names.contains("YsyxSimulationConfig"))
    assert(names.contains("U55cYsyxSocFpgaConfig"))
    assert(names.contains("Zcu102NpcFpgaConfig"))
    assert(names.contains("FullIsa64PipelineDualForwardingSimulationConfig"))
    assert(names.contains("Zcu102Rv32OperatorSimulationConfig"))
    assert(names.contains("U55cRv32OperatorSimulationConfig"))
    assert(names.contains("U55cRv64OperatorSimulationConfig"))
    assert(names.contains("U55cFullIsa64NpcFpgaConfig"))
    assert(names.contains("U55cFullIsa64Npc250MHzFpgaConfig"))
    assert(!names.contains("FpgaConfig"))
    assert(!names.contains("ExternalAxiConfig"))
    assert(!names.contains("YsyxElaborateConfig"))
    assert(!names.contains("PipelineCheckConfig"))
    assert(generated.exists(_.shortName == "PipelineSimulationConfig"))
  }

  it should "ignore Config-shaped text in comments and string literals" in {
    val source =
      """package scpu
        |/** class CommentConfig extends ConstructionConfig */
        |val example = "class StringConfig extends ConstructionConfig"
        |class RealConfig extends ConstructionConfig
        |""".stripMargin
    val code = ConfigCatalogGenerator.codeOnly(source)

    assert(!code.contains("CommentConfig"))
    assert(!code.contains("StringConfig"))
    assert(code.contains("RealConfig"))
  }

  "ConfigResolver" should "instantiate only registered complete NPC configurations" in {
    withConfig("PipelineSimulationConfig") {
      val (entry, construction) = ConfigResolver.resolve("SimulationConfig")
      assert(entry.className == "scpu.PipelineSimulationConfig")
      assert(construction.config.pipeline.enablePipeline)
      assert(construction.capability == "run")
    }
  }

  "ConstructionProfile" should "derive stable host and protocol ABIs from run behavior and scope" in {
    val entry = ConfigCatalog.resolve("SimulationConfig", Set("npc"))
    val construction = new SimulationConfig
    val values = ConstructionProfile.values(entry, construction, construction.config).toMap

    assert(values("PROFILE_FORMAT") == "10")
    assert(values("HOST_ABI") == "nemu-construction-v1")
    assert(values("NEMU_PRESET") == "LocalPipelineTrace")
    assert(values("NEMU_BACKEND") == "local")
    assert(values("PROTOCOL_ABI") == "npc-dpi-v1")
    assert(values("ISA_STRING") == "rv64im_zicsr")
  }

  it should "reject a construction behavior that conflicts with the Config scope" in {
    val entry = ConfigCatalog.resolve("SimulationConfig", Set("npc"))
    val fpgaEntry = entry.copy(scope = "fpga")
    val construction = new SimulationConfig
    assertThrows[IllegalArgumentException](ConstructionProfile.values(fpgaEntry, construction, construction.config))
  }

  it should "describe the RV64IMF forwarding comparison configuration exactly" in {
    val entry = ConfigCatalog.resolve("FullIsa64PipelineDualForwardingSimulationConfig", Set("npc"))
    val construction = new FullIsa64PipelineDualForwardingSimulationConfig
    val values = ConstructionProfile.values(entry, construction, construction.config).toMap

    assert(values("ISA_STRING") == "rv64imf_zicsr")
    assert(values("PIPELINE") == "1")
    assert(values("ID_FWD") == "1")
    assert(values("EX_FWD") == "1")
  }

  it should "enable committed-instruction HTML for every local NPC terminal" in {
    val enabled = Seq(
      new StandaloneConfig,
      new SimulationConfig,
      new PipelineSimulationConfig,
      new FullIsa64NoPipelineSimulationConfig,
      new FullIsa64PipelineNoForwardingSimulationConfig,
      new FullIsa64PipelineDualForwardingSimulationConfig,
      new Zcu102Rv32OperatorSimulationConfig,
      new U55cRv32OperatorSimulationConfig,
      new U55cRv64OperatorSimulationConfig
    )
    enabled.foreach { construction =>
      val entry = ConfigCatalog.resolve(construction.getClass.getName, Set("npc"))
      val values = ConstructionProfile.values(entry, construction, construction.config).toMap
      assert(values("NEMU_PERFORMANCE_HTML") == "1")
      assert(values("NEMU_PIPELINE_HTML") == "1")
      assert(values("NEMU_NPC_DIFFTEST") == "1")
      assert(values("NEMU_VCD") == "0")
      assert(values("NEMU_TRACE") == "0")
    }

    val scalar = new FullIsa64NoPipelineSimulationConfig
    val scalarEntry = ConfigCatalog.resolve(scalar.getClass.getName, Set("npc"))
    val scalarValues = ConstructionProfile.values(scalarEntry, scalar, scalar.config).toMap
    assert(scalarValues("PIPELINE") == "0")
    assert(scalarValues("NEMU_PERFORMANCE_HTML") == "1")
    assert(scalarValues("NEMU_PIPELINE_HTML") == "1")
  }
}
