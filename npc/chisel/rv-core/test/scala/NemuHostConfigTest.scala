package scpu

import org.scalatest.flatspec.AnyFlatSpec

class NemuHostConfigTest extends AnyFlatSpec {
  private final class InvalidLocalConstruction extends NemuSimulationConstructionConfig {
    override protected val configuredNemu: NemuHostConfig = NemuHostConfig.U55cBase
  }

  private final class InvalidFpgaConstruction extends FpgaConstructionConfig {
    override protected val configuredNemu: NemuHostConfig = NemuHostConfig.U55cBase
    override protected val configuredFpga: FpgaToolchainConfig = FpgaToolchainConfig.Zcu102Base
  }

  "NEMU host Base" should "explicitly bind every controlled field" in {
    assert(NemuHostConfig.LocalBase.backend == NemuBackend.LocalVerilator)
    assert(NemuHostConfig.LocalBase.devices)
    assert(NemuHostConfig.LocalBase.watchpoint)
    assert(NemuHostConfig.LocalBase.optimization == "O2")
    assert(!NemuHostConfig.LocalBase.trace)
    assert(!NemuHostConfig.LocalBase.vcd)
    assert(!NemuHostConfig.LocalBase.performanceHtml)
    assert(!NemuHostConfig.LocalBase.pipelineHtml)
    assert(!NemuHostConfig.LocalBase.softwareDifftest)
    assert(!NemuHostConfig.LocalBase.debug)
    assert(!NemuHostConfig.LocalBase.lto)
    assert(!NemuHostConfig.LocalBase.asan)

    assert(NemuHostConfig.LocalPerformance.performanceHtml)
    assert(!NemuHostConfig.LocalPerformance.pipelineHtml)
    assert(NemuHostConfig.LocalPipelineTrace.performanceHtml)
    assert(NemuHostConfig.LocalPipelineTrace.pipelineHtml)
    assert(NemuHostConfig.LocalPipelineTrace.softwareDifftest)
    assert(NemuHostConfig.U55cBase.backend == NemuBackend.U55c)
    assert(NemuHostConfig.Zcu102Base.backend == NemuBackend.Zcu102)
  }

  it should "support direct copy overrides and reject invalid combinations" in {
    val debug = NemuHostConfig.LocalBase.copy(
      trace = true,
      vcd = true,
      optimization = "O0",
      debug = true
    )
    assert(debug.vcd && debug.debug && debug.optimization == "O0")
    assert(NemuHostConfig.presetName(debug) == "Custom")

    assertThrows[IllegalArgumentException](NemuHostConfig.LocalBase.copy(vcd = true))
    assertThrows[IllegalArgumentException](NemuHostConfig.U55cBase.copy(vcd = true, trace = true))
    assertThrows[IllegalArgumentException](NemuHostConfig.LocalBase.copy(pipelineHtml = true))
    assertThrows[IllegalArgumentException](NemuHostConfig.U55cBase.copy(
      performanceHtml = true,
      pipelineHtml = true
    ))
    assertThrows[IllegalArgumentException](NemuHostConfig.U55cBase.copy(softwareDifftest = true))
    assertThrows[IllegalArgumentException](NemuHostConfig.LocalBase.copy(optimization = "Os"))
  }

  "Construction traits" should "enforce local and FPGA backend ownership" in {
    assertThrows[IllegalArgumentException](new InvalidLocalConstruction().nemuConfig)
    assertThrows[IllegalArgumentException](new InvalidFpgaConstruction().fpgaToolchainConfig)
  }

  "NEMU host catalog" should "use only explicitly registered case class Base values" in {
    assert(NemuHostConfig.registeredPresets.map(_.name) == Vector(
      "LocalBase",
      "LocalPerformance",
      "LocalPipelineTrace",
      "U55cBase",
      "Zcu102Base"
    ))
    assert(NemuHostConfig.registeredPresets.map(_.config).distinct.size == 5)
    assert(NemuHostConfig.presetName(NemuHostConfig.LocalPipelineTrace) == "LocalPipelineTrace")
  }
}
