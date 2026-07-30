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
  * 缓存只覆盖 main-memory 窗口，MMIO 始终旁路；启用缓存会同时启用 Zifencei：
  * FENCE 会 drain D$，FENCE.I 则会再失效 I$。
  */

/** 本地教学缓存核心；默认 SimulationConfig 保持完全无缓存。 */
class CacheSimulationCoreConfig extends ConfigBundle(
  new WithTeachingCacheConfig ++
    new SimulationCoreConfig
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
