package npc.fpga

import npc.CdeConfigResolver
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import _root_.fpga._
import _root_.fpga.u55c.U55cXilinxIpAttachment
import npc.{ArithmeticRouteOperation, BaseConfig, BranchPredictorConfig, BuiltinCompute, BuiltinComputeConstruction, ComputeBackend, ConfigCatalog, ConstructionConfig, ConstructionProfile, ExternalAxiSocIntegrationConfig, FpgaComputeConstruction, FpgaNpcIntegrationConfig, FpgaToolchainConfig, NemuHostConfig, NpcConfig, NpcCoreComponents, NpcCoreConfigKey, OperatorIpTimingConfig, OperatorRouteTarget, PipelineDualFwdPerformConfig, Rv32IMZicsrConfig, Rv64IMZicsrConfig, SdbDebugConfig, WithArithmeticTimingConfig, WithNpcCoreConfig}
import npc.fpga.u55c.{U55cNpcFpgaConfig, U55cRv64Npc300MHzPerformanceMonitorFpgaConfig, U55cRv64Npc300MHzFpgaConfig, U55cRv64NpcFpgaConfig}
import npc.fpga.zcu102.Zcu102NpcFpgaConfig
import ysyx.{YsyxPlatformParameters, YsyxSimulationConfig, YsyxSocConfig}
import ysyx.fpga.u55c.U55cYsyxSocFpgaConfig
import ysyx.fpga.zcu102.Zcu102YsyxSocFpgaConfig

class FpgaConfigCompositionTest extends AnyFlatSpec {
  private class ExternalAxiModelConstruction
    extends ConstructionConfig(
      new Rv32IMZicsrConfig ++
        new PipelineDualFwdPerformConfig ++
        new ExternalAxiSocIntegrationConfig ++
        new BaseConfig
    ) with BuiltinComputeConstruction

  private class FpgaModelConstruction
    extends ConstructionConfig(
      new SdbDebugConfig ++
        new BranchPredictorConfig ++
        new Rv32IMZicsrConfig ++
        new PipelineDualFwdPerformConfig ++
        new FpgaNpcIntegrationConfig ++
        new BaseConfig
    ) with FpgaComputeConstruction

  private def withConfig[T](value: String)(body: => T): T = {
    val previous = sys.props.get("npc.config")
    System.setProperty("npc.config", value)
    try body
    finally previous.fold(System.clearProperty("npc.config"))(System.setProperty("npc.config", _))
  }

  private def assertXilinxRoutes(config: NpcConfig, width: Int): Unit = {
    config.operators.routes.validate(config.isa)
    ArithmeticRouteOperation.mOperations.foreach { operation =>
      val route = config.operators.routes.route(operation)
      assert(route.target == OperatorRouteTarget.VendorIp)
      assert(route.operandWidth == width)
    }
  }

  private def assertDefaultImplementationReports(toolchain: FpgaToolchainConfig): Unit = {
    val reports = toolchain.reports
    assert(reports.timingMaxPaths == 50)
    assert(reports.timingPathsPerClock == 10)
    assert(reports.reportCongestion)
    assert(reports.reportClockUtilization)
    assert(reports.reportControlSets)
    assert(reports.reportHighFanoutNets)
    assert(reports.reportMethodology)
    assert(reports.reportQorSuggestions)
  }

