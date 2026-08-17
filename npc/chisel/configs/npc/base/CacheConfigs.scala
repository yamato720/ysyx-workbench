package npc

/** 用完整层级覆盖缓存参数；启用任一缓存时同时启用 Zifencei。 */
class WithCacheHierarchyConfig(hierarchy: CacheHierarchyConfig) extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig = base.copy(
    isa = base.isa.copy(Zifencei = hierarchy.enabled || base.isa.Zifencei),
    cache = hierarchy.copy(cacheLog = hierarchy.enabled)
  )
}

class WithInstructionCacheConfig(cache: CacheConfig) extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig = {
    val next = base.cache.copy(icache = cache)
    base.copy(
      isa = base.isa.copy(Zifencei = cache.enabled || base.isa.Zifencei),
      cache = next.copy(cacheLog = next.enabled)
    )
  }
}

class WithDataCacheConfig(cache: CacheConfig) extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig = {
    val next = base.cache.copy(dcache = cache)
    base.copy(
      isa = base.isa.copy(Zifencei = cache.enabled || base.isa.Zifencei),
      cache = next.copy(cacheLog = next.enabled)
    )
  }
}

class WithL2CacheConfig(cache: CacheConfig) extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig = {
    val next = base.cache.copy(l2cache = cache)
    base.copy(
      isa = base.isa.copy(Zifencei = cache.enabled || base.isa.Zifencei),
      cache = next.copy(cacheLog = next.enabled)
    )
  }
}

/** 缓存的命中/缺失/回填计数硬件依赖。 */
class WithCacheLogConfig extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(cache = base.cache.copy(cacheLog = base.cache.enabled))
}

class WithInstructionBufferConfig(buffer: InstructionBufferConfig) extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(cache = base.cache.copy(instructionBuffer = buffer))
}

class WithCacheAccessModeConfig(accessMode: CacheAccessMode) extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(cache = base.cache.copy(accessMode = accessMode))
}

class WithPipelinedCacheQueuesConfig(queues: PipelinedCacheQueueConfig) extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(cache = base.cache.copy(pipelinedQueues = queues))
}
