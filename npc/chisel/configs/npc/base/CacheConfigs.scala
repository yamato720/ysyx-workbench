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

/** 教学预设：4 KiB/16 B/2-way I$ 与 D$，Tree-PLRU，D$ write-back/write-allocate。 */
class WithTeachingCacheConfig extends ConfigBundle(
  new WithCacheHierarchyConfig(CacheHierarchyConfig.Teaching)
)

/** U55C HBM 预设：64-byte line 的填充或写回各使用一个 512-bit memory beat。 */
class WithWideHbmCacheConfig extends ConfigBundle(
  new WithCacheHierarchyConfig(CacheHierarchyConfig.WideHbm)
)

/** U55C HBM 预设：在 64-byte I$/D$ line 之后增加共享 L2。 */
class WithWideHbmL2CacheConfig extends ConfigBundle(
  new WithCacheHierarchyConfig(CacheHierarchyConfig.WideHbmWithL2)
)

/** 本地两拍 L1/L2：请求、响应、取指和访存队列均固定为四项。 */
class WithPipelinedTwoCycleWideHbmL2CacheConfig extends ConfigBundle(
  new WithCacheHierarchyConfig(CacheHierarchyConfig.PipelinedTwoCycleWideHbmWithL2)
)

/** 保持历史无缓存行为的显式片段。 */
class WithoutCacheConfig extends ConfigBundle(
  new WithCacheHierarchyConfig(CacheHierarchyConfig.Disabled)
)
