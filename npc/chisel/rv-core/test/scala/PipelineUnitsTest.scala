package npc

import org.scalatest.flatspec.AnyFlatSpec

class PipelineUnitsTest extends AnyFlatSpec {
  "HazardUnit" should "elaborate seven producer ports with forwarding availability" in {
    val chirrtl = _root_.circt.stage.ChiselStage.emitCHIRRTL(new HazardUnit)

    assert(chirrtl.contains("module HazardUnit"))
    assert(chirrtl.contains("producers : {"))
    assert(chirrtl.contains("idForwardAvailable : UInt<1>"))
    assert(chirrtl.contains("executeForwardNextCycleAvailable : UInt<1>"))
    assert(chirrtl.contains("stall : UInt<1>"))
  }

  "ForwardingUnit" should "elaborate active ID and EX forwarding selectors" in {
    val chirrtl = _root_.circt.stage.ChiselStage.emitCHIRRTL(new ForwardingUnit(32))

    assert(chirrtl.contains("module ForwardingUnit"))
    assert(chirrtl.contains("idCandidates : {"))
    assert(chirrtl.contains("dataValid : UInt<1>"))
    assert(chirrtl.contains("executeRs1 : UInt<5>"))
    assert(chirrtl.contains("executeRs2Forwarded : UInt<32>"))
  }

  "PipelineConfig" should "enable both forwarding paths by default for a pipeline" in {
    val pipeline = PipelineConfig(enablePipeline = true)

    assert(pipeline.forwarding.enableIdForwarding)
    assert(pipeline.forwarding.enableExecuteForwarding)
    assert(!pipeline.forwarding.enableOutstandingCompletionForwarding)
    assert(pipeline.integerExecuteStages == 1)
    assert(pipeline.serialExecuteStages == 1)
    assert(!pipeline.registerInitialFetchRequest)
    assert(pipeline.serialExecuteResultForwarding)
  }

  it should "only accept one or two integer stages and one through three serial stages" in {
    assert(PipelineConfig(integerExecuteStages = 2).integerExecuteStages == 2)
    assert(PipelineConfig(serialExecuteStages = 2).serialExecuteStages == 2)
    assert(PipelineConfig(serialExecuteStages = 3).serialExecuteStages == 3)
    assert(new WithSerialExecuteAdditionalStagesConfig(1).applyTo(NpcConfig()).pipeline.serialExecuteStages == 2)
    assert(new WithSerialExecuteAdditionalStagesConfig(2).applyTo(NpcConfig()).pipeline.serialExecuteStages == 3)
    assert(PipelineConfig(registerInitialFetchRequest = true).registerInitialFetchRequest)
    assert(!PipelineConfig(serialExecuteResultForwarding = false).serialExecuteResultForwarding)
    assertThrows[IllegalArgumentException](PipelineConfig(integerExecuteStages = 3))
    assertThrows[IllegalArgumentException](PipelineConfig(serialExecuteStages = 4))
    assertThrows[IllegalArgumentException](new WithSerialExecuteAdditionalStagesConfig(0))
    assertThrows[IllegalArgumentException](new WithSerialExecuteAdditionalStagesConfig(3))
  }
}
