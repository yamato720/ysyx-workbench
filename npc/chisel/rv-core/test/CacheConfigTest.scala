package npc

import org.scalatest.flatspec.AnyFlatSpec

class CacheConfigTest extends AnyFlatSpec {
  "CacheGeometry" should "derive direct, set-associative, and fully-associative address fields" in {
    val direct = CacheGeometry(4096, 16, CacheMapping.DirectMapped)
    val setAssociative = CacheGeometry(4096, 16, CacheMapping.SetAssociative(4))
    val fullyAssociative = CacheGeometry(4096, 16, CacheMapping.FullyAssociative)

    assert(direct.lines == 256 && direct.ways == 1 && direct.sets == 256)
    assert(direct.offsetBits == 4 && direct.indexBits == 8 && direct.tagBits(32) == 20)
    assert(setAssociative.ways == 4 && setAssociative.sets == 64)
    assert(setAssociative.indexBits == 6 && setAssociative.tagBits(32) == 22)
    assert(fullyAssociative.ways == 256 && fullyAssociative.sets == 1)
    assert(fullyAssociative.indexBits == 0 && fullyAssociative.tagBits(32) == 28)
  }

  it should "reject inconsistent geometry instead of accepting a user tag width" in {
    assertThrows[IllegalArgumentException](CacheGeometry(3072, 16, CacheMapping.DirectMapped))
    assertThrows[IllegalArgumentException](CacheGeometry(4096, 24, CacheMapping.DirectMapped))
    assertThrows[IllegalArgumentException](CacheGeometry(64, 16, CacheMapping.SetAssociative(8)))
    assertThrows[IllegalArgumentException](CacheMapping.SetAssociative(3))

    val narrowerThanBus = CacheConfig(enabled = true,
      geometry = CacheGeometry(64, 4, CacheMapping.DirectMapped))
    assertThrows[IllegalArgumentException](narrowerThanBus.validate(32, 64))
  }

  "Cache policies" should "accept all replacement and allocation combinations" in {
    val geometry = CacheGeometry(1024, 16, CacheMapping.SetAssociative(4))
    val replacements = Seq(CacheReplacement.LRU, CacheReplacement.TreePLRU,
      CacheReplacement.FIFO, CacheReplacement.Random)
    val policies = for {
      read <- Seq(CacheReadMissPolicy.ReadAllocate, CacheReadMissPolicy.ReadBypass)
      write <- Seq(CacheWritePolicy.WriteBack, CacheWritePolicy.WriteThrough)
      miss <- Seq(CacheWriteMissPolicy.WriteAllocate, CacheWriteMissPolicy.NoWriteAllocate)
    } yield CachePolicy(read, write, miss)

    replacements.foreach { replacement =>
      policies.foreach { policy =>
        CacheConfig(enabled = true, geometry = geometry,
          replacement = replacement, policy = policy).validate(32, 64)
      }
    }
  }

  "NpcConfig" should "remain cache-disabled by default and expose the fixed teaching preset explicitly" in {
    val legacy = NpcConfig().validated
    assert(!legacy.cache.enabled)
    assert(!legacy.cache.l2cache.enabled)
    assert(!legacy.isa.Zifencei)

    val cached = new CacheSimulationConfig().config
    assert(cached.cache.icache.enabled)
    assert(cached.cache.dcache.enabled)
    assert(cached.cache.icache.geometry.capacityBytes == 4096)
    assert(cached.cache.dcache.geometry.lineBytes == 16)
    assert(cached.cache.dcache.policy.write == CacheWritePolicy.WriteBack)
    assert(cached.cache.dcache.policy.writeMiss == CacheWriteMissPolicy.WriteAllocate)
    assert(cached.cache.accessMode == CacheAccessMode.PipelinedTwoCycle)
    assert(cached.cache.pipelinedQueues == PipelinedCacheQueueConfig.TwoCycleLocal)
    assert(cached.cache.instructionBuffer == InstructionBufferConfig(enabled = true, entries = 8))
    assert(cached.isa.Zifencei)
  }

  it should "derive the wide-HBM L2 geometry and reject it outside its physical-memory topology" in {
    val hierarchy = CacheHierarchyConfig.WideHbmWithL2
    assert(hierarchy.icache.geometry.lineBytes == 64)
    assert(hierarchy.dcache.geometry.lineBytes == 64)
    assert(hierarchy.l2cache.enabled)
    assert(hierarchy.l2cache.geometry.capacityBytes == 256 * 1024)
    assert(hierarchy.l2cache.geometry.lineBytes == 64)
    assert(hierarchy.l2cache.geometry.ways == 8)
    assert(hierarchy.l2cache.geometry.sets == 512)

    val base = NpcConfig(
      isa = ISAConfig(xlen = 64, Zicsr = true, Zifencei = true),
      axi = AxiConfig(dataWidth = 64, useExternalMaster = true, externalDataWidth = 512),
      cache = hierarchy
    )
    assert(base.validated.memoryDataWidth == 512)
    assert(base.copy(axi = base.axi.copy(useExternalMaster = false)).validated.memoryDataWidth == 512)
    assertThrows[IllegalArgumentException](base.copy(cache = hierarchy.copy(icache = CacheConfig.Disabled)).validated)

    val localL2 = new HbmJitterL2CacheSimulationConfig().config
    assert(localL2.cache.l2cache.enabled)
    assert(!localL2.axi.useExternalMaster)
    assert(localL2.memoryDataWidth == 512)
  }