  "U55cNpcFpgaConfig" should "select the FPGA NPC policy and board clock" in {
    assert(new U55cNpcFpgaConfig().capability == "run")
    assert(new U55cNpcFpgaConfig().nemuConfig == NemuHostConfig.U55cBase)
    assert(new U55cNpcFpgaConfig().fpgaToolchainConfig == FpgaToolchainConfig.U55cBase)
    assert(new U55cNpcFpgaConfig().isInstanceOf[FpgaComputeConstruction])
    implicit val parameters: Parameters =
      new U55cNpcFpgaConfig

    val npcConfig = FpgaConfigParameters.npcCoreConfig
    assert(npcConfig.isa.xlen == 32)
    assert(npcConfig.axi.useExternalMaster)
    assert(npcConfig.debug.enableDispatchControl)
    assert(FpgaConfigParameters.board.contains(FpgaBoard.U55c))
    assert(FpgaConfigParameters.platform.clockMHz == 300)
    assert(FpgaConfigParameters.performanceMonitor == FpgaPerformanceMonitorConfig.Disabled)
    assert(FpgaConfigParameters.runtimeSdb.enabled)
    val toolchain = new U55cNpcFpgaConfig().fpgaToolchainConfig
    assert(toolchain.runtime.notificationMode == "xrt-poll")
    assert(toolchain.flow.vitisXrtMode == "unset")
    assertDefaultImplementationReports(toolchain)
    assertXilinxRoutes(npcConfig, 32)
  }

  "U55cRv64NpcFpgaConfig" should "select 64-bit vendor integer routes" in {
    implicit val parameters: Parameters = new U55cRv64NpcFpgaConfig
    val config = FpgaConfigParameters.npcCoreConfig
    assert(config.isa.xlen == 64)
    assert(config.operators.mulDiv.multiplyTiming.latency == 3)
    assert(config.operators.mulDiv.multiplyTiming.initiationInterval == 1)
    assertXilinxRoutes(config, 64)
  }

  "U55cRv64Npc300MHzFpgaConfig" should "use frequency-specific RV64 and divider timing cuts at 300 MHz" in {
    implicit val parameters: Parameters = new U55cRv64Npc300MHzFpgaConfig
    val config = FpgaConfigParameters.npcCoreConfig
    assert(config.isa.xlen == 64)
    assert(FpgaConfigParameters.platform.clockMHz == 300)
    assert(FpgaConfigParameters.ipAttachment.name == "xilinx-u55c")
    assert(config.operators.mulDiv.multiplyTiming.latency == 6)
    assert(config.operators.mulDiv.multiplyTiming.initiationInterval == 1)
    assert(config.pipeline.integerExecuteStages == 2)
    assert(config.pipeline.serialExecuteStages == 3)
    assert(config.pipeline.registerInitialFetchRequest)
    assert(config.pipeline.separateSerialIntegerAlu)
    assert(!config.pipeline.serialExecuteResultForwarding)
    assert(config.operators.mulDiv.dividerAdapterNonBlocking)
    ArithmeticRouteOperation.mOperations.filter(_.isMultiply).foreach { operation =>
      assert(config.operators.routes.route(operation).latency == 6)
    }
    val profile = ConstructionProfile.values(
      ConfigCatalog.resolve("U55cRv64Npc300MHzFpgaConfig", Set("fpga")),
      new U55cRv64Npc300MHzFpgaConfig,
      config
    ).toMap
    assert(profile("INTEGER_EXECUTE_STAGES") == "2")
    assert(profile("SERIAL_EXECUTE_STAGES") == "3")
    assert(profile("REGISTER_INITIAL_FETCH_REQUEST") == "1")
    assert(profile("SEPARATE_SERIAL_INTEGER_ALU") == "1")
    assert(profile("SERIAL_EXECUTE_RESULT_FORWARDING") == "0")
    assert(profile("NEMU_PERFORMANCE_HTML") == "1")
    assert(profile("NEMU_CACHE_HTML") == "1")
    assert(profile("NEMU_PIPELINE_HTML") == "1")
    assert(profile("NPC_SDB_DEBUG") == "1")
    assert(profile("NPC_TRACE") == "0")
    assert(profile("NPC_FINAL_LOG") == "0")
    assert(profile("PROTOCOL_ABI") == "npc-fpga-runtime-v11")
    assert(profile("FPGA_RUNTIME_SDB") == "1")
    assert(profile("FPGA_RUNTIME_TRACE") == "0")
    assert(FpgaConfigParameters.ipAttachment.manifestValues.toMap.apply("FPGA_DIVIDER_NON_BLOCKING") == "1")
    assertXilinxRoutes(config, 64)
  }

