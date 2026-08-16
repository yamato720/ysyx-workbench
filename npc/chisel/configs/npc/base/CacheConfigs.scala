package npc

/** 用完整层级覆盖缓存参数；启用任一缓存时同时启用 Zifencei。 */
class WithCacheHierarchyConfig(hierarchy: CacheHierarchyConfig) extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig = base.copy(
    isa = base.isa.copy(Zifencei = hierarchy.enabled || base.isa.Zifencei),
    cache = hierarchy
  )
}

class WithInstructionCacheConfig(cache: CacheConfig) extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig = base.copy(
    isa = base.isa.copy(Zifencei = cache.enabled || base.isa.Zifencei),
    cache = base.cache.copy(icache = cache)
  )
}

class WithDataCacheConfig(cache: CacheConfig) extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig = base.copy(
    isa = base.isa.copy(Zifencei = cache.enabled || base.isa.Zifencei),
    cache = base.cache.copy(dcache = cache)
  )
}

class WithInstructionBufferConfig(buffer: InstructionBufferConfig) extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(cache = base.cache.copy(instructionBuffer = buffer))
}
