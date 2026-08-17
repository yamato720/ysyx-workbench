package npc

private[npc] object PowerOfTwo {
  def apply(value: Int): Boolean = value > 0 && (value & (value - 1)) == 0
}

sealed trait CacheMapping {
  private[npc] def ways(lines: Int): Int
  def name: String
}

object CacheMapping {
  case object DirectMapped extends CacheMapping {
    override private[npc] def ways(lines: Int): Int = 1
    override val name: String = "direct"
  }

  final case class SetAssociative(wayCount: Int) extends CacheMapping {
    require(PowerOfTwo(wayCount), s"cache ways must be a positive power of two, got $wayCount")
    override private[npc] def ways(lines: Int): Int = wayCount
    override val name: String = s"set-associative-$wayCount"
  }

  case object FullyAssociative extends CacheMapping {
    override private[npc] def ways(lines: Int): Int = lines
    override val name: String = "fully-associative"
  }
}

sealed trait CacheReplacement { def name: String }
object CacheReplacement {
  case object LRU extends CacheReplacement { override val name: String = "lru" }
  case object TreePLRU extends CacheReplacement { override val name: String = "tree-plru" }
  case object FIFO extends CacheReplacement { override val name: String = "fifo" }
  /** Hardware uses a fixed reset seed, so identical request streams choose identical victims. */
  case object Random extends CacheReplacement { override val name: String = "random-deterministic" }
}

sealed trait CacheReadMissPolicy { def name: String }
object CacheReadMissPolicy {
  case object ReadAllocate extends CacheReadMissPolicy { override val name: String = "read-allocate" }
  case object ReadBypass extends CacheReadMissPolicy { override val name: String = "read-bypass" }
}

sealed trait CacheWritePolicy { def name: String }
object CacheWritePolicy {
  case object WriteBack extends CacheWritePolicy { override val name: String = "write-back" }
  case object WriteThrough extends CacheWritePolicy { override val name: String = "write-through" }
}

sealed trait CacheWriteMissPolicy { def name: String }
object CacheWriteMissPolicy {
  case object WriteAllocate extends CacheWriteMissPolicy { override val name: String = "write-allocate" }
  case object NoWriteAllocate extends CacheWriteMissPolicy { override val name: String = "no-write-allocate" }
}

final case class CachePolicy(
  readMiss: CacheReadMissPolicy = CacheReadMissPolicy.ReadAllocate,
  write: CacheWritePolicy = CacheWritePolicy.WriteBack,
  writeMiss: CacheWriteMissPolicy = CacheWriteMissPolicy.WriteAllocate
)

sealed trait CacheStorage { def name: String }
object CacheStorage {
  case object Auto extends CacheStorage { override val name: String = "auto" }
  case object Registers extends CacheStorage { override val name: String = "registers" }
  case object Uram extends CacheStorage { override val name: String = "uram" }
}

/** 缓存前端的请求接受与命中响应时序。 */
sealed trait CacheAccessMode { def name: String }
object CacheAccessMode {
  /** 保持历史单 MSHR、一次只接受一笔 CPU 请求的行为。 */
  case object Blocking extends CacheAccessMode { override val name: String = "blocking" }
  /** 命中请求由 S0 发起同步阵列读，下一拍直接比较阵列输出并按序返回。 */
  case object PipelinedTwoCycle extends CacheAccessMode {
    override val name: String = "pipelined-two-cycle"
  }
}

/** 两拍本地仿真冻结的 FIFO 深度。 */
final case class PipelinedCacheQueueConfig(
  requestDepth: Int = 1,
  responseDepth: Int = 1,
  fetchDepth: Int = 1,
  memoryDepth: Int = 1
) {
  private val values = Seq(requestDepth, responseDepth, fetchDepth, memoryDepth)
  require(values.forall(value => PowerOfTwo(value)),
    s"cache queue depths must be positive powers of two, got ${values.mkString(",")}")
}

object PipelinedCacheQueueConfig {
  val Blocking: PipelinedCacheQueueConfig = PipelinedCacheQueueConfig()
  /** 本地两拍 Config 以四项固定的四深度队列冻结其可见 ABI。 */
  val TwoCycleLocal: PipelinedCacheQueueConfig = PipelinedCacheQueueConfig(4, 4, 4, 4)
}