  it should "freeze every public local cache profile to two-cycle access and four queue depths" in {
    val pipelined = new PipelinedTwoCycleWideL2SimulationCoreConfig().build
    val noCompletionForwarding = new PipelinedTwoCycleWideL2NoCompletionForwardingSimulationCoreConfig().build
    val hbmL1 = new HbmJitterCacheSimulationConfig().config
    val hbmL2 = new HbmJitterL2CacheSimulationConfig().config
    assert(pipelined.cache.accessMode == CacheAccessMode.PipelinedTwoCycle)
    assert(pipelined.cache.pipelinedQueues == PipelinedCacheQueueConfig.TwoCycleLocal)
    assert(pipelined.cache.instructionBuffer == InstructionBufferConfig(enabled = true, entries = 8))
    assert(pipelined.cache.icache.geometry.lineBytes == 64)
    assert(pipelined.cache.l2cache.geometry.capacityBytes == 256 * 1024)
    assert(pipelined.memoryDataWidth == 512)
    assert(pipelined.pipeline.forwarding.enableOutstandingCompletionForwarding)
    assert(!noCompletionForwarding.pipeline.forwarding.enableOutstandingCompletionForwarding)
    assert(noCompletionForwarding.cache == pipelined.cache)
    assert(!pipelined.memory.dpiTiming.enabled)
    Seq(hbmL1, hbmL2).foreach { profile =>
      assert(profile.cache.accessMode == CacheAccessMode.PipelinedTwoCycle)
      assert(profile.cache.pipelinedQueues == PipelinedCacheQueueConfig.TwoCycleLocal)
      assert(profile.cache.instructionBuffer == InstructionBufferConfig(enabled = true, entries = 8))
      assert(profile.pipeline.integerExecuteStages == 1)
      assert(profile.pipeline.serialExecuteStages == 3)
      assert(profile.pipeline.separateSerialIntegerAlu)
      assert(!profile.pipeline.serialExecuteResultForwarding)
      assert(profile.pipeline.directIntegerWritebackBypass)
    }
    assert(NpcConfig().validated.cache.accessMode == CacheAccessMode.Blocking)
    assertThrows[IllegalArgumentException](pipelined.copy(
      axi = pipelined.axi.copy(useExternalMaster = true)).validated)
  }

  "DPI timing configuration" should "preserve the immediate default and expose the deterministic wide-L1 model" in {
    val immediate = NpcConfig().validated.memory.dpiTiming
    assert(!immediate.enabled)
    assert(immediate.minReadResponseCycles == 1)
    assert(immediate.maxWriteResponseCycles == 1)

    val hbm = new HbmJitterCacheSimulationConfig().config
    assert(hbm.cache.enabled)
    assert(!hbm.cache.l2cache.enabled)
    assert(!hbm.axi.useExternalMaster)
    assert(hbm.memoryDataWidth == 512)
    assert(hbm.memory.dpiTiming == DpiMemoryTimingConfig.HbmJitter73To81)
    assert(hbm.memory.dpiTiming.minReadResponseCycles == 73)
    assert(hbm.memory.dpiTiming.maxReadResponseCycles == 81)
    assert(hbm.memory.dpiTiming.minWriteResponseCycles == 73)
    assert(hbm.memory.dpiTiming.maxWriteResponseCycles == 81)

    assertThrows[IllegalArgumentException](DpiMemoryTimingConfig(minReadResponseCycles = 0))
    assertThrows[IllegalArgumentException](DpiMemoryTimingConfig(minReadResponseCycles = 9, maxReadResponseCycles = 8))
    assertThrows[IllegalArgumentException](DpiMemoryTimingConfig(randomSeed = 0))
  }

  it should "reject caches without Zifencei and record URAM as an FPGA-only storage choice" in {
    val hierarchy = CacheHierarchyConfig.Teaching
    assertThrows[IllegalArgumentException](NpcConfig(
      isa = ISAConfig(Zifencei = false),
      cache = hierarchy
    ).validated)
    assert(hierarchy.copy(dcache = hierarchy.dcache.copy(storage = CacheStorage.Uram)).usesUram)
  }
}
