package npc

import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec

class NpcConfigCompositionTest extends AnyFlatSpec {
  "Construction boundary" should "expose only NEMU-running terminal configurations" in {
    assert(new StandaloneConfig().capability == "run")
    assert(new PipelineCheckConfig().capability == "check-only")
    assert(new SimulationConfig().capability == "run")
    assert(new SimulationConfig().nemuConfig == NemuHostConfig.LocalPipelineTrace)
    val fpgaCore: Any = new FpgaConfig with FpgaIpTerminal
    assert(!fpgaCore.isInstanceOf[MakeTerminal])
    assert(!new ExternalAxiConfig().isInstanceOf[MakeTerminal])
  }

  "ConstructionConfig" should "directly provide its completed core through the CDE key" in {
    val construction = new FpgaConfig with FpgaIpTerminal
    implicit val parameters: Parameters = construction

    assert(parameters(NpcCoreConfigKey) == construction.config)
  }

  "ConfigFragment ++" should "apply the right fragment first and keep left overrides" in {
    val config = (
      new WithExternalAxiConfig(idWidth = 8) ++
        new Rv32IMZicsrConfig ++
        new BaseConfig
    ).build

    assert(config.isa.xlen == 32)
    assert(config.isa.M)
    assert(config.axi.useExternalMaster)
    assert(config.axi.dataWidth == 32)
    assert(config.axi.idWidth == 8)
  }

  "FpgaConfig" should "compose explicit architecture, performance, memory, and compute fragments" in {
    val config = (new FpgaConfig with FpgaIpTerminal).config

    assert(config.isa.xlen == 32)
    assert(config.isa.M)
    assert(!config.isa.F)
    assert(!config.isa.D)
    assert(config.pipeline.enablePipeline)
    assert(config.pipeline.forwarding.enableIdForwarding)
    assert(config.pipeline.forwarding.enableExecuteForwarding)
    assert(config.operators.mulDiv.implementation.backend == ComputeBackend.FPGA)
    assert(config.memory.mainMemorySize == 0x08000000L)
    assert(config.debug.enableTopDebugIo)
    assert(config.debug.enableDispatchControl)
    assert(config.axi.useExternalMaster)
    assert(config.axi.dataWidth == 32)
    assert(config.operators.routes.route(ArithmeticRouteOperation.Mul).target == OperatorRouteTarget.Model)
    assert(!config.operators.routes.routes.contains(ArithmeticRouteOperation.Fadd))
    config.operators.routes.validate(config.isa)
  }

  "Local floating-point Config" should "retain its model routes" in {
    val localFloating = new FloatingCheckConfig().config
    assert(localFloating.isa.F)
    assert(localFloating.operators.routes.route(ArithmeticRouteOperation.Fadd).target ==
      OperatorRouteTarget.Model)
  }

  "Operator route defaults" should "cover every enabled M/F operation and reject an unselected check route" in {
    val model = new U55cRv64OperatorSimulationConfig().config
    assert(model.isa.xlen == 64)
    assert(model.operators.routes.profileValues(model.isa).size ==
      ArithmeticRouteOperation.mOperations.size + ArithmeticRouteOperation.fOperations.size)

    assertThrows[IllegalArgumentException]((
      new WithOperatorRoutesConfig(OperatorRouteConfig(Map(
        ArithmeticRouteOperation.Mul -> OperatorRoute(
          OperatorRouteTarget.Unselected, "unselected", 64, 1, 1)
      ))) ++
        new Rv64IMZicsrConfig
    ).build)
  }

  it should "remain composable when an integration adds a later fragment" in {
    val config = (
      new ScalarPerformConfig ++
        (new FpgaConfig with FpgaIpTerminal)
    ).build

    assert(config.isa.xlen == 32)
    assert(config.isa.M)
    assert(!config.pipeline.enablePipeline)
    assert(!config.pipeline.forwarding.enableIdForwarding)
    assert(!config.pipeline.forwarding.enableExecuteForwarding)
    assert(config.debug.enableDispatchControl)
    assert(config.axi.useExternalMaster)
    assert(config.axi.dataWidth == 32)
  }