final case class CacheGeometry(
  capacityBytes: Int,
  lineBytes: Int,
  mapping: CacheMapping
) {
  require(PowerOfTwo(capacityBytes),
    s"cache capacityBytes must be a positive power of two, got $capacityBytes")
  require(PowerOfTwo(lineBytes),
    s"cache lineBytes must be a positive power of two, got $lineBytes")
  require(capacityBytes >= lineBytes,
    s"cache capacityBytes ($capacityBytes) must be at least lineBytes ($lineBytes)")

  val lines: Int = capacityBytes / lineBytes
  val ways: Int = mapping.ways(lines)
  require(ways <= lines, s"cache ways ($ways) cannot exceed total lines ($lines)")
  require(PowerOfTwo(ways), s"cache ways must be a power of two, got $ways")
  val sets: Int = lines / ways
  require(PowerOfTwo(sets), s"cache sets must be a power of two, got $sets")

  val offsetBits: Int = Integer.numberOfTrailingZeros(lineBytes)
  val indexBits: Int = Integer.numberOfTrailingZeros(sets)

  def tagBits(addressWidth: Int): Int = {
    require(addressWidth > offsetBits + indexBits,
      s"cache address width $addressWidth is too small for offset=$offsetBits and index=$indexBits")
    addressWidth - offsetBits - indexBits
  }

  def validateBus(dataWidth: Int): Unit = {
    require(dataWidth > 0 && dataWidth % 8 == 0,
      s"cache bus data width must contain whole bytes, got $dataWidth")
    val busBytes = dataWidth / 8
    require(lineBytes % busBytes == 0,
      s"cache lineBytes ($lineBytes) must be an integer multiple of bus bytes ($busBytes)")
  }
}

final case class CacheConfig(
  enabled: Boolean = false,
  geometry: CacheGeometry = CacheGeometry(4096, 16, CacheMapping.SetAssociative(2)),
  replacement: CacheReplacement = CacheReplacement.TreePLRU,
  policy: CachePolicy = CachePolicy(),
  storage: CacheStorage = CacheStorage.Auto
) {
  def validate(addressWidth: Int, dataWidth: Int): Unit = if (enabled) {
    geometry.validateBus(dataWidth)
    geometry.tagBits(addressWidth)
    if (replacement == CacheReplacement.TreePLRU) {
      require(PowerOfTwo(geometry.ways),
        s"TreePLRU requires a power-of-two way count, got ${geometry.ways}")
    }
  }

  /** cache 可以使用比 CPU-side AXI-Lite port 更宽的 line-memory port。 */
  def validateMemoryBus(dataWidth: Int): Unit = if (enabled) geometry.validateBus(dataWidth)
}

object CacheConfig {
  val Disabled: CacheConfig = CacheConfig()
}

final case class InstructionBufferConfig(enabled: Boolean = false, entries: Int = 1) {
  require(!enabled || PowerOfTwo(entries),
    s"instruction buffer entries must be a positive power of two, got $entries")
}

final case class CacheHierarchyConfig(
  icache: CacheConfig = CacheConfig.Disabled,
  dcache: CacheConfig = CacheConfig.Disabled,
  l2cache: CacheConfig = CacheConfig.Disabled,
  instructionBuffer: InstructionBufferConfig = InstructionBufferConfig(),
  accessMode: CacheAccessMode = CacheAccessMode.Blocking,
  pipelinedQueues: PipelinedCacheQueueConfig = PipelinedCacheQueueConfig.Blocking,
  cacheLog: Boolean = false
) {
  def enabled: Boolean = icache.enabled || dcache.enabled || l2cache.enabled
  def usesUram: Boolean = Seq(icache, dcache, l2cache).exists(cache =>
    cache.enabled && cache.storage == CacheStorage.Uram)

  def validate(addressWidth: Int, dataWidth: Int, isa: ISAConfig): Unit = {
    icache.validate(addressWidth, dataWidth)
    dcache.validate(addressWidth, dataWidth)
    l2cache.validate(addressWidth, dataWidth)
    require(!enabled || isa.Zifencei, "enabled caches require the RISC-V Zifencei extension")
    require(!l2cache.enabled || (icache.enabled && dcache.enabled),
      "a unified L2 cache requires both I$ and D$ to be enabled")
    require(accessMode == CacheAccessMode.PipelinedTwoCycle ||
      pipelinedQueues == PipelinedCacheQueueConfig.Blocking,
      "blocking caches must retain single-entry queue defaults")
  }
}

object CacheHierarchyConfig {
  val Disabled: CacheHierarchyConfig = CacheHierarchyConfig()

