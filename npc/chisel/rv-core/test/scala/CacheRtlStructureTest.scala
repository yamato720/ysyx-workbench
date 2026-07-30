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

  "NpcCore" should "insert both caches and the maintenance controller only for the explicit Config" in {
    val legacy = _root_.circt.stage.ChiselStage.emitCHIRRTL(new NpcCore(new SimulationConfig().config))
    val cached = _root_.circt.stage.ChiselStage.emitCHIRRTL(new NpcCore(new CacheSimulationConfig().config))

    assert(!legacy.contains("module InstructionCache"))
    assert(!legacy.contains("module DataCache"))
    assert(cached.contains("module InstructionCache"))
    assert(cached.contains("module DataCache"))
    assert(cached.contains("module CacheMaintenanceController"))
    assert(cached.contains("module InstructionBuffer"))
    assert(cached.contains("commitStoreValid"))
  }
}
