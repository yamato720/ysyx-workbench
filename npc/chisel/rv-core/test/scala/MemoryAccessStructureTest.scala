package scpu

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

  "DPI slaves" should "elaborate both 32-bit and 64-bit word interfaces" in {
    Seq(32, 64).foreach { xlen =>
      val ram = _root_.circt.stage.ChiselStage.emitCHIRRTL(
        new scpu.protocol.AxiLiteDpiRamSlave(dataWidth = xlen)
      )
      val mmio = _root_.circt.stage.ChiselStage.emitCHIRRTL(
        new scpu.protocol.AxiLiteDpiMmioSlave(dataWidth = xlen)
      )

      assert(ram.contains(s"data : UInt<$xlen>"))
      assert(mmio.contains(s"data : UInt<$xlen>"))
      assert(mmio.contains("strb"))
    }
  }
}
