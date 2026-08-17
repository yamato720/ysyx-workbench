package npc

/** NPC 缓存属性的终端配方。
  *
  * 这里仅选择 I$、D$、统一 L2、instruction buffer 和缓存访问时序；不携带 ISA、流水线、
  * 内存窗口或 [[BaseConfig]]。启用任一缓存时同时声明缓存计数信号；引出由
  * `++ TraceConfig` / `++ FinalLogConfig` 决定。最终可运行终端在上层
  * `Configs.scala` 中将本文件的一个配方与架构、性能和集成配方显式组合。
  */

/** 缓存的命中/缺失/回填计数硬件依赖。 */
class CacheLogConfig extends ConfigBundle(
  new WithCacheLogConfig
)

/** 教学缓存：I$/D$ 各 4 KiB、16-byte line、2-way Tree-PLRU。
  *
  * I$ 使用 read-allocate、write-through、no-write-allocate；D$ 使用 read-allocate、
  * write-back、write-allocate；instruction buffer 为 4 entries，访问模式为 blocking。
  */
class TeachingCacheConfig extends ConfigBundle(
  new CacheLogConfig ++
    new WithL2CacheConfig(CacheConfig(enabled = false)) ++
    new WithPipelinedCacheQueuesConfig(PipelinedCacheQueueConfig.Blocking) ++
    new WithCacheAccessModeConfig(CacheAccessMode.Blocking) ++
    new WithInstructionBufferConfig(InstructionBufferConfig(enabled = true, entries = 4)) ++
    new WithDataCacheConfig(CacheConfig(
      enabled = true,
      geometry = CacheGeometry(4096, 16, CacheMapping.SetAssociative(2)),
      replacement = CacheReplacement.TreePLRU,
      policy = CachePolicy(
        readMiss = CacheReadMissPolicy.ReadAllocate,
        write = CacheWritePolicy.WriteBack,
        writeMiss = CacheWriteMissPolicy.WriteAllocate
      ),
      storage = CacheStorage.Auto
    )) ++
    new WithInstructionCacheConfig(CacheConfig(
      enabled = true,
      geometry = CacheGeometry(4096, 16, CacheMapping.SetAssociative(2)),
      replacement = CacheReplacement.TreePLRU,
      policy = CachePolicy(
        readMiss = CacheReadMissPolicy.ReadAllocate,
        write = CacheWritePolicy.WriteThrough,
        writeMiss = CacheWriteMissPolicy.NoWriteAllocate
      ),
      storage = CacheStorage.Auto
    ))
)

/** 宽 HBM L1：I$/D$ 各 4 KiB、64-byte line、2-way Tree-PLRU。
  *
  * I$ 使用 read-allocate、write-through、no-write-allocate；D$ 使用 read-allocate、
  * write-back、write-allocate；单条 64-byte line 对应一个 512-bit memory beat。
  */
class WideHbmCacheConfig extends ConfigBundle(
  new CacheLogConfig ++
    new WithL2CacheConfig(CacheConfig(enabled = false)) ++
    new WithPipelinedCacheQueuesConfig(PipelinedCacheQueueConfig.Blocking) ++
    new WithCacheAccessModeConfig(CacheAccessMode.Blocking) ++
    new WithInstructionBufferConfig(InstructionBufferConfig(enabled = true, entries = 4)) ++
    new WithDataCacheConfig(CacheConfig(
      enabled = true,
      geometry = CacheGeometry(4096, 64, CacheMapping.SetAssociative(2)),
      replacement = CacheReplacement.TreePLRU,
      policy = CachePolicy(
        readMiss = CacheReadMissPolicy.ReadAllocate,
        write = CacheWritePolicy.WriteBack,
        writeMiss = CacheWriteMissPolicy.WriteAllocate
      ),
      storage = CacheStorage.Auto
    )) ++
    new WithInstructionCacheConfig(CacheConfig(
      enabled = true,
      geometry = CacheGeometry(4096, 64, CacheMapping.SetAssociative(2)),
      replacement = CacheReplacement.TreePLRU,
      policy = CachePolicy(
        readMiss = CacheReadMissPolicy.ReadAllocate,
        write = CacheWritePolicy.WriteThrough,
        writeMiss = CacheWriteMissPolicy.NoWriteAllocate
      ),
      storage = CacheStorage.Auto
    ))
)

