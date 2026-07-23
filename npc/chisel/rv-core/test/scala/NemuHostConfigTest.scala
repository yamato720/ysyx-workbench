package scpu

import org.scalatest.flatspec.AnyFlatSpec

class NemuHostConfigTest extends AnyFlatSpec {
  private final class CustomLocalTerminal extends LocalNpcTerminal {
    override protected val configuredNemu: NemuHostConfig = NemuHostConfig.LocalBase.copy(
      trace = true,
      vcd = true
    )
  }

  private final class CustomU55cTerminal extends U55cNpcTerminal {
    override protected val configuredNemu: NemuHostConfig = NemuHostConfig.U55cBase.copy(debug = true)
    override protected val configuredFpga: FpgaToolchainConfig = FpgaToolchainConfig.U55cBase.copy(
      flow = FpgaToolchainConfig.U55cBase.flow.copy(implementationParallelJobs = 12)
    )
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
    assertThrows[IllegalArgumentException](ConstructionValidation.localNemu(NemuHostConfig.U55cBase))
    assertThrows[IllegalArgumentException](ConstructionValidation.fpga(
      NemuHostConfig.U55cBase,
      FpgaToolchainConfig.Zcu102Base
    ))
  }

  it should "mount complete defaults and allow explicit recipe overrides" in {
    val terminals = Seq(
      new LocalNpcTerminal {} -> ("npc", "NPC", "local"),
      new LocalSocTerminal {} -> ("soc", "SOC", "local"),
      new U55cNpcTerminal {} -> ("fpga", "NPC", "u55c"),
      new U55cSocTerminal {} -> ("fpga", "SOC", "u55c"),
      new Zcu102NpcTerminal {} -> ("fpga", "NPC", "zcu102"),
      new Zcu102SocTerminal {} -> ("fpga", "SOC", "zcu102")
    )

    terminals.foreach { case (terminal, (scope, target, backend)) =>
      assert(terminal.constructionScope == scope)
      assert(terminal.constructionTarget == target)
      assert(terminal.nemuConfig.backend.id == backend)
    }

    assert((new U55cNpcTerminal {}).fpgaToolchainConfig == FpgaToolchainConfig.U55cBase)
    assert((new Zcu102SocTerminal {}).fpgaToolchainConfig == FpgaToolchainConfig.Zcu102Base)

    val customLocal = new CustomLocalTerminal
    assert(customLocal.nemuConfig.trace && customLocal.nemuConfig.vcd)
    assert(customLocal.nemuPreset == "Custom")
    val customU55c = new CustomU55cTerminal
    assert(customU55c.nemuConfig.debug)
    assert(customU55c.fpgaToolchainConfig.flow.implementationParallelJobs == 12)
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
