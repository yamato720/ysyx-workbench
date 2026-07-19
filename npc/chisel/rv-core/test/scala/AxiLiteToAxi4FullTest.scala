package scpu

import org.scalatest.flatspec.AnyFlatSpec
import scpu.protocol.AxiLiteToAxi4Full

class AxiLiteToAxi4FullTest extends AnyFlatSpec {
  "AxiLiteToAxi4Full" should "elaborate a 32-bit AXI4-Full master port" in {
    val chirrtl = _root_.circt.stage.ChiselStage.emitCHIRRTL(
      new AxiLiteToAxi4Full(addrWidth = 32, dataWidth = 32, idWidth = 4, transactionId = 3)
    )

    assert(chirrtl.contains("module AxiLiteToAxi4Full"))
    assert(chirrtl.contains("io.axi.aw.bits.len"))
    assert(chirrtl.contains("io.axi.w.bits.last"))
    assert(chirrtl.contains("io.axi.ar.bits.size"))
  }
}