  "U55cRv64Npc300MHzPerformanceMonitorFpgaConfig" should
    "make the v13 batch monitor a 300 MHz SDB-free hardware ABI" in {
    implicit val parameters: Parameters = new U55cRv64Npc300MHzPerformanceMonitorFpgaConfig
    val monitor = FpgaConfigParameters.performanceMonitor
    val runtimeSdb = FpgaConfigParameters.runtimeSdb
    val terminal = new U55cRv64Npc300MHzPerformanceMonitorFpgaConfig
    val profile = ConstructionProfile.values(
      ConfigCatalog.resolve("U55cRv64Npc300MHzPerformanceMonitorFpgaConfig", Set("fpga")),
      terminal,
      FpgaConfigParameters.npcCoreConfig,
      performanceMonitor = monitor.profile,
      runtimeSdbEnabled = runtimeSdb.enabled
    ).toMap

    assert(terminal.capability == "batch")
    assert(terminal.nemuConfig == NemuHostConfig.U55cBase)
    assert(terminal.fpgaToolchainConfig == FpgaToolchainConfig.U55cBase)
    assert(FpgaConfigParameters.platform.clockMHz == 300)
    assert(monitor.enabled)
    assert(!runtimeSdb.enabled)
    assert(monitor.hbmBank == 1)
    assert(monitor.bufferBytes == 8 * 1024 * 1024)
    assert(monitor.maxRecords == 200000)
    assert(monitor.cacheRecords == 2048)
    assert(monitor.traceDataWidth == 256)
    assert(monitor.burstRecords == 16)
    assert(profile("PROTOCOL_ABI") == "npc-fpga-runtime-v13-performance-monitor")
    assert(profile("FPGA_RUNTIME_SDB") == "0")
    assert(profile("FPGA_RUNTIME_TRACE") == "1")
    assert(profile("FPGA_TRACE_FORMAT") == "2")
    assert(profile("FPGA_TRACE_RECORD_BYTES") == "32")
    assert(profile("FPGA_TRACE_DATA_WIDTH") == "256")
    assert(profile("FPGA_TRACE_BURST_RECORDS") == "16")
    assert(profile("NEMU_PERFORMANCE_HTML") == "1")
    assert(profile("NEMU_CACHE_HTML") == "1")
    assert(profile("NEMU_PIPELINE_HTML") == "1")
    assert(profile("NPC_TRACE") == "1")
    assert(profile("NPC_FINAL_LOG") == "1")
    assert(profile("NPC_SDB_DEBUG") == "0")
  }

  "Zcu102NpcFpgaConfig" should "use the PS UIO notification path with the same strict routes" in {
    implicit val parameters: Parameters = new Zcu102NpcFpgaConfig
    val toolchain = new Zcu102NpcFpgaConfig().fpgaToolchainConfig
    assert(toolchain.runtime.notificationMode == "ps-uio-irq")
    assert(toolchain.flow.vitisXrtMode == "inherit")
    assertDefaultImplementationReports(toolchain)
    assertXilinxRoutes(FpgaConfigParameters.npcCoreConfig, 32)
  }

  it should "allow a left-side complete NPC Config to override the default NPC" in {
    implicit val parameters: Parameters =
      new WithNpcCoreConfig(new ExternalAxiModelConstruction().config) ++
        new U55cNpcFpgaConfig

    assert(FpgaConfigParameters.npcCoreConfig == new ExternalAxiModelConstruction().config)
    assert(!FpgaConfigParameters.npcCoreConfig.debug.enableDispatchControl)
    assert(FpgaConfigParameters.platform.clockMHz == 300)
  }