/** 宽 HBM L1/L2：在上述 L1 后增加共享 256 KiB、64-byte line、8-way Tree-PLRU L2。
  *
  * L2 使用 read-allocate、write-back、write-allocate；MMIO 不进入 L2，instruction buffer
  * 保持 4 entries，访问模式仍为 blocking。
  */
class WideHbmL2CacheConfig extends ConfigBundle(
  new CacheLogConfig ++
    new WithL2CacheConfig(CacheConfig(
      enabled = true,
      geometry = CacheGeometry(256 * 1024, 64, CacheMapping.SetAssociative(8)),
      replacement = CacheReplacement.TreePLRU,
      policy = CachePolicy(
        readMiss = CacheReadMissPolicy.ReadAllocate,
        write = CacheWritePolicy.WriteBack,
        writeMiss = CacheWriteMissPolicy.WriteAllocate
      ),
      storage = CacheStorage.Auto
    )) ++
    new WithPipelinedCacheQueuesConfig(PipelinedCacheQueueConfig.Blocking) ++
    new WithCacheAccessModeConfig(CacheAccessMode.Blocking) ++
    new WithInstructionBufferConfig(InstructionBufferConfig(enabled = true, entries = 4)) ++
    new WithDataCacheConfig(CacheConfig(
      enabled = true,
      geometry = CacheGeometry(4096, 64, CacheMapping.SetAssociative(2)),
      replacement = CacheReplacement.TreePLRU,
      policy = CachePolicy(
        readMiss = CacheReadMissPolicy.ReadAllocate,
        write = CacheWritePolicy.WriteBack,
        writeMiss = CacheWriteMissPolicy.WriteAllocate
      ),
      storage = CacheStorage.Auto
    )) ++
    new WithInstructionCacheConfig(CacheConfig(
      enabled = true,
      geometry = CacheGeometry(4096, 64, CacheMapping.SetAssociative(2)),
      replacement = CacheReplacement.TreePLRU,
      policy = CachePolicy(
        readMiss = CacheReadMissPolicy.ReadAllocate,
        write = CacheWritePolicy.WriteThrough,
        writeMiss = CacheWriteMissPolicy.NoWriteAllocate
      ),
      storage = CacheStorage.Auto
    ))
)

/** 本地两拍教学缓存：教学 L1 参数不变，instruction buffer 扩展为 8 entries。
  *
  * I$/D$ 的请求、响应、取指和访存队列均为 4 entries；命中在 AR 握手后的下一拍返回。
  */
class PipelinedTwoCycleTeachingCacheConfig extends ConfigBundle(
  new CacheLogConfig ++
    new WithL2CacheConfig(CacheConfig(enabled = false)) ++
    new WithPipelinedCacheQueuesConfig(PipelinedCacheQueueConfig.TwoCycleLocal) ++
    new WithCacheAccessModeConfig(CacheAccessMode.PipelinedTwoCycle) ++
    new WithInstructionBufferConfig(InstructionBufferConfig(enabled = true, entries = 8)) ++
    new WithDataCacheConfig(CacheConfig(
      enabled = true,
      geometry = CacheGeometry(4096, 16, CacheMapping.SetAssociative(2)),
      replacement = CacheReplacement.TreePLRU,
      policy = CachePolicy(
        readMiss = CacheReadMissPolicy.ReadAllocate,
        write = CacheWritePolicy.WriteBack,
        writeMiss = CacheWriteMissPolicy.WriteAllocate
      ),
      storage = CacheStorage.Auto
    )) ++
    new WithInstructionCacheConfig(CacheConfig(
      enabled = true,
      geometry = CacheGeometry(4096, 16, CacheMapping.SetAssociative(2)),
      replacement = CacheReplacement.TreePLRU,
      policy = CachePolicy(
        readMiss = CacheReadMissPolicy.ReadAllocate,
        write = CacheWritePolicy.WriteThrough,
        writeMiss = CacheWriteMissPolicy.NoWriteAllocate
      ),
      storage = CacheStorage.Auto
    ))
)

