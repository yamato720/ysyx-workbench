package npc

import org.scalatest.flatspec.AnyFlatSpec

class CacheRtlStructureTest extends AnyFlatSpec {
  private val geometry = CacheGeometry(256, 16, CacheMapping.SetAssociative(2))

  "CacheController" should "elaborate every replacement policy with beat refill and maintenance states" in {
    Seq(CacheReplacement.LRU, CacheReplacement.TreePLRU,
      CacheReplacement.FIFO, CacheReplacement.Random).foreach { replacement =>
      val cache = CacheConfig(enabled = true, geometry = geometry,
        replacement = replacement, storage = CacheStorage.Registers)
      val chirrtl = _root_.circt.stage.ChiselStage.emitCHIRRTL(
        new CacheController(cache, 32, 64, 0x80000000L, 0x10000000L, readOnly = false))
      assert(chirrtl.contains("module CacheController"))
      assert(chirrtl.contains("module CacheReplacementUnit"))
      assert(chirrtl.contains("writebackBeat"))
      assert(chirrtl.contains("refillBeat"))
      assert(chirrtl.contains("maintenanceRequest"))
    }
  }

  it should "infer synchronous arrays for Auto and mark URAM arrays by stable names" in {
    val auto = CacheConfig(enabled = true, geometry = geometry, storage = CacheStorage.Auto)
    val uram = auto.copy(storage = CacheStorage.Uram)
    val autoChirrtl = _root_.circt.stage.ChiselStage.emitCHIRRTL(
      new CacheArray(auto, 32, 64, hasDirty = true))
    val uramSystemVerilog = _root_.circt.stage.ChiselStage.emitSystemVerilog(
      new CacheArray(uram, 32, 64, hasDirty = true))

    assert(autoChirrtl.contains("smem cache_way_0_data"))
    assert(autoChirrtl.contains("smem cache_way_0_tag"))
    assert(uramSystemVerilog.contains("ram_style = \"ultra\""))
  }

  "PipelinedCacheController" should "elaborate S0/S1 stages, ordered queues, and blocking maintenance" in {
    val cache = CacheConfig(enabled = true, geometry = geometry, storage = CacheStorage.Auto)
    val chirrtl = _root_.circt.stage.ChiselStage.emitCHIRRTL(
      new PipelinedCacheController(cache, 32, 64, 0x80000000L, 0x10000000L,
        readOnly = false, PipelinedCacheQueueConfig.TwoCycleLocal))

    assert(chirrtl.contains("module PipelinedCacheController"))
    assert(chirrtl.contains("s0Valid"))
    assert(chirrtl.contains("s1Valid"))
    assert(chirrtl.contains("requestQueue"))
    assert(chirrtl.contains("responseQueue"))
    assert(chirrtl.contains("maintenanceCanStart"))
  }

  "NpcCore" should "insert both caches and the maintenance controller only for the explicit Config" in {
    val legacy = _root_.circt.stage.ChiselStage.emitCHIRRTL(new NpcCore(new SimulationConfig().config))
    val cached = _root_.circt.stage.ChiselStage.emitSystemVerilog(
      new NpcCore(new CacheSimulationConfig().config))

    assert(!legacy.contains("module InstructionCache"))
    assert(!legacy.contains("module DataCache"))
    assert(cached.contains("module InstructionCache"))
    assert(cached.contains("module DataCache"))
    assert(cached.contains("module CacheMaintenanceController"))
    assert(cached.contains("module InstructionBuffer"))
    assert(cached.contains("commitStoreValid"))
  }

  it should "place a unified L2 only in the explicit wide-HBM hierarchy" in {
    val wideL2 = NpcConfig(
      isa = ISAConfig(xlen = 64, Zicsr = true, Zifencei = true),
      axi = AxiConfig(dataWidth = 64, useExternalMaster = true, externalDataWidth = 512),
      cache = CacheHierarchyConfig.WideHbmWithL2
    ).validated
    val legacy = _root_.circt.stage.ChiselStage.emitCHIRRTL(new NpcCore(new SimulationConfig().config))
    val cached = _root_.circt.stage.ChiselStage.emitCHIRRTL(new NpcCore(wideL2))

    assert(!legacy.contains("module UnifiedL2Cache"))
    assert(cached.contains("module UnifiedL2Cache"))
    assert(cached.contains("l2Flush"))
  }

  it should "elaborate the local two-cycle hierarchy without changing the blocking presets" in {
    val pipelined = _root_.circt.stage.ChiselStage.emitCHIRRTL(
      new NpcCore(new PipelinedTwoCycleWideL2SimulationCoreConfig().build))

    assert(pipelined.contains("module PipelinedCacheController"))
    assert(pipelined.contains("module PipelinedIFetchAXIAdapter"))
    assert(pipelined.contains("module PipelinedMemoryStage"))
    assert(pipelined.contains("module PipelinedAxiLiteArbiter2"))
    assert(pipelined.contains("module PipelinedAxiLiteCrossbar"))
    assert(pipelined.contains("module PipelinedAxiLiteDpiRamSlave"))
  }
}