  "U55cYsyxSocFpgaConfig" should "replace YsyxElaborateConfig's default NPC and infer FPGA from its board" in {
    implicit val parameters: Parameters = new U55cYsyxSocFpgaConfig

    val boardConfig = FpgaConfigParameters.npcCoreConfig
    val baseConfig = (new FpgaModelConstruction).config
    assert(boardConfig.copy(operators = boardConfig.operators.copy(routes = baseConfig.operators.routes)) == baseConfig)
    assert(boardConfig.debug.enableDispatchControl)
    assert(boardConfig.operators.routes.route(ArithmeticRouteOperation.Mul).target == OperatorRouteTarget.VendorIp)
    assert(YsyxPlatformParameters.isFpga)
    assert(!YsyxPlatformParameters.isDpiSimulation)
  }

  "YsyxSimulationConfig" should "select the local Verilator host when no FPGA board is present" in {
    implicit val parameters: Parameters = new YsyxSimulationConfig

    assert(!YsyxPlatformParameters.isFpga)
    assert(YsyxPlatformParameters.isDpiSimulation)
    assert(new YsyxSimulationConfig().nemuConfig == NemuHostConfig.LocalPipelineTrace)
    assert(new YsyxSimulationConfig().isInstanceOf[BuiltinComputeConstruction])
    assert(new YsyxSimulationConfig().nemuConfig.pipelineHtml)
    assert(new U55cNpcFpgaConfig().nemuConfig.pipelineHtml)
    assert(new Zcu102NpcFpgaConfig().nemuConfig.pipelineHtml)
  }

  it should "wrap the reusable SoC core without redefining its hardware graph" in {
    val terminal = new YsyxSimulationConfig
    val core = new YsyxSocConfig with BuiltinComputeConstruction

    assert(terminal(NpcCoreConfigKey) == core(NpcCoreConfigKey))
  }

  it should "require an IP terminal trait before a reusable SoC core is elaborated" in {
    val unmounted = new YsyxSocConfig

    assertThrows[IllegalArgumentException](unmounted(NpcCoreConfigKey))
  }

  it should "allow a later complete NPC Config to replace the SoC FPGA default" in {
    implicit val parameters: Parameters =
      new WithNpcCoreConfig(new ExternalAxiModelConstruction().config) ++
        new U55cYsyxSocFpgaConfig

    assert(FpgaConfigParameters.npcCoreConfig == new ExternalAxiModelConstruction().config)
    assert(!FpgaConfigParameters.npcCoreConfig.debug.enableDispatchControl)
    assert(YsyxPlatformParameters.isFpga)
  }

  it should "resolve registered terminal Configs and let the CDE board win" in {
    withConfig("npc.fpga.u55c.U55cNpcFpgaConfig") {
      val (entry, construction) = CdeConfigResolver.resolve("Zcu102NpcFpgaConfig", Set("fpga"))
      implicit val parameters: Parameters = construction

      assert(entry.board.contains("u55c"))
      assert(FpgaConfigParameters.board.contains(FpgaBoard.U55c))
      assert(FpgaConfigParameters.platform.board == FpgaBoard.U55c)
      assert(FpgaConfigParameters.platform.clockMHz == 300)
    }
  }

  "FpgaToolchainConfig" should "support grouped copy overrides without changing other groups" in {
    val base = FpgaToolchainConfig.U55cBase
    val custom = base.copy(
      flow = base.flow.copy(
        implementationStrategy = "Performance_Explore",
        implementationParallelJobs = 12
      ),
      reports = base.reports.copy(reportQorSuggestions = false)
    )
    val profile = custom.profileValues.toMap

    assert(custom.device == base.device)
    assert(custom.runtime == base.runtime)
    assert(custom.flow.implementationParallelJobs == 12)
    assert(custom.flow.implementationStrategy == "Performance_Explore")
    assert(!custom.reports.reportQorSuggestions)
    assert(profile("FPGA_VIVADO_IMPL_JOBS") == "12")
    assert(profile("FPGA_VIVADO_IMPL_STRATEGY") == "Performance_Explore")
    assert(profile("FPGA_REPORT_QOR_SUGGESTIONS") == "0")
  }

