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
  instructionBuffer: InstructionBufferConfig = InstructionBufferConfig()
) {
  def enabled: Boolean = icache.enabled || dcache.enabled
  def usesUram: Boolean = Seq(icache, dcache).exists(cache => cache.enabled && cache.storage == CacheStorage.Uram)

  def validate(addressWidth: Int, dataWidth: Int, isa: ISAConfig): Unit = {
    icache.validate(addressWidth, dataWidth)
    dcache.validate(addressWidth, dataWidth)
    require(!enabled || isa.Zifencei, "enabled caches require the RISC-V Zifencei extension")
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
}

/** 流水线旁路通路的开关。 */
case class ForwardingConfig(
  enableIdForwarding: Boolean = true,
  enableExecuteForwarding: Boolean = true
)

/** 流水线、互锁和旁路的生成时参数。 */
case class PipelineConfig(
  enablePipeline: Boolean = false,
  enableInterlock: Boolean = true,
  forwarding: ForwardingConfig = ForwardingConfig(),
  integerExecuteStages: Int = 1,
  serialExecuteStages: Int = 1,
  registerInitialFetchRequest: Boolean = false,
  separateSerialIntegerAlu: Boolean = false,
  serialExecuteResultForwarding: Boolean = true
) {
  require(integerExecuteStages == 1 || integerExecuteStages == 2,
    s"integerExecuteStages must be 1 or 2, got $integerExecuteStages")
  require(serialExecuteStages >= 1 && serialExecuteStages <= 3,
    s"serialExecuteStages must be 1, 2, or 3, got $serialExecuteStages")
}

/** 主存与 MMIO 地址窗口，以及复位向量。 */
case class MemoryConfig(
  resetVector: BigInt = BigInt("80000000", 16),
  mainMemoryBase: Long = 0x80000000L,
  mainMemorySize: Long = 0x10000000L,
  mmioBase: Long = 0xA0000000L,
  mmioSize: Long = 0x02000000L
)

/** 顶层调试和派发控制接口的开关。 */
case class DebugConfig(
  enableTopDebugIo: Boolean = false,
  enableDispatchControl: Boolean = false
)

/** NPC AXI master 的接口形状和外部连接策略。 */
case class AxiConfig(
  addrWidth: Int = 32,
  dataWidth: Int = 64,
  idWidth: Int = 4,
  transactionId: Int = 0,
  useExternalMaster: Boolean = false
)

/** 硬件模块最终消费的完整、无依赖 NPC 参数值。 */
case class NpcConfig(
  isa: ISAConfig = ISAConfig(),
  pipeline: PipelineConfig = PipelineConfig(),
  operators: OperatorConfig = OperatorConfig(),
  memory: MemoryConfig = MemoryConfig(),
  axi: AxiConfig = AxiConfig(),
  debug: DebugConfig = DebugConfig(),
  cache: CacheHierarchyConfig = CacheHierarchyConfig.Disabled
) {
  def validated: NpcConfig = {
    operators.routes.validate(isa)
    cache.validate(axi.addrWidth, axi.dataWidth, isa)
    this
  }
}
