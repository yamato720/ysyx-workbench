package npc

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import org.scalatest.flatspec.AnyFlatSpec
import scala.jdk.CollectionConverters._

class ConfigCatalogTest extends AnyFlatSpec {
  private def deleteTree(root: Path): Unit = {
    val paths = Files.walk(root)
    try paths.iterator.asScala.toVector.reverse.foreach(Files.deleteIfExists)
    finally paths.close()
  }

  private def withConfig[T](value: String)(body: => T): T = {
    val previous = sys.props.get("npc.config")
    System.setProperty("npc.config", value)
    try body
    finally previous.fold(System.clearProperty("npc.config"))(System.setProperty("npc.config", _))
  }

  "ConfigCatalog" should "resolve both a short name and its canonical FQCN" in {
    val byShortName = ConfigCatalog.resolve("SimulationConfig", Set("npc"))
    val byClassName = ConfigCatalog.resolve("npc.SimulationConfig", Set("npc"))
    assert(byShortName == byClassName)
    assert(byShortName.className == "npc.SimulationConfig")
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
    assert(names.contains("CacheSimulationConfig"))
    assert(names.contains("HbmJitterL2CacheSimulationConfig"))
    assert(names.contains("HbmJitterCacheVcdSimulationConfig"))
    assert(names.contains("PipelinedTwoCycleWideL2SimulationConfig"))
    assert(names.contains("PipelinedTwoCycleWideL2NoCompletionForwardingSimulationConfig"))
    assert(names.contains("YsyxSimulationConfig"))
    assert(names.contains("CacheYsyxSimulationConfig"))
    assert(names.contains("U55cYsyxSocFpgaConfig"))
    assert(names.contains("Zcu102NpcFpgaConfig"))
    assert(names.contains("FullIsa64PipelineDualForwardingSimulationConfig"))
    assert(names.contains("Zcu102Rv32OperatorSimulationConfig"))
    assert(names.contains("U55cRv32OperatorSimulationConfig"))
    assert(names.contains("U55cRv64OperatorSimulationConfig"))
    assert(names.contains("U55cRv64NpcFpgaConfig"))
    assert(names.contains("U55cRv64Npc300MHzFpgaConfig"))
    assert(names.contains("U55cRv64Npc300MHzPerformanceMonitorFpgaConfig"))
    assert(names.contains("U55cRv64CacheNpc300MHzPerformanceMonitorFpgaConfig"))
    assert(names.contains("U55cRv64CacheNpc150MHzPerformanceMonitorFpgaConfig"))
    assert(names.contains("U55cSpmv32PcFp32X8192UramResourceProbeConfig"))
    assert(names.contains("SpmvOneHbmCsr5MulSimulationConfig"))
    assert(names.contains("SpmvOneHbmCsr5MulCachedXSimulationConfig"))
    assert(names.contains("SpmvOneHbmCsr5MulPerformanceMonitorSimulationConfig"))
    assert(names.contains("U55cCacheNpcFpgaConfig"))
    assert(names.contains("U55cRv64CacheNpc300MHzFpgaConfig"))
    assert(names.contains("U55cCacheYsyxSocFpgaConfig"))
    Seq(100, 125, 150, 200, 250, 300).foreach { frequency =>
      assert(names.contains(s"U55cRv64Npc${frequency}MHzPerformanceMonitorFpgaConfig"))
    }
    assert(!names.contains("U55cRv64Npc300MHzDebugFpgaConfig"))
    assert(!names.contains("U55cFullIsa64NpcFpgaConfig"))
    assert(!names.contains("FpgaConfig"))
    assert(!names.contains("ExternalAxiConfig"))
    assert(!names.contains("YsyxElaborateConfig"))
    assert(!names.contains("PipelineCheckConfig"))
    assert(generated.exists(_.shortName == "PipelineSimulationConfig"))
    assert(generated.exists(entry =>
      entry.shortName == "U55cSpmv32PcFp32X8192UramResourceProbeConfig" &&
        entry.scope == "fpga" && entry.board.contains("u55c") && entry.target == "SPMV"))
    assert(generated.exists(entry =>
      entry.shortName == "SpmvOneHbmCsr5MulSimulationConfig" && entry.scope == "spmv" &&
        entry.board.isEmpty && entry.target == "SPMV"))
    assert(generated.exists(entry =>
      entry.shortName == "SpmvOneHbmCsr5MulCachedXSimulationConfig" && entry.scope == "spmv" &&
        entry.board.isEmpty && entry.target == "SPMV"))
    assert(generated.exists(entry =>
      entry.shortName == "SpmvOneHbmCsr5MulPerformanceMonitorSimulationConfig" &&
        entry.scope == "spmv" && entry.board.isEmpty && entry.target == "SPMV"))
  }