  val Teaching: CacheHierarchyConfig = CacheHierarchyConfig(
    icache = CacheConfig(
      enabled = true,
      geometry = CacheGeometry(4096, 16, CacheMapping.SetAssociative(2)),
      replacement = CacheReplacement.TreePLRU,
      policy = CachePolicy(
        readMiss = CacheReadMissPolicy.ReadAllocate,
        write = CacheWritePolicy.WriteThrough,
        writeMiss = CacheWriteMissPolicy.NoWriteAllocate
      )
    ),
    dcache = CacheConfig(
      enabled = true,
      geometry = CacheGeometry(4096, 16, CacheMapping.SetAssociative(2)),
      replacement = CacheReplacement.TreePLRU,
      policy = CachePolicy(
        readMiss = CacheReadMissPolicy.ReadAllocate,
        write = CacheWritePolicy.WriteBack,
        writeMiss = CacheWriteMissPolicy.WriteAllocate
      )
    ),
    instructionBuffer = InstructionBufferConfig(enabled = true, entries = 4)
  )

  /** U55C HBM 预设：一个完整 512-bit memory beat 填充一条 64-byte cache line。 */
  val WideHbm: CacheHierarchyConfig = CacheHierarchyConfig(
    icache = CacheConfig(
      enabled = true,
      geometry = CacheGeometry(4096, 64, CacheMapping.SetAssociative(2)),
      replacement = CacheReplacement.TreePLRU,
      policy = CachePolicy(
        readMiss = CacheReadMissPolicy.ReadAllocate,
        write = CacheWritePolicy.WriteThrough,
        writeMiss = CacheWriteMissPolicy.NoWriteAllocate
      )
    ),
    dcache = CacheConfig(
      enabled = true,
      geometry = CacheGeometry(4096, 64, CacheMapping.SetAssociative(2)),
      replacement = CacheReplacement.TreePLRU,
      policy = CachePolicy(
        readMiss = CacheReadMissPolicy.ReadAllocate,
        write = CacheWritePolicy.WriteBack,
        writeMiss = CacheWriteMissPolicy.WriteAllocate
      )
    ),
    instructionBuffer = InstructionBufferConfig(enabled = true, entries = 4)
  )

  /** U55C HBM 层级：在 64-byte L1 line 之后增加共享 256 KiB L2。 */
  val WideHbmWithL2: CacheHierarchyConfig = WideHbm.copy(
    l2cache = CacheConfig(
      enabled = true,
      geometry = CacheGeometry(256 * 1024, 64, CacheMapping.SetAssociative(8)),
      replacement = CacheReplacement.TreePLRU,
      policy = CachePolicy(
        readMiss = CacheReadMissPolicy.ReadAllocate,
        write = CacheWritePolicy.WriteBack,
        writeMiss = CacheWriteMissPolicy.WriteAllocate
      )
    )
  )

  /** 仅本地仿真的 PipelinedTwoCycle 教学 L1 组合；L1 命中在 AR 握手后的下一拍按序返回。 */
  val PipelinedTwoCycleTeaching: CacheHierarchyConfig = Teaching.copy(
    instructionBuffer = InstructionBufferConfig(enabled = true, entries = 8),
    accessMode = CacheAccessMode.PipelinedTwoCycle,
    pipelinedQueues = PipelinedCacheQueueConfig.TwoCycleLocal
  )

  /** 仅本地仿真的两拍宽 HBM L1 组合；保持原有 64-byte line 与主存时序。 */
  val PipelinedTwoCycleWideHbm: CacheHierarchyConfig = WideHbm.copy(
    instructionBuffer = InstructionBufferConfig(enabled = true, entries = 8),
    accessMode = CacheAccessMode.PipelinedTwoCycle,
    pipelinedQueues = PipelinedCacheQueueConfig.TwoCycleLocal
  )

  /** 仅本地仿真的 PipelinedTwoCycle 宽 HBM L1/L2 组合；L1 命中在 AR 握手后的下一拍按序返回。 */
  val PipelinedTwoCycleWideHbmWithL2: CacheHierarchyConfig = WideHbmWithL2.copy(
    instructionBuffer = InstructionBufferConfig(enabled = true, entries = 8),
    accessMode = CacheAccessMode.PipelinedTwoCycle,
    pipelinedQueues = PipelinedCacheQueueConfig.TwoCycleLocal
  )
}

