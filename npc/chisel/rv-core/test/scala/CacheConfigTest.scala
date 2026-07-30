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
    assert(!legacy.isa.Zifencei)

    val cached = new CacheSimulationConfig().config
    assert(cached.cache.icache.enabled)
    assert(cached.cache.dcache.enabled)
    assert(cached.cache.icache.geometry.capacityBytes == 4096)
    assert(cached.cache.dcache.geometry.lineBytes == 16)
    assert(cached.cache.dcache.policy.write == CacheWritePolicy.WriteBack)
    assert(cached.cache.dcache.policy.writeMiss == CacheWriteMissPolicy.WriteAllocate)
    assert(cached.cache.instructionBuffer == InstructionBufferConfig(enabled = true, entries = 4))
    assert(cached.isa.Zifencei)
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
