package scpu

import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec

class NpcConfigCompositionTest extends AnyFlatSpec {
  "Construction boundary" should "expose only NEMU-running terminal configurations" in {
    assert(new StandaloneConfig().capability == "run")
    assert(new PipelineCheckConfig().capability == "check-only")
    assert(new SimulationConfig().capability == "run")
    assert(new SimulationConfig().nemuConfig == NemuHostConfig.LocalPipelineTrace)
    assert(!new FpgaConfig().isInstanceOf[MakeTerminalConfig])
    assert(!new ExternalAxiConfig().isInstanceOf[MakeTerminalConfig])
  }

  "ConstructionConfig" should "directly provide its completed core through the CDE key" in {
    val construction = new FpgaConfig
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
    val config = new FpgaConfig().config

    assert(config.isa.xlen == 32)
    assert(config.isa.M)
    assert(config.isa.F)
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
    assert(config.operators.routes.route(ArithmeticRouteOperation.Fadd).target == OperatorRouteTarget.Model)
    config.operators.routes.validate(config.isa)
  }

  "Operator route defaults" should "cover every enabled M/F operation and reject an unselected check route" in {
    val model = new U55cRv64OperatorSimulationConfig().config
    assert(model.isa.xlen == 64)
    assert(model.operators.routes.profileValues(model.isa).size ==
      ArithmeticRouteOperation.mOperations.size + ArithmeticRouteOperation.fOperations.size)

    val unselected = (
      new WithOperatorRoutesConfig(OperatorRouteConfig(Map(
        ArithmeticRouteOperation.Mul -> OperatorRoute(
          OperatorRouteTarget.Unselected, "unselected", 64, 1, 1)
      ))) ++
        new Rv64IMZicsrConfig
    ).build
    assertThrows[IllegalArgumentException](unselected.operators.routes.validate(unselected.isa))
  }

  it should "remain composable when an integration adds a later fragment" in {
    val config = (
      new ScalarPerformConfig ++
        new FpgaConfig
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

  "NPC performance presets" should "separate scalar, single-EX forwarding, and dual forwarding" in {
    val scalar = new ScalarPerformConfig().build
    val singleEx = new PipelineExFwdPerformConfig().build
    val dual = new PipelineDualFwdPerformConfig().build

    assert(!scalar.pipeline.enablePipeline)
    assert(!scalar.pipeline.forwarding.enableIdForwarding)
    assert(!scalar.pipeline.forwarding.enableExecuteForwarding)
    assert(singleEx.pipeline.enablePipeline)
    assert(!singleEx.pipeline.forwarding.enableIdForwarding)
    assert(singleEx.pipeline.forwarding.enableExecuteForwarding)
    assert(dual.pipeline.enablePipeline)
    assert(dual.pipeline.forwarding.enableIdForwarding)
    assert(dual.pipeline.forwarding.enableExecuteForwarding)
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

    terminalAndCore.foreach { case (terminal, core) => assert(terminal == core) }
  }
}