  it should "keep directly mountable terminal traits in their own layer" in {
    val root = ConfigCatalogGenerator.locateNpcRoot(Paths.get(".").toAbsolutePath.normalize).get
    val common = root.resolve("chisel/configs/common")

    assert(Files.isRegularFile(common.resolve("TerminalTraits.scala")))
    assert(!Files.exists(common.resolve("base/TerminalTraits.scala")))
    assert(!Files.exists(common.resolve("core/TerminalTraits.scala")))
    assert(!Files.exists(common.resolve("terminal/TerminalTraits.scala")))
  }

  it should "ignore Config-shaped text in comments and string literals" in {
    val source =
      """package npc
        |/** class CommentConfig extends ConstructionConfig */
        |val example = "class StringConfig extends ConstructionConfig"
        |class RealConfig extends ConstructionConfig
        |""".stripMargin
    val code = ConfigCatalogGenerator.codeOnly(source)

    assert(!code.contains("CommentConfig"))
    assert(!code.contains("StringConfig"))
    assert(code.contains("RealConfig"))
  }

  it should "enforce the root terminal layout while allowing recipe overrides" in {
    val directory = Files.createTempDirectory("config-layout-test-")
    try {
      Files.writeString(directory.resolve("Configs.scala"),
        "package npc\nclass GoodConfig extends ConstructionConfig with LocalNpcTerminal\n",
        StandardCharsets.UTF_8)
      val core = Files.createDirectories(directory.resolve("core"))
      val misplaced = core.resolve("Misplaced.scala")
      Files.writeString(misplaced,
        "package npc\nclass MisplacedConfig extends ConstructionConfig with LocalNpcTerminal\n",
        StandardCharsets.UTF_8)

      val misplacedError = intercept[IllegalArgumentException] {
        ConfigCatalogGenerator.validateTerminalLayout(directory)
      }
      assert(misplacedError.getMessage.contains("terminal 层 trait 只能挂载"))

      Files.delete(misplaced)
      Files.writeString(directory.resolve("Configs.scala"),
        "package npc\nclass UnmarkedConfig extends ConstructionConfig\n",
        StandardCharsets.UTF_8)
      val unmarkedError = intercept[IllegalArgumentException] {
        ConfigCatalogGenerator.validateTerminalLayout(directory)
      }
      assert(unmarkedError.getMessage.contains("只能包含挂载 terminal 层 trait 的 Config"))

      Files.writeString(directory.resolve("Configs.scala"),
        "package npc\nclass AmbiguousConfig extends ConstructionConfig " +
          "with LocalNpcTerminal with U55cNpcTerminal\n",
        StandardCharsets.UTF_8)
      val ambiguousError = intercept[IllegalArgumentException] {
        ConfigCatalogGenerator.validateTerminalLayout(directory)
      }
      assert(ambiguousError.getMessage.contains("挂载了多个 terminal 层 trait"))

      Files.writeString(directory.resolve("Configs.scala"),
        "package npc\nclass ManualRecipeConfig extends ConstructionConfig with LocalNpcTerminal {\n" +
          "  override protected val configuredNemu = NemuHostConfig.LocalBase\n}\n",
        StandardCharsets.UTF_8)
      assert(ConfigCatalogGenerator.validateTerminalLayout(directory) ==
        directory.resolve("Configs.scala").toAbsolutePath.normalize)

      Files.writeString(directory.resolve("Configs.scala"),
        "package npc\nclass LayerViolationConfig extends ConstructionConfig " +
          "with LocalNpcTerminal with NemuSimulationConstruction\n",
        StandardCharsets.UTF_8)
      val layeringError = intercept[IllegalArgumentException] {
        ConfigCatalogGenerator.validateTerminalLayout(directory)
      }
      assert(layeringError.getMessage.contains("不能混入 base trait"))
    } finally deleteTree(directory)
  }

  "ConfigResolver" should "instantiate only registered complete NPC configurations" in {
    withConfig("PipelineSimulationConfig") {
      val (entry, construction) = ConfigResolver.resolve("SimulationConfig")
      assert(entry.className == "npc.PipelineSimulationConfig")
      assert(construction.config.pipeline.enablePipeline)
      assert(construction.capability == "run")
    }
  }

