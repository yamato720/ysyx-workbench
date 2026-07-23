package scpu.fpga

import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import scpu.ExternalAxiConfig
import scpu.{ArithmeticRouteOperation, FpgaToolchainConfig, NemuHostConfig, NpcConfig, OperatorFallbackReason, OperatorRouteTarget}
import scpu.fpga.u55c.{U55cFullIsa64Npc250MHzFpgaConfig, U55cFullIsa64NpcFpgaConfig, U55cNpcFpgaConfig, U55cYsyxSocFpgaConfig}
import scpu.fpga.zcu102.{Zcu102NpcFpgaConfig, Zcu102YsyxSocFpgaConfig}
import ysyx.{YsyxPlatformParameters, YsyxSimulationConfig}

class FpgaConfigCompositionTest extends AnyFlatSpec {
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
      assert(route.fallbackReason == OperatorFallbackReason.None)
    }
    ArithmeticRouteOperation.fOperations.foreach { operation =>
      val route = config.operators.routes.route(operation)
      assert(route.operandWidth == width)
      if (operation.isDirectFloating) {
        assert(route.target == OperatorRouteTarget.DirectLogic)
        assert(route.fallbackReason == OperatorFallbackReason.None)
      } else {
        assert(route.target == OperatorRouteTarget.HostFallback)
        assert(route.fallbackReason == OperatorFallbackReason.FpoRiscvIncompatible)
      }
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

  "U55cFullIsa64NpcFpgaConfig" should "select 64-bit vendor integer routes" in {
    implicit val parameters: Parameters = new U55cFullIsa64NpcFpgaConfig
    val config = FpgaConfigParameters.npcCoreConfig
    assert(config.isa.xlen == 64)
    assertXilinxRoutes(config, 64)
  }

  "U55cFullIsa64Npc250MHzFpgaConfig" should "keep the RV64 core width while overriding only the board clock" in {
    implicit val parameters: Parameters = new U55cFullIsa64Npc250MHzFpgaConfig
    val config = FpgaConfigParameters.npcCoreConfig
    assert(config.isa.xlen == 64)
    assert(FpgaConfigParameters.platform.clockMHz == 250)
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
      new ExternalAxiConfig ++
        new U55cNpcFpgaConfig

    assert(FpgaConfigParameters.npcCoreConfig == new ExternalAxiConfig().config)
    assert(!FpgaConfigParameters.npcCoreConfig.debug.enableDispatchControl)
    assert(FpgaConfigParameters.platform.clockMHz == 125)
  }

  "U55cYsyxSocFpgaConfig" should "replace YsyxElaborateConfig's default NPC and infer FPGA from its board" in {
    implicit val parameters: Parameters = new U55cYsyxSocFpgaConfig

    val boardConfig = FpgaConfigParameters.npcCoreConfig
    val baseConfig = new scpu.FpgaConfig().config
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
    assert(new YsyxSimulationConfig().nemuConfig.pipelineHtml)
    assert(!new U55cNpcFpgaConfig().nemuConfig.pipelineHtml)
    assert(!new Zcu102NpcFpgaConfig().nemuConfig.pipelineHtml)
  }

  it should "allow a later complete NPC Config to replace the SoC FPGA default" in {
    implicit val parameters: Parameters =
      new ExternalAxiConfig ++
        new U55cYsyxSocFpgaConfig

    assert(FpgaConfigParameters.npcCoreConfig == new ExternalAxiConfig().config)
    assert(!FpgaConfigParameters.npcCoreConfig.debug.enableDispatchControl)
    assert(YsyxPlatformParameters.isFpga)
  }

  it should "resolve registered terminal Configs and let the CDE board win" in {
    withConfig("scpu.fpga.u55c.U55cNpcFpgaConfig") {
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
      new U55cFullIsa64NpcFpgaConfig,
      new U55cFullIsa64Npc250MHzFpgaConfig,
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
}
