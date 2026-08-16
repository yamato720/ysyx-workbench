package npc

import org.scalatest.flatspec.AnyFlatSpec

class MemoryAccessStructureTest extends AnyFlatSpec {
  private def config(xlen: Int) = NpcConfig(
    isa = ISAConfig(xlen = xlen, M = true),
    axi = AxiConfig(dataWidth = xlen),
    debug = DebugConfig(enableTopDebugIo = true)
  )

  "XLEN memory adapters" should "elaborate aligned full-width main-memory paths and fault payloads" in {
    Seq(32, 64).foreach { xlen =>
      val lsu = _root_.circt.stage.ChiselStage.emitCHIRRTL(
        new LSUAXIAdapter(addrWidth = 32, dataWidth = xlen)
      )
      val fetch = _root_.circt.stage.ChiselStage.emitCHIRRTL(
        new IFetchAXIAdapter(addrWidth = 32, dataWidth = xlen)
      )
      val core = _root_.circt.stage.ChiselStage.emitCHIRRTL(new NpcCore(config(xlen)))

      assert(lsu.contains(s"data : UInt<$xlen>"))
      assert(lsu.contains("fault : {"))
      assert(lsu.contains("reason : UInt<3>"))
      assert(lsu.contains("narrowAccessSize"))
      assert(lsu.contains("node requestSize = mux"))
      assert(lsu.contains(s"UInt<3>(0h${if (xlen == 32) "2" else "3"})"))
      assert(fetch.contains(s"data : UInt<$xlen>"))
      assert(fetch.contains("fault : {"))
      assert(fetch.contains("connect io.axi.ar.bits.addr"))
      assert(fetch.contains(s"connect io.axi.ar.bits.size, UInt<3>(0h${if (xlen == 32) "2" else "3"})"))
      assert(core.contains("memoryFault : {"))
    }
  }

  "DPI slaves" should "elaborate narrow and 512-bit cache-memory interfaces" in {
    Seq(32, 64).foreach { xlen =>
      val ram = _root_.circt.stage.ChiselStage.emitCHIRRTL(
        new npc.protocol.AxiLiteDpiRamSlave(dataWidth = xlen)
      )
      val mmio = _root_.circt.stage.ChiselStage.emitCHIRRTL(
        new npc.protocol.AxiLiteDpiMmioSlave(dataWidth = xlen)
      )

      assert(ram.contains(s"data : UInt<$xlen>"))
      assert(mmio.contains(s"data : UInt<$xlen>"))
      assert(mmio.contains("strb"))
    }

    val wideTiming = DpiMemoryTimingConfig.HbmJitter73To81
    val wideRam = _root_.circt.stage.ChiselStage.emitCHIRRTL(
      new npc.protocol.AxiLiteDpiRamSlave(dataWidth = 512, timing = wideTiming)
    )
    val wideMmio = _root_.circt.stage.ChiselStage.emitCHIRRTL(
      new npc.protocol.AxiLiteDpiMmioSlave(dataWidth = 512)
    )
    assert(wideRam.contains("data : UInt<512>"))
    assert(wideRam.contains("delayCounter"))
    assert(wideRam.contains("randomState"))
    assert(wideMmio.contains("data : UInt<512>"))
    assert(wideMmio.contains("MMIOCore"))

    val pipelinedRam = _root_.circt.stage.ChiselStage.emitCHIRRTL(
      new npc.protocol.PipelinedAxiLiteDpiRamSlave(dataWidth = 512, depth = 4)
    )
    val pipelinedArbiter = _root_.circt.stage.ChiselStage.emitCHIRRTL(
      new npc.protocol.PipelinedAxiLiteArbiter2(dataWidth = 512, depth = 4)
    )
    val pipelinedInterleaver = _root_.circt.stage.ChiselStage.emitCHIRRTL(
      new npc.protocol.PipelinedAxiLiteXorInterleaver2(dataWidth = 512,
        highSelectBit = 14, lowSelectBit = 6, extraSelectBits = Seq(7, 8), depth = 4)
    )
    assert(pipelinedRam.contains("module PipelinedAxiLiteDpiRamSlave"))
    assert(pipelinedArbiter.contains("readRoutes"))
    assert(pipelinedInterleaver.contains("module PipelinedAxiLiteXorInterleaver2"))
    assert(pipelinedInterleaver.contains("writeRoutes"))
  }
}