  "ConstructionProfile" should "derive stable host and protocol ABIs from run behavior and scope" in {
    val entry = ConfigCatalog.resolve("SimulationConfig", Set("npc"))
    val construction = new SimulationConfig
    val values = ConstructionProfile.values(entry, construction, construction.config).toMap

    assert(values("PROFILE_FORMAT") == "22")
    assert(values("HOST_ABI") == "nemu-construction-v1")
    assert(values("NEMU_PRESET") == "LocalPipelineTrace")
    assert(values("NEMU_BACKEND") == "local")
    assert(values("NEMU_MEMORY_STATISTICS_MODE") == "Split")
    assert(values("PROTOCOL_ABI") == "npc-dpi-v1")
    assert(values("ISA_STRING") == "rv64im_zicsr")
    assert(values("INTEGER_EXECUTE_STAGES") == "1")
    assert(values("SERIAL_EXECUTE_STAGES") == "1")
    assert(values("REGISTER_INITIAL_FETCH_REQUEST") == "0")
    assert(values("SEPARATE_SERIAL_INTEGER_ALU") == "0")
    assert(values("SERIAL_EXECUTE_RESULT_FORWARDING") == "1")

    val serviceOnlyHost = new HostConstruction {
      override protected val configuredNemu: NemuHostConfig =
        NemuHostConfig.LocalBase.copy(memoryStatisticsMode = MemoryStatisticsMode.ServiceOnly)
    }
    val serviceOnlyValues = ConstructionProfile.values(entry, serviceOnlyHost, construction.config).toMap
    assert(serviceOnlyValues("NEMU_MEMORY_STATISTICS_MODE") == "ServiceOnly")
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
    assert(values("INTEGER_EXECUTE_STAGES") == "1")
    assert(values("SERIAL_EXECUTE_STAGES") == "1")
    assert(values("REGISTER_INITIAL_FETCH_REQUEST") == "0")
    assert(values("SEPARATE_SERIAL_INTEGER_ALU") == "0")
    assert(values("SERIAL_EXECUTE_RESULT_FORWARDING") == "1")
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
      assert(values("NEMU_CACHE_HTML") == "1")
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
    assert(scalarValues("NEMU_CACHE_HTML") == "1")
    assert(scalarValues("NEMU_PIPELINE_HTML") == "1")
  }

  it should "make VCD a separately frozen local Verilator ABI" in {
    val construction = new HbmJitterCacheVcdSimulationConfig
    val entry = ConfigCatalog.resolve(construction.getClass.getName, Set("npc"))
    val values = ConstructionProfile.values(entry, construction, construction.config).toMap

    assert(values("NEMU_PRESET") == "LocalVcdTrace")
    assert(values("NEMU_BACKEND") == "local")
    assert(values("NEMU_TRACE") == "1")
    assert(values("NEMU_VCD") == "1")
    assert(values("NEMU_PERFORMANCE_HTML") == "1")
    assert(values("NEMU_CACHE_HTML") == "1")
    assert(values("NEMU_PIPELINE_HTML") == "1")
    assert(values("NEMU_NPC_DIFFTEST") == "1")
  }

  it should "describe the local wide-HBM L2 timing endpoint" in {
    val construction = new HbmJitterL2CacheSimulationConfig
    val entry = ConfigCatalog.resolve(construction.getClass.getName, Set("npc"))
    val values = ConstructionProfile.values(entry, construction, construction.config).toMap

    assert(values("AXI_MEMORY_DATA_WIDTH") == "512")
    assert(values("AXI_EXTERNAL") == "0")
    assert(values("ICACHE_LINE_BYTES") == "64")
    assert(values("DCACHE_LINE_BYTES") == "64")
    assert(values("L2CACHE_ENABLED") == "1")
    assert(values("L2CACHE_CAPACITY_BYTES") == (256 * 1024).toString)
    assert(values("DPI_MEMORY_READ_RESPONSE_MIN_CYCLES") == "73")
    assert(values("DPI_MEMORY_READ_RESPONSE_MAX_CYCLES") == "81")
  }

  it should "freeze the two-cycle cache mode and local FIFO depths in the profile" in {
    val construction = new PipelinedTwoCycleWideL2SimulationConfig
    val entry = ConfigCatalog.resolve(construction.getClass.getName, Set("npc"))
    val values = ConstructionProfile.values(entry, construction, construction.config).toMap

    assert(values("CACHE_ACCESS_MODE") == "pipelined-two-cycle")
    assert(values("CACHE_REQUEST_QUEUE_DEPTH") == "4")
    assert(values("CACHE_RESPONSE_QUEUE_DEPTH") == "4")
    assert(values("CACHE_FETCH_QUEUE_DEPTH") == "4")
    assert(values("CACHE_MEMORY_QUEUE_DEPTH") == "4")
    assert(values("INSTRUCTION_BUFFER_ENTRIES") == "8")
    assert(values("OUTSTANDING_COMPLETION_FWD") == "1")

    val disabled = new PipelinedTwoCycleWideL2NoCompletionForwardingSimulationConfig
    val disabledEntry = ConfigCatalog.resolve(disabled.getClass.getName, Set("npc"))
    val disabledValues = ConstructionProfile.values(disabledEntry, disabled, disabled.config).toMap
    assert(disabledValues("OUTSTANDING_COMPLETION_FWD") == "0")
  }
}
