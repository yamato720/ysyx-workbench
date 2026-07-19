package scpu

import org.scalatest.flatspec.AnyFlatSpec

class IntegerAluTest extends AnyFlatSpec {
  "IntegerAlu" should "elaborate integer and control-flow datapaths" in {
    val chirrtl = _root_.circt.stage.ChiselStage.emitCHIRRTL(new IntegerAlu(32))

    assert(chirrtl.contains("module IntegerAlu"))
    assert(chirrtl.contains("result : UInt<32>"))
    assert(chirrtl.contains("branchTaken : UInt<3>"))
  }
}
