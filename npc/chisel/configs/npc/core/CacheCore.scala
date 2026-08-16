package npc

/**
  * 缓存核心配置总览。
  *
  * 本文件的完整核心均选用 [[WithTeachingCacheConfig]]。要创建其他缓存组合，在终端
  * Config 的左侧叠加下列 base 片段即可覆盖该预设（CDE 的左侧优先）：
  *
  *   - [[WithCacheHierarchyConfig]]：一次设置 I$、D$ 与 instruction buffer；
  *   - [[WithInstructionCacheConfig]]、[[WithDataCacheConfig]]：分别覆盖 I$/D$；
  *   - [[WithInstructionBufferConfig]]：设置顺序取指缓冲；
  *   - [[WithoutCacheConfig]]：显式恢复历史无缓存行为。
  *
  * 每个 [[CacheConfig]] 可独立启用，并由以下参数组成：
  *
  *   - 几何 [[CacheGeometry]]：容量、line 字节数和映射方式；映射可为 direct-mapped、
  *     set-associative(ways) 或 fully-associative，sets、tag/index/offset 位数自动推导；
  *   - 替换策略 [[CacheReplacement]]：LRU、Tree-PLRU、FIFO 或固定种子的确定性 Random；
  *   - [[CachePolicy]]：read-allocate/read-bypass、write-back/write-through、
  *     write-allocate/no-write-allocate（I$ 只读，写策略仅对 D$ 有效）；
  *   - 存储 [[CacheStorage]]：Auto、Registers 或 Uram；Uram 仅允许 FPGA 构造；
  *   - [[InstructionBufferConfig]]：是否启用及 entries 数。
  *
  * 可复制的重载示例如下。示例核心应放在 `core/`，公开的无参终端仍放在根部
  * `Configs.scala`；每个 `++` 链的左侧均覆盖右侧的教学预设。
  *
  * 1. 增大 I$ 并将其改为四路 LRU，同时将顺序取指缓冲扩展至八项：
  *
  * {{{
  * class FourWayLruCacheSimulationCore extends ConfigBundle(
  *   new WithInstructionCacheConfig(CacheConfig(
  *     enabled = true,
  *     geometry = CacheGeometry(8192, 32, CacheMapping.SetAssociative(4)),
  *     replacement = CacheReplacement.LRU,
  *     storage = CacheStorage.Auto
  *   )) ++
  *     new WithInstructionBufferConfig(InstructionBufferConfig(enabled = true, entries = 8)) ++
  *     new CacheSimulationCoreConfig
  * )
  * }}}
  *
  * 2. 为流式写入实验保留教学 I$，但将 D$ 改为寄存器实现的 FIFO、write-through、
  *    no-write-allocate，并让 read miss 直接旁路：
  *
  * {{{
  * class StreamingWriteThroughSimulationCore extends ConfigBundle(
  *   new WithDataCacheConfig(CacheConfig(
  *     enabled = true,
  *     geometry = CacheGeometry(4096, 16, CacheMapping.SetAssociative(2)),
  *     replacement = CacheReplacement.FIFO,
  *     policy = CachePolicy(
  *       readMiss = CacheReadMissPolicy.ReadBypass,
  *       write = CacheWritePolicy.WriteThrough,
  *       writeMiss = CacheWriteMissPolicy.NoWriteAllocate
  *     ),
  *     storage = CacheStorage.Registers
  *   )) ++
  *     new CacheSimulationCoreConfig
  * )
  * }}}
  *
  * 3. 为替换策略实验设置一个小型全相联 I$；Random 使用固定复位种子，因而仿真可复现：
  *
  * {{{
  * class FullyAssociativeRandomSimulationCore extends ConfigBundle(
  *   new WithInstructionCacheConfig(CacheConfig(
  *     enabled = true,
  *     geometry = CacheGeometry(1024, 16, CacheMapping.FullyAssociative),
  *     replacement = CacheReplacement.Random,
  *     storage = CacheStorage.Registers
  *   )) ++
  *     new CacheSimulationCoreConfig
  * )
  * }}}
  *
  * 例如将第一个配方作为本地可运行终端时，在 `npc/Configs.scala` 中只挂载该完整
  * core：
  *
  * {{{
  * class FourWayLruCacheSimulationConfig extends ConstructionConfig(
  *   new FourWayLruCacheSimulationCore
  * ) with LocalNpcTerminal with NemuSimulationIpTerminal
  * }}}
  *
  * FPGA 配方可采用相同的 `WithInstructionCacheConfig`/`WithDataCacheConfig` 覆盖方式；
  * 仅在该 FPGA 核心配方中把 `storage` 改为 `CacheStorage.Uram`，并以新的 FPGA 终端执行
  * `make -C npc rebuild config=<Config>`。
  *
  * 教学预设为 I$/D$ 各 4 KiB、16-byte line、2-way、Tree-PLRU；D$ 使用
  * write-back + write-allocate，顺序取指缓冲为 4 entries。缓存容量、line、路数和
  * instruction-buffer entries 必须为 2 的幂，line 还须为 AXI 数据字节数的整数倍。
  * `WithWideHbmCacheConfig` 是 U55C 的独立预设：I$/D$ 各 4 KiB、64-byte line、2-way，
  * 与 `new WithExternalAxiConfig(externalDataWidth = 512)` 组合后，一条 cache line 对应一个
  * 512-bit HBM AXI beat。缓存只覆盖 main-memory 窗口，MMIO 始终以 CPU 宽度访问并在控制器
  * 中做 lane 适配；启用缓存会同时启用 Zifencei：
  * FENCE 会 drain D$，FENCE.I 则会再失效 I$。
  */

