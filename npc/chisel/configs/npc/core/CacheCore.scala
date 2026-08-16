package npc

/** NPC 缓存属性的终端配方。
  *
  * 这里仅选择 I$、D$、统一 L2、instruction buffer 和缓存访问时序；不携带 ISA、流水线、
  * 内存窗口或 [[BaseConfig]]。最终可运行终端在上层 `Configs.scala` 中将本文件的一个配方
  * 与架构、性能和集成配方显式组合。
  */

/** 教学缓存：I$/D$ 各 4 KiB、16-byte line、2-way Tree-PLRU。
  *
  * I$ 使用 read-allocate、write-through、no-write-allocate；D$ 使用 read-allocate、
  * write-back、write-allocate；instruction buffer 为 4 entries，访问模式为 blocking。
  */
class TeachingCacheConfig extends ConfigBundle(
  new WithCacheHierarchyConfig(CacheHierarchyConfig.Teaching)
)

/** 宽 HBM L1：I$/D$ 各 4 KiB、64-byte line、2-way Tree-PLRU。
  *
  * I$ 使用 read-allocate、write-through、no-write-allocate；D$ 使用 read-allocate、
  * write-back、write-allocate；单条 64-byte line 对应一个 512-bit memory beat。
  */
class WideHbmCacheConfig extends ConfigBundle(
  new WithCacheHierarchyConfig(CacheHierarchyConfig.WideHbm)
)

/** 宽 HBM L1/L2：在上述 L1 后增加共享 256 KiB、64-byte line、8-way Tree-PLRU L2。
  *
  * L2 使用 read-allocate、write-back、write-allocate；MMIO 不进入 L2，instruction buffer
  * 保持 4 entries，访问模式仍为 blocking。
  */
class WideHbmL2CacheConfig extends ConfigBundle(
  new WithCacheHierarchyConfig(CacheHierarchyConfig.WideHbmWithL2)
)

/** 本地两拍教学缓存：教学 L1 参数不变，instruction buffer 扩展为 8 entries。
  *
  * I$/D$ 的请求、响应、取指和访存队列均为 4 entries；命中在 AR 握手后的下一拍返回。
  */
class PipelinedTwoCycleTeachingCacheConfig extends ConfigBundle(
  new WithCacheHierarchyConfig(CacheHierarchyConfig.PipelinedTwoCycleTeaching)
)

/** 本地两拍宽 HBM L1：64-byte L1 line、8-entry instruction buffer 和四项本地队列。 */
class PipelinedTwoCycleWideHbmCacheConfig extends ConfigBundle(
  new WithCacheHierarchyConfig(CacheHierarchyConfig.PipelinedTwoCycleWideHbm)
)

/** 本地两拍宽 HBM L1/L2：宽 HBM L1 加共享 256 KiB、8-way unified L2。
  *
  * L1/L2 line 均为 64 bytes，instruction buffer 和请求/响应/取指/访存队列均为 8/4 项，
  * 访问模式为 `PipelinedTwoCycle`，只用于本地 DPI 仿真。
  */
class PipelinedTwoCycleWideHbmL2CacheConfig extends ConfigBundle(
  new WithCacheHierarchyConfig(CacheHierarchyConfig.PipelinedTwoCycleWideHbmWithL2)
)

/** 显式保持历史无缓存行为；仅覆盖缓存属性。 */
class WithoutCacheConfig extends ConfigBundle(
  new WithCacheHierarchyConfig(CacheHierarchyConfig.Disabled)
)