/** 流水线旁路通路的开关。 */
case class ForwardingConfig(
  enableIdForwarding: Boolean = true,
  enableExecuteForwarding: Boolean = true,
  enableOutstandingCompletionForwarding: Boolean = false
)

/** 动态分支预测器的表容量；方向表和 JALR 目标表共享 entries。 */
case class BranchPredictorTableConfig(
  entries: Int = 32,
  returnEntries: Int = 8
) {
  private val values = Seq(entries, returnEntries)
  require(values.forall(value => value > 0 && (value & (value - 1)) == 0),
    s"branch predictor table depths must be positive powers of two, got ${values.mkString(",")}")
}

/** 独立于流水线性能配方的分支预测模式和容量。
  *
  * `bpLog` 是预测/实际 next-PC 等分支观测信号，由动态预测配方
  * `++ new BpLogConfig` 打开。
  */
case class BranchPredictorParameters(
  enabled: Boolean = false,
  table: BranchPredictorTableConfig = BranchPredictorTableConfig(),
  bpLog: Boolean = false
)

/** 流水线、互锁与旁路的生成时参数。
  *
  * `pipelineLog` 是各级驻留、停顿和提交采样信号；标量核同样导出顺序驻留。
  * 由性能配方 `++ new PipelineLogConfig` 打开。
  */
case class PipelineConfig(
  enablePipeline: Boolean = false,
  enableInterlock: Boolean = true,
  forwarding: ForwardingConfig = ForwardingConfig(),
  integerExecuteStages: Int = 1,
  serialExecuteStages: Int = 1,
  registerInitialFetchRequest: Boolean = false,
  separateSerialIntegerAlu: Boolean = false,
  serialExecuteResultForwarding: Boolean = true,
  directIntegerWritebackBypass: Boolean = false,
  pipelineLog: Boolean = false
) {
  require(integerExecuteStages == 1 || integerExecuteStages == 2,
    s"integerExecuteStages must be 1 or 2, got $integerExecuteStages")
  require(serialExecuteStages >= 1 && serialExecuteStages <= 3,
    s"serialExecuteStages must be 1, 2, or 3, got $serialExecuteStages")
}

/**
  * 本地 DPI 主存响应时序。
  *
  * `min/max*ResponseCycles` 从 AXI-Lite 地址/写请求被接收到对应响应首次有效的周期计数。
  * 禁用时保持历史单周期 DPI slave。伪随机序列由 RTL 生成，在固定 seed 下可重复，
  * 从而使时序实验可复现。
  */
final case class DpiMemoryTimingConfig(
  enabled: Boolean = false,
  minReadResponseCycles: Int = 1,
  maxReadResponseCycles: Int = 1,
  minWriteResponseCycles: Int = 1,
  maxWriteResponseCycles: Int = 1,
  randomSeed: Int = 1
) {
  require(minReadResponseCycles >= 1,
    s"DPI read response latency must be at least one cycle, got $minReadResponseCycles")
  require(maxReadResponseCycles >= minReadResponseCycles,
    s"DPI read response range is invalid: $minReadResponseCycles..$maxReadResponseCycles")
  require(minWriteResponseCycles >= 1,
    s"DPI write response latency must be at least one cycle, got $minWriteResponseCycles")
  require(maxWriteResponseCycles >= minWriteResponseCycles,
    s"DPI write response range is invalid: $minWriteResponseCycles..$maxWriteResponseCycles")
  require(randomSeed > 0, "DPI timing randomSeed must be positive")
}

object DpiMemoryTimingConfig {
  /** 历史本地 DPI RAM 行为。 */
  val Immediate: DpiMemoryTimingConfig = DpiMemoryTimingConfig()

  /**
    * 用于 U55C 宽 L1 实验的校准功能 HBM/DDR 模型。
    * 一笔 512-bit 本地事务表示一条完整的 64-byte cache line。
    */
  val HbmJitter73To81: DpiMemoryTimingConfig = DpiMemoryTimingConfig(
    enabled = true,
    minReadResponseCycles = 73,
    maxReadResponseCycles = 81,
    minWriteResponseCycles = 73,
    maxWriteResponseCycles = 81,
    randomSeed = 0x13579bdf
  )
}

/** 主存与 MMIO 地址窗口，以及复位向量。 */
case class MemoryConfig(
  resetVector: BigInt = BigInt("80000000", 16),
  mainMemoryBase: Long = 0x80000000L,
  mainMemorySize: Long = 0x10000000L,
  mmioBase: Long = 0xA0000000L,
  mmioSize: Long = 0x02000000L,
  dpiTiming: DpiMemoryTimingConfig = DpiMemoryTimingConfig.Immediate
)

