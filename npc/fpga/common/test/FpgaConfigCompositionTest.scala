package npc.fpga

import npc.CdeConfigResolver
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import npc.ExternalAxiConfig
import npc.{ArithmeticRouteOperation, ComputeBackend, ConstructionConfig, FloatingCheckConfig, FpgaIpTerminal, FpgaToolchainConfig, NemuHostConfig, NemuSimulationIpTerminal, NpcConfig, NpcCoreConfigKey, OperatorIpTimingConfig, OperatorRouteTarget, Rv64IMFZicsrConfig, WithNpcCoreConfig}
import npc.fpga.u55c.{U55cNpcFpgaConfig, U55cRv64Npc300MHzFpgaConfig, U55cRv64NpcFpgaConfig, U55cXilinxIpAttachment, U55cYsyxSocFpgaConfig}
import npc.fpga.zcu102.{Zcu102NpcFpgaConfig, Zcu102YsyxSocFpgaConfig}
import ysyx.{YsyxPlatformParameters, YsyxSimulationConfig, YsyxSocConfig}

class FpgaConfigCompositionTest extends AnyFlatSpec {
  private class ExternalAxiModelConstruction
    extends ConstructionConfig(new ExternalAxiConfig) with NemuSimulationIpTerminal

  private def withConfig[T](value: String)(body: => T): T = {
    val previous = sys.props.get("npc.config")
    System.setProperty("npc.config", value)
    try body
    finally previous.fold(System.clearProperty("npc.config"))(System.setProperty("npc.config", _))
  }

  private def assertXilinxRoutes(config: NpcConfig, width: Int): Unit = {
    config.operators.routes.validate(config.isa)
    assert(!config.isa.F)
    assert(!config.isa.D)
    ArithmeticRouteOperation.mOperations.foreach { operation =>
      val route = config.operators.routes.route(operation)
      assert(route.target == OperatorRouteTarget.VendorIp)
      assert(route.operandWidth == width)
    }
    ArithmeticRouteOperation.fOperations.foreach { operation =>
      assert(!config.operators.routes.routes.contains(operation))
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
    assert(new U55cNpcFpgaConfig().isInstanceOf[FpgaIpTerminal])
    implicit val parameters: Parameters =
      new U55cNpcFpgaConfig

    val npcConfig = FpgaConfigParameters.npcCoreConfig
    assert(npcConfig.isa.xlen == 32)
    assert(npcConfig.axi.useExternalMaster)
    assert(npcConfig.debug.enableDispatchControl)
    assert(FpgaConfigParameters.board.contains(FpgaBoard.U55c))
    assert(FpgaConfigParameters.platform.clockMHz == 125)
    val toolchain = new U55cNpcFpgaConfig().fpgaToolchainConfig
    assert(toolchain.runtime.notificationMode == "xrt-poll")
    assert(toolchain.flow.vitisXrtMode == "unset")
    assertDefaultImplementationReports(toolchain)
    assertXilinxRoutes(npcConfig, 32)
  }

  "U55cRv64NpcFpgaConfig" should "select 64-bit vendor integer routes without F" in {
    implicit val parameters: Parameters = new U55cRv64NpcFpgaConfig
    val config = FpgaConfigParameters.npcCoreConfig
    assert(config.isa.xlen == 64)
    assert(!config.isa.F)
    assert(!config.isa.D)
    assert(config.operators.mulDiv.multiplyTiming.latency == 3)
    assert(config.operators.mulDiv.multiplyTiming.initiationInterval == 1)
    assertXilinxRoutes(config, 64)
  }

  "U55cRv64Npc300MHzFpgaConfig" should "use a deeper RV64 multiplier pipeline at 300 MHz" in {
    implicit val parameters: Parameters = new U55cRv64Npc300MHzFpgaConfig
    val config = FpgaConfigParameters.npcCoreConfig
    assert(config.isa.xlen == 64)
    assert(FpgaConfigParameters.platform.clockMHz == 300)
    assert(FpgaConfigParameters.ipAttachment.name == "xilinx-u55c")
    assert(config.operators.mulDiv.multiplyTiming.latency == 5)
    assert(config.operators.mulDiv.multiplyTiming.initiationInterval == 1)
    ArithmeticRouteOperation.mOperations.filter(_.isMultiply).foreach { operation =>
      assert(config.operators.routes.route(operation).latency == 5)
    }
    assertXilinxRoutes(config, 64)
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
    assert(FpgaConfigParameters.platform.clockMHz == 125)
  }

  "U55cYsyxSocFpgaConfig" should "replace YsyxElaborateConfig's default NPC and infer FPGA from its board" in {
    implicit val parameters: Parameters = new U55cYsyxSocFpgaConfig

    val boardConfig = FpgaConfigParameters.npcCoreConfig
    val baseConfig = (new npc.FpgaConfig with FpgaIpTerminal).config
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
    assert(new YsyxSimulationConfig().isInstanceOf[NemuSimulationIpTerminal])
    assert(new YsyxSimulationConfig().nemuConfig.pipelineHtml)
    assert(!new U55cNpcFpgaConfig().nemuConfig.pipelineHtml)
    assert(!new Zcu102NpcFpgaConfig().nemuConfig.pipelineHtml)
  }

  it should "wrap the reusable SoC core without redefining its hardware graph" in {
    val terminal = new YsyxSimulationConfig
    val core = new YsyxSocConfig with NemuSimulationIpTerminal

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
      assert(FpgaConfigParameters.platform.clockMHz == 125)
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
      assert(attachment.name == "xilinx-u55c")
      assert(FpgaCoreComponents.forAttachment(attachment).arithmeticIp.name == "xilinx-u55c")
      assert(attachment.manifestValues.toMap.apply("FPGA_DIV_IP_CYCLES") == "34")
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
      multiply = defaults.multiply.copy(latency = 5),
      floatingDivide = defaults.floatingDivide.copy(latency = 31)
    ))
    val model = (NemuSimulationIpTerminal.from(attachment).computeUnitConfig ++ new Rv64IMFZicsrConfig).build

    assert(model.operators.mulDiv.implementation.backend == ComputeBackend.Builtin)
    assert(model.operators.floating.implementation.backend == ComputeBackend.Builtin)
    assert(model.operators.mulDiv.multiplyTiming.latency == 5)
    assert(model.operators.floating.divideTiming.latency == 31)
    assert(model.operators.floating.divideTiming.responseFifoDepth == 8)
  }

  it should "retain F only in local checks" in {
    val localFloating = new FloatingCheckConfig().config
    assert(localFloating.isa.F)
  }
}