/** 本地教学缓存核心；默认 SimulationConfig 保持完全无缓存。 */
class CacheSimulationCoreConfig extends ConfigBundle(
  new WithPipelinedTwoCycleTeachingCacheConfig ++
    new SimulationCoreConfig
)

/**
  * U55C 宽 L1 基线的本地功能时序对照构造。
  *
  * I$/D$ 通过本地 512-bit DPI cache-memory port 使用 64-byte line。每次完整 line
  * 主存访问的响应延迟固定落在 73--81 cycle，因此一次 line refill 是一个延迟事务，
  * 而不是八次串行的 64-bit 读。本构造有意只包含 L1，不模拟可选共享 L2 或 HBM bank/queue
  * 竞争。
  */
class HbmJitterCacheSimulationCoreConfig extends ConfigBundle(
  new WithNpcOutstandingCompletionForwardingConfig ++
  new WithDpiMemoryTimingConfig(DpiMemoryTimingConfig.HbmJitter73To81) ++
    new WithLocalDpiCacheMemoryWidthConfig(512) ++
    new WithPipelinedTwoCycleWideHbmCacheConfig ++
    new Rv64IMZicsrConfig ++
    new PipelineDualFwdOneStageIntegerExecuteDirectWritebackRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecutePerformConfig ++
    new WithTopDebugConfig ++
    new WithFpgaMainMemoryConfig ++
    new BaseConfig
)

/** 启用共享 256 KiB HBM 风格 L2 的本地功能时序对照构造。 */
class HbmJitterL2CacheSimulationCoreConfig extends ConfigBundle(
  new WithNpcOutstandingCompletionForwardingConfig ++
  new WithDpiMemoryTimingConfig(DpiMemoryTimingConfig.HbmJitter73To81) ++
    new WithLocalDpiCacheMemoryWidthConfig(512) ++
    new WithPipelinedTwoCycleWideHbmL2CacheConfig ++
    new Rv64IMZicsrConfig ++
    new PipelineDualFwdOneStageIntegerExecuteDirectWritebackRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecutePerformConfig ++
    new WithTopDebugConfig ++
    new WithFpgaMainMemoryConfig ++
    new BaseConfig
)

/**
  * 本地两拍 L1/L2 仿真：64-byte L1/L2 line 与 512-bit DPI 主存端口一一对应。
  * 命中在 S0 同步读出的后续一拍返回；miss、MMIO 和维护仍以按序阻塞方式完成。
  */