  "Compute fragments" should "apply one IP implementation to both arithmetic domains" in {
    val ip = IpComputeConfig(moduleName = "test_ip", outputFifoDepth = 8)
    val config = (
      new WithGenericIpComputeConfig(ip) ++
        new WithDefaultArithmeticTimingConfig ++
        new BaseConfig
    ).build

    assert(config.operators.mulDiv.implementation.backend == ComputeBackend.IP)
    assert(config.operators.floating.implementation.backend == ComputeBackend.IP)
    assert(config.operators.mulDiv.implementation.ip.outputFifoDepth == 8)
    assert(config.operators.floating.implementation.ip.outputFifoDepth == 8)
    assert(config.operators.mulDiv.multiplyTiming.responseFifoDepth == 8)
    assert(config.operators.floating.divideTiming.responseFifoDepth == 8)
  }

  "IP terminal traits" should "reuse one timing contract for FPGA and NEMU functional simulation" in {
    val defaults = OperatorIpTimingConfig.Default
    val timing = defaults.copy(
      outputFifoDepth = 8,
      multiply = defaults.multiply.copy(latency = 5),
      floatingDivide = defaults.floatingDivide.copy(latency = 31)
    )
    val fpga = new FpgaIpTerminal {
      override val operatorTiming: OperatorIpTimingConfig = timing
    }

    val fpgaConfig = (fpga.computeUnitConfig ++ new Rv64IMZicsrConfig).build
    val nemuConfig = (NemuSimulationIpTerminal.from(fpga).computeUnitConfig ++ new Rv64IMFZicsrConfig).build

    assert(fpgaConfig.operators.mulDiv.implementation.backend == ComputeBackend.FPGA)
    assert(fpgaConfig.operators.mulDiv.multiplyTiming.latency == 5)
    assert(fpgaConfig.operators.mulDiv.multiplyTiming.responseFifoDepth == 8)
    assert(fpgaConfig.operators.routes.route(ArithmeticRouteOperation.Mul).latency == 5)
    assert(fpgaConfig.operators.routes.route(ArithmeticRouteOperation.Div).latency == defaults.divide.latency)
    assert(nemuConfig.operators.mulDiv.implementation.backend == ComputeBackend.Builtin)
    assert(nemuConfig.operators.floating.implementation.backend == ComputeBackend.Builtin)
    assert(nemuConfig.operators.mulDiv.multiplyTiming.latency == 5)
    assert(nemuConfig.operators.floating.divideTiming.latency == 31)
    assert(nemuConfig.operators.floating.divideTiming.responseFifoDepth == 8)
    assert(nemuConfig.operators.routes.route(ArithmeticRouteOperation.Mul).latency == 5)
    assert(nemuConfig.operators.routes.route(ArithmeticRouteOperation.Fdiv).latency == 31)
  }

  "Zicsr fragments" should "make the extension explicit and preserve left precedence" in {
    val disabled = (new WithoutZicsrConfig ++ new Rv64IMFZicsrConfig).build
    val enabled = (new WithZicsrConfig ++ new WithoutZicsrConfig ++ new Rv64IMFZicsrConfig).build

    assert(!disabled.isa.Zicsr)
    assert(!disabled.isa.F)
    assert(enabled.isa.Zicsr)
  }

  "NPC ISA presets" should "build RV32 variants from I and derive RV64 by overriding only XLEN" in {
    val base = new Rv64IConfig().build
    val rv32Full = new Rv32IMFZicsrConfig().build
    val full = new Rv64IMFZicsrConfig().build

    assert(base.isa.xlen == 64)
    assert(!base.isa.M)
    assert(!base.isa.F)
    assert(!base.isa.Zicsr)
    assert(full.isa.xlen == 64)
    assert(full.isa.M)
    assert(full.isa.F)
    assert(full.isa.Zicsr)
    assert(rv32Full.isa.M == full.isa.M)
    assert(rv32Full.isa.F == full.isa.F)
    assert(rv32Full.isa.Zicsr == full.isa.Zicsr)
    assert(rv32Full.isa.xlen == 32)
    assert(rv32Full.axi.dataWidth == 32)
    assert(full.axi.dataWidth == 64)
  }

