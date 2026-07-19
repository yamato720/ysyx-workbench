package scpu

import org.scalatest.flatspec.AnyFlatSpec

class NpcModularStructureTest extends AnyFlatSpec {
  private def config(xlen: Int) = NpcConfig(
    isa = ISAConfig(xlen = xlen, M = true),
    axi = AxiConfig(dataWidth = xlen),
    pipeline = PipelineConfig(enablePipeline = true),
    debug = DebugConfig(enableTopDebugIo = true)
  )

  "NpcFrontend" should "expose decoded operand-free dispatch and redirect ports" in {
    val chirrtl = _root_.circt.stage.ChiselStage.emitCHIRRTL(new NpcFrontend(config(32)))

    assert(chirrtl.contains("module NpcFrontend"))
    assert(chirrtl.contains("dispatch : {"))
    assert(chirrtl.contains("rs1 : UInt<5>"))
    assert(chirrtl.contains("rs2 : UInt<5>"))
    assert(chirrtl.contains("redirectValid : UInt<1>"))
  }

  "NpcCore" should "keep the CPU name, AXI port, and nested debug bundle in both XLENs" in {
    Seq(32, 64).foreach { xlen =>
      val chirrtl = _root_.circt.stage.ChiselStage.emitCHIRRTL(new NpcCore(config(xlen)))
      assert(chirrtl.contains("module CPU"))
      assert(chirrtl.contains("master : {"))
      val debugStart = chirrtl.indexOf("debug : {")
      assert(debugStart >= 0)
      val debug = chirrtl.substring(debugStart)
      assert(debug.contains("frontend : {"))
      assert(debug.contains("backend : {"))
      assert(debug.contains("master : {"))
      assert(!chirrtl.contains("debugCommitInstruction : UInt"))
    }
  }
}
