package npc

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

  it should "use one registered arithmetic response instead of a tag completion queue" in {
    val chirrtl = _root_.circt.stage.ChiselStage.emitCHIRRTL(new NpcCore(config(64)))

    assert(chirrtl.contains("arithmeticResponseReg"))
    assert(!chirrtl.contains("arithmeticEntries"))
    assert(!chirrtl.contains("arithmeticHead"))
    assert(!chirrtl.contains("arithmeticTail"))
    assert(!chirrtl.contains("arithmeticResponseArbiter"))
  }

  it should "make EX0, serial result, serial control, and serial ALU cuts independently selectable in both XLENs" in {
    Seq(32, 64).foreach { xlen =>
      val twoStage = config(xlen).copy(pipeline = PipelineConfig(
        enablePipeline = true,
        integerExecuteStages = 2
      ))
      val chirrtl = _root_.circt.stage.ChiselStage.emitCHIRRTL(new NpcCore(twoStage))
      assert(chirrtl.contains("integerExecuteReg"))
      assert(!chirrtl.contains("serialIntegerAlu"))

      val splitSerialAlu = twoStage.copy(pipeline = twoStage.pipeline.copy(separateSerialIntegerAlu = true))
      val splitChirrtl = _root_.circt.stage.ChiselStage.emitCHIRRTL(new NpcCore(splitSerialAlu))
      assert(splitChirrtl.contains("integerExecuteReg"))
      assert(splitChirrtl.contains("serialIntegerAlu"))

      val twoStageSerial = splitSerialAlu.copy(pipeline = splitSerialAlu.pipeline.copy(serialExecuteStages = 2))
      val serialChirrtl = _root_.circt.stage.ChiselStage.emitCHIRRTL(new NpcCore(twoStageSerial))
      assert(serialChirrtl.contains("serialExecuteResultReg"))

      val threeStageSerial = splitSerialAlu.copy(pipeline = splitSerialAlu.pipeline.copy(serialExecuteStages = 3))
      val threeStageChirrtl = _root_.circt.stage.ChiselStage.emitCHIRRTL(new NpcCore(threeStageSerial))
      assert(threeStageChirrtl.contains("serialExecuteControlReg"))
      assert(threeStageChirrtl.contains("serialExecuteResultReg"))
    }
  }

  it should "register two-stage integer branch redirects through EX/MEM" in {
    val twoStage = config(64).copy(pipeline = PipelineConfig(
      enablePipeline = true,
      integerExecuteStages = 2
    ))
    val chirrtl = _root_.circt.stage.ChiselStage.emitCHIRRTL(new NpcCore(twoStage))

    assert(chirrtl.contains("executeMemoryRedirectPending"))
    assert(chirrtl.contains("executeMemoryReg"))
  }

  it should "elaborate the registered initial fetch request in both XLENs" in {
    Seq(32, 64).foreach { xlen =>
      val registeredFetch = config(xlen).copy(pipeline = PipelineConfig(
        enablePipeline = true,
        registerInitialFetchRequest = true
      ))
      val chirrtl = _root_.circt.stage.ChiselStage.emitCHIRRTL(new NpcCore(registeredFetch))
      assert(chirrtl.contains("requestPc"))
      assert(chirrtl.contains("IFetchAXIAdapter"))
    }
  }
}