/** 本地两拍宽 HBM L1：64-byte L1 line、8-entry instruction buffer 和四项本地队列。 */
class PipelinedTwoCycleWideHbmCacheConfig extends ConfigBundle(
  new CacheLogConfig ++
    new WithL2CacheConfig(CacheConfig(enabled = false)) ++
    new WithPipelinedCacheQueuesConfig(PipelinedCacheQueueConfig.TwoCycleLocal) ++
    new WithCacheAccessModeConfig(CacheAccessMode.PipelinedTwoCycle) ++
    new WithInstructionBufferConfig(InstructionBufferConfig(enabled = true, entries = 8)) ++
    new WithDataCacheConfig(CacheConfig(
      enabled = true,
      geometry = CacheGeometry(4096, 64, CacheMapping.SetAssociative(2)),
      replacement = CacheReplacement.TreePLRU,
      policy = CachePolicy(
        readMiss = CacheReadMissPolicy.ReadAllocate,
        write = CacheWritePolicy.WriteBack,
        writeMiss = CacheWriteMissPolicy.WriteAllocate
      ),
      storage = CacheStorage.Auto
    )) ++
    new WithInstructionCacheConfig(CacheConfig(
      enabled = true,
      geometry = CacheGeometry(4096, 64, CacheMapping.SetAssociative(2)),
      replacement = CacheReplacement.TreePLRU,
      policy = CachePolicy(
        readMiss = CacheReadMissPolicy.ReadAllocate,
        write = CacheWritePolicy.WriteThrough,
        writeMiss = CacheWriteMissPolicy.NoWriteAllocate
      ),
      storage = CacheStorage.Auto
    ))
)

/** 本地两拍宽 HBM L1/L2：宽 HBM L1 加共享 256 KiB、8-way unified L2。
  *
  * L1/L2 line 均为 64 bytes，instruction buffer 和请求/响应/取指/访存队列均为 8/4 项，
  * 访问模式为 `PipelinedTwoCycle`，只用于本地 DPI 仿真。
  */
class PipelinedTwoCycleWideHbmL2CacheConfig extends ConfigBundle(
  new CacheLogConfig ++
    new WithL2CacheConfig(CacheConfig(
      enabled = true,
      geometry = CacheGeometry(256 * 1024, 64, CacheMapping.SetAssociative(8)),
      replacement = CacheReplacement.TreePLRU,
      policy = CachePolicy(
        readMiss = CacheReadMissPolicy.ReadAllocate,
        write = CacheWritePolicy.WriteBack,
        writeMiss = CacheWriteMissPolicy.WriteAllocate
      ),
      storage = CacheStorage.Auto
    )) ++
    new WithPipelinedCacheQueuesConfig(PipelinedCacheQueueConfig.TwoCycleLocal) ++
    new WithCacheAccessModeConfig(CacheAccessMode.PipelinedTwoCycle) ++
    new WithInstructionBufferConfig(InstructionBufferConfig(enabled = true, entries = 8)) ++
    new WithDataCacheConfig(CacheConfig(
      enabled = true,
      geometry = CacheGeometry(4096, 64, CacheMapping.SetAssociative(2)),
      replacement = CacheReplacement.TreePLRU,
      policy = CachePolicy(
        readMiss = CacheReadMissPolicy.ReadAllocate,
        write = CacheWritePolicy.WriteBack,
        writeMiss = CacheWriteMissPolicy.WriteAllocate
      ),
      storage = CacheStorage.Auto
    )) ++
    new WithInstructionCacheConfig(CacheConfig(
      enabled = true,
      geometry = CacheGeometry(4096, 64, CacheMapping.SetAssociative(2)),
      replacement = CacheReplacement.TreePLRU,
      policy = CachePolicy(
        readMiss = CacheReadMissPolicy.ReadAllocate,
        write = CacheWritePolicy.WriteThrough,
        writeMiss = CacheWriteMissPolicy.NoWriteAllocate
      ),
      storage = CacheStorage.Auto
    ))
)

/** 显式保持历史无缓存行为；仅覆盖缓存属性。 */
class WithoutCacheConfig extends ConfigBundle(
  new CacheLogConfig ++
    new WithL2CacheConfig(CacheConfig(enabled = false)) ++
    new WithDataCacheConfig(CacheConfig(enabled = false)) ++
    new WithInstructionCacheConfig(CacheConfig(enabled = false)) ++
    new WithInstructionBufferConfig(InstructionBufferConfig(enabled = false, entries = 1)) ++
    new WithPipelinedCacheQueuesConfig(PipelinedCacheQueueConfig.Blocking) ++
    new WithCacheAccessModeConfig(CacheAccessMode.Blocking)
)