  "NPC performance presets" should "keep frequency-timing switches independently selectable" in {
    val scalar = new ScalarPerformConfig().build
    val singleEx = new PipelineExFwdPerformConfig().build
    val dual = new PipelineDualFwdPerformConfig().build
    val twoStage = new PipelineDualFwdTwoStageIntegerExecutePerformConfig().build
    val registeredFetch = (new WithRegisteredInitialFetchRequestConfig ++
      new PipelineDualFwdPerformConfig).build
    val combined = new PipelineDualFwdTwoStageIntegerExecuteRegisteredFetchPerformConfig().build
    val splitSerialAlu = (new WithSeparateSerialIntegerAluConfig ++
      new PipelineDualFwdPerformConfig).build
    val serialOneAdditionalStage = (new WithSerialExecuteAdditionalStagesConfig(1) ++
      new PipelineDualFwdPerformConfig).build
    val allTimingCuts =
      new PipelineDualFwdTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecutePerformConfig().build

    assert(!scalar.pipeline.enablePipeline)
    assert(!scalar.pipeline.forwarding.enableIdForwarding)
    assert(!scalar.pipeline.forwarding.enableExecuteForwarding)
    assert(singleEx.pipeline.enablePipeline)
    assert(!singleEx.pipeline.forwarding.enableIdForwarding)
    assert(singleEx.pipeline.forwarding.enableExecuteForwarding)
    assert(dual.pipeline.enablePipeline)
    assert(dual.pipeline.forwarding.enableIdForwarding)
    assert(dual.pipeline.forwarding.enableExecuteForwarding)
    assert(dual.pipeline.integerExecuteStages == 1)
    assert(dual.pipeline.serialExecuteStages == 1)
    assert(!dual.pipeline.registerInitialFetchRequest)
    assert(!dual.pipeline.separateSerialIntegerAlu)
    assert(dual.pipeline.serialExecuteResultForwarding)
    assert(!dual.pipeline.directIntegerWritebackBypass)
    assert(twoStage.pipeline.enablePipeline)
    assert(twoStage.pipeline.forwarding.enableIdForwarding)
    assert(twoStage.pipeline.forwarding.enableExecuteForwarding)
    assert(twoStage.pipeline.integerExecuteStages == 2)
    assert(twoStage.pipeline.serialExecuteStages == 1)
    assert(!twoStage.pipeline.registerInitialFetchRequest)
    assert(!twoStage.pipeline.separateSerialIntegerAlu)
    assert(twoStage.pipeline.serialExecuteResultForwarding)
    assert(registeredFetch.pipeline.integerExecuteStages == 1)
    assert(registeredFetch.pipeline.registerInitialFetchRequest)
    assert(combined.pipeline.integerExecuteStages == 2)
    assert(combined.pipeline.registerInitialFetchRequest)
    assert(!combined.pipeline.separateSerialIntegerAlu)
    assert(splitSerialAlu.pipeline.integerExecuteStages == 1)
    assert(splitSerialAlu.pipeline.serialExecuteStages == 1)
    assert(splitSerialAlu.pipeline.separateSerialIntegerAlu)
    assert(splitSerialAlu.pipeline.serialExecuteResultForwarding)
    assert(serialOneAdditionalStage.pipeline.serialExecuteStages == 2)
    assert(allTimingCuts.pipeline.integerExecuteStages == 2)
    assert(allTimingCuts.pipeline.serialExecuteStages == 3)
    assert(allTimingCuts.pipeline.registerInitialFetchRequest)
    assert(allTimingCuts.pipeline.separateSerialIntegerAlu)
    assert(!allTimingCuts.pipeline.serialExecuteResultForwarding)
  }

  "NPC terminal configurations" should "wrap one complete core before adding the host ABI" in {
    val terminalAndCore = Seq(
      new StandaloneConfig().config -> new StandaloneCoreConfig().build,
      new SimulationConfig().config -> new SimulationCoreConfig().build,
      new PipelineSimulationConfig().config -> new PipelineSimulationCoreConfig().build,
      new FullIsa64NoPipelineSimulationConfig().config ->
        new FullIsa64NoPipelineSimulationCoreConfig().build,
      new FullIsa64PipelineNoForwardingSimulationConfig().config ->
        new FullIsa64PipelineNoForwardingSimulationCoreConfig().build,
      new FullIsa64PipelineDualForwardingSimulationConfig().config ->
        new FullIsa64PipelineDualForwardingSimulationCoreConfig().build,
      new Zcu102Rv32OperatorSimulationConfig().config ->
        new Zcu102Rv32OperatorSimulationCoreConfig().build,
      new U55cRv32OperatorSimulationConfig().config ->
        new U55cRv32OperatorSimulationCoreConfig().build,
      new U55cRv64OperatorSimulationConfig().config ->
        new U55cRv64OperatorSimulationCoreConfig().build
    )

    terminalAndCore.foreach { case (terminal, core) =>
      assert(terminal == NemuSimulationIpTerminal.computeUnitConfig.applyTo(core).validated)
    }
  }
}