/** 顶层调试口与观测导出模式。
  *
  * `enableTopDebugIo` 只表示是否存在调试端口（NEMU 运行环和 FPGA mailbox
  * 的提交/完成通路）。额外引脚由三个导出模式打开：
  * `enableTrace` 为逐提交采样，`enableSdbDebug` 为 SDB 快照/单步，
  * `enableFinalLog` 为结束时的聚合计数。各域信号本身挂在 arch / cache /
  * pipeline / branch 参数上。
  */
case class DebugConfig(
  enableTopDebugIo: Boolean = false,
  enableTrace: Boolean = false,
  enableSdbDebug: Boolean = false,
  enableFinalLog: Boolean = false,
  enableDispatchControl: Boolean = false
) {
  def enableObservation: Boolean = enableTrace || enableSdbDebug || enableFinalLog
}

/** NPC AXI master 的接口形状和外部连接策略。 */
case class AxiConfig(
  addrWidth: Int = 32,
  dataWidth: Int = 64,
  idWidth: Int = 4,
  transactionId: Int = 0,
  useExternalMaster: Boolean = false,
  externalDataWidth: Int = 0
) {
  /** 零值保持历史的单宽度 AXI 契约。 */
  def resolvedExternalDataWidth: Int = if (externalDataWidth == 0) dataWidth else externalDataWidth

  /**
    * 无缓存路径保持 CPU 宽度的 Lite fabric。缓存核心可为外部 AXI/HBM 或本地 DPI 选择
    * 更宽的 cache-memory port，而 CPU 与 MMIO-side port 保持 XLEN 宽度。
    */
  def memoryDataWidth(cacheEnabled: Boolean): Int =
    if (cacheEnabled && externalDataWidth != 0) resolvedExternalDataWidth else dataWidth

  def validate(cacheEnabled: Boolean): Unit = {
    require(dataWidth >= 32 && (dataWidth & (dataWidth - 1)) == 0,
      s"CPU AXI data width must be a power of two and at least 32, got $dataWidth")
    require(resolvedExternalDataWidth >= dataWidth &&
      (resolvedExternalDataWidth & (resolvedExternalDataWidth - 1)) == 0,
      s"external AXI data width must be a power of two no narrower than CPU AXI ($dataWidth), got $resolvedExternalDataWidth")
    require(!useExternalMaster || cacheEnabled || resolvedExternalDataWidth == dataWidth,
      "a wider external AXI master requires an enabled cache hierarchy")
  }
}

/** 硬件模块最终消费的完整、无依赖 NPC 参数值。 */
case class NpcConfig(
  isa: ISAConfig = ISAConfig(),
  pipeline: PipelineConfig = PipelineConfig(),
  branchPredictor: BranchPredictorParameters = BranchPredictorParameters(),
  operators: OperatorConfig = OperatorConfig(),
  memory: MemoryConfig = MemoryConfig(),
  axi: AxiConfig = AxiConfig(),
  debug: DebugConfig = DebugConfig(),
  cache: CacheHierarchyConfig = CacheHierarchyConfig.Disabled
) {
  def memoryDataWidth: Int = axi.memoryDataWidth(cache.enabled)

  def validated: NpcConfig = {
    operators.routes.validate(isa)
    axi.validate(cache.enabled)
    cache.validate(axi.addrWidth, axi.dataWidth, isa)
    cache.icache.validateMemoryBus(memoryDataWidth)
    cache.dcache.validateMemoryBus(memoryDataWidth)
    cache.l2cache.validateMemoryBus(memoryDataWidth)
    require(cache.accessMode != CacheAccessMode.PipelinedTwoCycle ||
      (!axi.useExternalMaster && cache.icache.enabled && cache.dcache.enabled),
      "PipelinedTwoCycle cache access is limited to the local complete L1 DPI hierarchy")
    require(!cache.cacheLog || cache.enabled,
      "cache log requires an enabled cache hierarchy")
    require(!branchPredictor.bpLog || branchPredictor.enabled,
      "branch log requires a dynamic branch predictor")
    require(!(debug.enableTrace || debug.enableFinalLog || debug.enableSdbDebug) || debug.enableTopDebugIo,
      "trace / sdb-debug / finallog export requires the top-level debug port")
    this
  }
}