class PipelinedTwoCycleWideL2SimulationCoreConfig extends ConfigBundle(
  new WithNpcOutstandingCompletionForwardingConfig ++
    new WithDpiMemoryTimingConfig(DpiMemoryTimingConfig.Immediate) ++
    new WithLocalDpiCacheMemoryWidthConfig(512) ++
    new WithPipelinedTwoCycleWideHbmL2CacheConfig ++
    new Rv64IMZicsrConfig ++
    new PipelineDualFwdTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecutePerformConfig ++
    new WithTopDebugConfig ++
    new WithFpgaMainMemoryConfig ++
    new BaseConfig
)

/** 与两拍 L1/L2 端点同层级的关闭版，用于完成表前递 A/B 对照。 */
class PipelinedTwoCycleWideL2NoCompletionForwardingSimulationCoreConfig extends ConfigBundle(
  new WithoutNpcOutstandingCompletionForwardingConfig ++
    new PipelinedTwoCycleWideL2SimulationCoreConfig
)

/** ysyxSoC 使用的缓存版 RV32 外部 AXI 核心。 */
class CacheExternalAxiConfig extends ConfigBundle(
  new WithTeachingCacheConfig ++
    new ExternalAxiConfig
)

/** 默认 RV32 FPGA 核心的缓存版本。 */
class CacheFpgaConfig extends ConstructionConfig(
  new WithTeachingCacheConfig ++
    new Rv32IMZicsrConfig ++
    new PipelineDualFwdPerformConfig ++
    new WithExternalAxiConfig ++
    new WithDispatchControlConfig ++
    new WithTopDebugConfig ++
    new WithFpgaMainMemoryConfig ++
    new BaseConfig
)

/** U55C RV64 时序核心的缓存版本。 */
class CacheRv64PipelineDualForwardingTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecuteFpgaConfig
  extends ConstructionConfig(
    new WithTeachingCacheConfig ++
      new Rv64IMZicsrConfig ++
      new PipelineDualFwdTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecutePerformConfig ++
      new WithExternalAxiConfig ++
      new WithDispatchControlConfig ++
      new WithTopDebugConfig ++
      new WithFpgaMainMemoryConfig ++
      new BaseConfig
  )

/** RV64 U55C HBM 核心：64-byte cache line 与 512-bit 外部 AXI master。 */
class WideHbmCacheRv64PipelineDualForwardingTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecuteFpgaConfig
  extends ConstructionConfig(
    new WithWideHbmCacheConfig ++
      new Rv64IMZicsrConfig ++
      new PipelineDualFwdTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecutePerformConfig ++
      new WithExternalAxiConfig(externalDataWidth = 512) ++
      new WithDispatchControlConfig ++
      new WithTopDebugConfig ++
      new WithFpgaMainMemoryConfig ++
      new BaseConfig
  )

/** RV64 U55C HBM 核心：在 64-byte L1 line 之后增加共享 L2。
  *
  * `WithWideHbmL2CacheConfig` 在 Fabric 的 I$/D$ arbiter 之后、AXI4-Full 之前增加
  * 一个 256 KiB、8-way Tree-PLRU、write-back、write-allocate L2。因此它只观察
  * main-memory 流量，host MMIO slave 位于其外。FENCE/FENCE.I 和 FPGA 完成 drain
  * 都先 flush D$，再 flush 此 L2，之后核心才可以继续或复位。
  */
class WideHbmL2CacheRv64PipelineDualForwardingTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecuteFpgaConfig
  extends ConstructionConfig(
    new WithWideHbmL2CacheConfig ++
      new Rv64IMZicsrConfig ++
      new PipelineDualFwdTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecutePerformConfig ++
      new WithExternalAxiConfig(externalDataWidth = 512) ++
      new WithDispatchControlConfig ++
      new WithTopDebugConfig ++
      new WithFpgaMainMemoryConfig ++
      new BaseConfig
  )
