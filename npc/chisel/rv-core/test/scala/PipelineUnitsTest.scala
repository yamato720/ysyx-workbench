package scpu

import org.scalatest.flatspec.AnyFlatSpec

class PipelineUnitsTest extends AnyFlatSpec {
  "HazardUnit" should "elaborate five producer ports with forwarding availability" in {
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
  }
}