  it should "reject incompatible XRT and notification modes" in {
    val zcu102 = FpgaToolchainConfig.Zcu102Base
    assertThrows[IllegalArgumentException](zcu102.copy(
      flow = zcu102.flow.copy(vitisXrtMode = "unset")
    ))
    assertThrows[IllegalArgumentException](zcu102.copy(
      runtime = zcu102.runtime.copy(notificationMode = "xrt-poll")
    ))
  }

  it should "be attached with the matching NEMU Base by every public FPGA terminal" in {
    val u55c = Seq(
      new U55cNpcFpgaConfig,
      new U55cRv64NpcFpgaConfig,
      new U55cRv64Npc300MHzFpgaConfig,
      new U55cYsyxSocFpgaConfig
    )
    u55c.foreach { terminal =>
      assert(terminal.nemuConfig == NemuHostConfig.U55cBase)
      assert(terminal.fpgaToolchainConfig == FpgaToolchainConfig.U55cBase)
    }

    val zcu102 = Seq(new Zcu102NpcFpgaConfig, new Zcu102YsyxSocFpgaConfig)
    zcu102.foreach { terminal =>
      assert(terminal.nemuConfig == NemuHostConfig.Zcu102Base)
      assert(terminal.fpgaToolchainConfig == FpgaToolchainConfig.Zcu102Base)
    }
  }

  it should "mount the selected IP attachment into NPC and SoC without a board switch" in {
    {
      implicit val parameters: Parameters = new U55cNpcFpgaConfig
      val attachment = FpgaConfigParameters.ipAttachment
      val neutralComponents = NpcCoreComponents.externalArithmetic(attachment.name, attachment.arithmeticIp)
      assert(attachment.name == "xilinx-u55c")
      assert(neutralComponents.name == attachment.name)
      assert(neutralComponents.arithmeticIp == attachment.arithmeticIp)
      assert(neutralComponents.exposesDispatchControl(FpgaConfigParameters.npcCoreConfig))
      assert(FpgaCoreComponents.forAttachment(attachment).arithmeticIp.name == "xilinx-u55c")
      assert(attachment.manifestValues.toMap.apply("FPGA_DIV_IP_CYCLES") == "34")
      assert(attachment.manifestValues.toMap.apply("FPGA_DIVIDER_NON_BLOCKING") == "0")
    }
    {
      implicit val parameters: Parameters = new U55cRv64Npc300MHzFpgaConfig
      assert(FpgaConfigParameters.ipAttachment.manifestValues.toMap.apply("FPGA_DIVIDER_NON_BLOCKING") == "1")
    }
    {
      implicit val parameters: Parameters = new Zcu102NpcFpgaConfig
      val attachment = FpgaConfigParameters.ipAttachment
      assert(attachment.name == "xilinx-zcu102")
      assert(FpgaCoreComponents.forAttachment(attachment).arithmeticIp.name == "xilinx-zcu102")
    }
  }

  it should "reject Xilinx IP timing contracts that cannot generate a matching pipeline" in {
    val defaults = OperatorIpTimingConfig.Default
    assertThrows[IllegalArgumentException](U55cXilinxIpAttachment(defaults.copy(
      multiply = defaults.multiply.copy(initiationInterval = 2)
    )))
    assertThrows[IllegalArgumentException](U55cXilinxIpAttachment(defaults.copy(
      divide = defaults.divide.copy(latency = 36)
    )))
  }

  it should "derive NEMU functional timing from the selected FPGA attachment" in {
    val defaults = OperatorIpTimingConfig.Default
    val attachment = U55cXilinxIpAttachment(defaults.copy(
      outputFifoDepth = 8,
      multiply = defaults.multiply.copy(latency = 5)
    ))
    val model = (
      new WithArithmeticTimingConfig(attachment.timing) ++
        BuiltinCompute.computeUnitConfig ++
        new Rv64IMZicsrConfig
    ).build

    assert(model.operators.mulDiv.implementation.backend == ComputeBackend.Builtin)
    assert(model.operators.mulDiv.multiplyTiming.latency == 5)
  }
}
