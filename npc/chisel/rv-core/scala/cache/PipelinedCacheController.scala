package npc

import chisel3._
import chisel3.util._
import npc.protocol.{AxiLiteMasterIO, AxiLiteResp}

/** 两拍缓存内部保存的完整 CPU-side AXI-Lite 请求。 */
class PipelinedCacheRequest(addrWidth: Int, dataWidth: Int) extends Bundle {
  val write = Bool()
  val earlyAcknowledgedWrite = Bool()
  val addr = UInt(addrWidth.W)
  val size = UInt(3.W)
  val prot = UInt(3.W)
  val data = UInt(dataWidth.W)
  val strb = UInt((dataWidth / 8).W)
}

/** 读写响应共用一个 FIFO，以请求顺序约束 R/B 的可见顺序。 */
class PipelinedCacheResponse(dataWidth: Int) extends Bundle {
  val write = Bool()
  val data = UInt(dataWidth.W)
  val resp = UInt(2.W)
}

/**
  * 本地仿真专用的两拍、按序缓存控制器。
  *
  * S0 在请求握手当拍向同步阵列发起读，下一拍直接以阵列输出比较 tag 并生成结果。响应 FIFO
  * 在空时直通这笔结果，因此 CPU 可在该拍完成握手；命中流水每拍可推进一笔。一旦
  * S0 的阵列输出直接发现 miss 或 MMIO 后，入口立即关闭，已接收的流水项保留，随后以单 MSHR 完成
  * refill、writeback 或旁路事务。维护请求同样先等待流水和 FIFO 排空，再按 D$/L2
  * 控制器已有的逐 set/way 顺序写回或失效。
  */
class PipelinedCacheController(
  cache: CacheConfig,
  addrWidth: Int,
  dataWidth: Int,
  mainMemoryBase: Long,
  mainMemorySize: Long,
  readOnly: Boolean,
  queues: PipelinedCacheQueueConfig,
  memoryDataWidth: Int = 0,
  enableNextLinePrefetch: Boolean = false,
  eagerNextLinePrefetch: Boolean = false,
  enableWriteMissEarlyAcknowledgement: Boolean = false,
  cacheIndexBitOffset: Int = 0,
  cacheIndexLowValue: Int = 0,
  prefetchStrideLines: Int = 1
) extends Module {
  require(cache.enabled, "PipelinedCacheController requires an enabled CacheConfig")
  private val effectiveMemoryDataWidth = if (memoryDataWidth == 0) dataWidth else memoryDataWidth
  private val geometry = cache.geometry
  private val sets = geometry.sets
  private val ways = geometry.ways
  private val cpuBeatBytes = dataWidth / 8
  private val memoryBeatBytes = effectiveMemoryDataWidth / 8
  private val cpuBeats = geometry.lineBytes / cpuBeatBytes
  private val memoryBeats = geometry.lineBytes / memoryBeatBytes
  private val lineWidth = geometry.lineBytes * 8
  private val setWidth = math.max(1, log2Ceil(sets))
  private val wayWidth = math.max(1, log2Ceil(ways))
  private val memoryBeatWidth = math.max(1, log2Ceil(memoryBeats))
  // 多 bank D$ 由低位原 set index 选 bank；bank 内 set 跳过这些低位，tag 也从
  // 原 tag 起点继续。这样 bank 化只增加 miss 并发度，不改变总容量和冲突关系。
  private val tagWidth = geometry.tagBits(addrWidth) - cacheIndexBitOffset
  private val cpuLaneBits = log2Ceil(cpuBeatBytes)
  private val memoryLaneBits = log2Ceil(memoryBeatBytes)
  private val memoryFullStrobe = ((BigInt(1) << memoryBeatBytes) - 1).U(memoryBeatBytes.W)
  private val cpuFullStrobe = ((BigInt(1) << cpuBeatBytes) - 1).U(cpuBeatBytes.W)
  private val memoryFullSize = log2Ceil(memoryBeatBytes).U(3.W)
  // 预取 buffer 一次保存完整 line；跨多个 memory beat 的普通教学缓存保留原有阻塞 refill。
  private val nextLinePrefetch = enableNextLinePrefetch && memoryBeats == 1
  private val eagerPrefetch = eagerNextLinePrefetch && nextLinePrefetch

  cache.validate(addrWidth, dataWidth)
  cache.validateMemoryBus(effectiveMemoryDataWidth)
  require(!eagerNextLinePrefetch || enableNextLinePrefetch,
    "eager next-line prefetch requires next-line prefetch support")
  require(!enableWriteMissEarlyAcknowledgement || !readOnly,
    "write-miss early acknowledgement requires a writable cache")
  require(cacheIndexBitOffset >= 0 &&
    geometry.offsetBits + cacheIndexBitOffset + geometry.indexBits < addrWidth,
    s"cache index bit offset $cacheIndexBitOffset is invalid for address width $addrWidth")
  require(cacheIndexLowValue >= 0 && cacheIndexLowValue < (1 << cacheIndexBitOffset),
    s"cache index low value $cacheIndexLowValue does not fit $cacheIndexBitOffset bits")
  require(prefetchStrideLines > 0,
    s"prefetch stride must be positive, got $prefetchStrideLines")
  require(effectiveMemoryDataWidth >= dataWidth &&
    (effectiveMemoryDataWidth & (effectiveMemoryDataWidth - 1)) == 0,
    s"cache memory width must be a power of two no narrower than CPU width ($dataWidth), got $effectiveMemoryDataWidth")

  val io = IO(new Bundle {
    val cpu = Flipped(new AxiLiteMasterIO(addrWidth, dataWidth))
    val memory = new AxiLiteMasterIO(addrWidth, effectiveMemoryDataWidth)
    val maintenanceRequest = Input(Bool())
    val maintenanceInvalidate = Input(Bool())
    val maintenanceDone = Output(Bool())
    val drained = Output(Bool())
    val statistics = Output(new CacheStatistics)
  })

  val array = Module(new CacheArray(cache, addrWidth, effectiveMemoryDataWidth, hasDirty = !readOnly,
    useValidityEpoch = readOnly, indexBitOffset = cacheIndexBitOffset))
  val replacement = Module(new CacheReplacementUnit(sets, ways, cache.replacement))
  val requestQueue = Module(new Queue(new PipelinedCacheRequest(addrWidth, dataWidth),
    queues.requestDepth, pipe = false, flow = true))
  val responseQueue = Module(new Queue(new PipelinedCacheResponse(dataWidth),
    queues.responseDepth, pipe = false, flow = true))
  // 连续栈帧保存可在首个 write miss 的回填期间提前确认。队列只保存 B 的顺序
  // 占位；对应请求仍保留在 requestQueue，最终仍由同一 D$ 按原顺序更新 cache line。
  val earlyWriteAcknowledgements = Module(new Queue(Bool(), queues.requestDepth,
    pipe = false, flow = true))

  val sRun :: sWritebackSend :: sWritebackResponse :: sRefillAddress :: sRefillData :: sFullLineWriteAllocate :: sPassReadAddress :: sPassReadData :: sPassWriteSend :: sPassWriteResponse :: sRespond :: sMaintenanceIssue :: sMaintenanceInspect :: sMaintenanceDone :: Nil = Enum(14)
  val state = RegInit(sRun)
  // 只读 I$ 在维护入口已排空后可切换有效代际，而不必逐项改写 tag RAM。
  // 代际即将回绕时改走原有物理扫描，防止很早之前的有效 line 再次被命中。
  val validEpoch = RegInit(0.U(CacheValidityEpoch.width.W))
  val fastInstructionInvalidate = if (readOnly) {
    io.maintenanceInvalidate && validEpoch =/= CacheValidityEpoch.maximum.U(CacheValidityEpoch.width.W)
  } else false.B

  val s0Valid = RegInit(false.B)
  val s0Request = Reg(new PipelinedCacheRequest(addrWidth, dataWidth))
  // S0 在接收请求的同一拍发起同步阵列读；该位表示下一拍的阵列输出仍与当前
  // S0 请求对应。miss 在进入 MSHR 前不再接收年轻请求，因此不会保留旧读结果。
  val s0ReadReady = RegInit(false.B)

  val missRequest = Reg(new PipelinedCacheRequest(addrWidth, dataWidth))
  val victimWay = RegInit(0.U(wayWidth.W))
  val victimTag = RegInit(0.U(tagWidth.W))
  val victimLine = Reg(UInt(lineWidth.W))
  val victimSet = RegInit(0.U(setWidth.W))
  val writebackBeat = RegInit(0.U(memoryBeatWidth.W))
  val writebackMaintenance = RegInit(false.B)
  val writebackInstallFullLine = RegInit(false.B)
  val missResponseSuppressed = RegInit(false.B)
  val refillLine = Reg(UInt(lineWidth.W))
  val refillBeat = RegInit(0.U(memoryBeatWidth.W))
  val sendAwDone = RegInit(false.B)
  val sendWDone = RegInit(false.B)
  val completeData = RegInit(0.U(dataWidth.W))
  val completeResp = RegInit(AxiLiteResp.OKAY)
  val completeWrite = RegInit(false.B)
  // WT 写入必须等下游 B 成功后才更新 cache；失败时保留旧 line。
  val writeThroughPending = RegInit(false.B)
  val writeThroughWay = RegInit(0.U(wayWidth.W))
  val writeThroughLine = Reg(UInt(lineWidth.W))
  val prefetchBufferValid = RegInit(false.B)
  val prefetchBufferAddress = Reg(UInt(addrWidth.W))
  val prefetchBufferLine = Reg(UInt(lineWidth.W))
  val prefetchIssuePending = RegInit(false.B)
  val prefetchIssueAddress = Reg(UInt(addrWidth.W))
  val prefetchOutstanding = RegInit(false.B)
  val prefetchFollowIssued = RegInit(false.B)
  val prefetchLastDemandValid = RegInit(false.B)
  val prefetchLastDemandAddress = Reg(UInt(addrWidth.W))

  val maintenanceSet = RegInit(0.U(setWidth.W))
  val maintenanceWay = RegInit(0.U(wayWidth.W))
  val maintenanceInvalidate = RegInit(false.B)
  // write-back cache 记录少量脏 line 的位置。普通 FENCE 不失效有效 line，因而
  // 典型的栈帧或自修改代码写入可以定点写回；表溢出时才回退到原有全 set/way 扫描。
  private val dirtyCountWidth = math.max(1, log2Ceil(sets * ways + 1))
  private val dirtyTrackerEntries = 4
  private val dirtyTrackerIndexWidth = math.max(1, log2Ceil(dirtyTrackerEntries))
  val dirtyLineCount = RegInit(0.U(dirtyCountWidth.W))
  val dirtyTrackerValid = RegInit(VecInit(Seq.fill(dirtyTrackerEntries)(false.B)))
  val dirtyTrackerSet = Reg(Vec(dirtyTrackerEntries, UInt(setWidth.W)))
  val dirtyTrackerWay = Reg(Vec(dirtyTrackerEntries, UInt(wayWidth.W)))
  val dirtyTrackerReliable = RegInit(true.B)
  val maintenanceTrackedDirty = RegInit(false.B)
  val drained = RegInit(true.B)

  // 同一 line 的连续 store/load 可能先于同步阵列写入可见；保存完整 line，避免下一笔
  // S0 读到旧快照后覆盖较老 store 的其他 CPU word。
  val lastStoreValid = RegInit(false.B)
  val lastStoreLine = RegInit(0.U(addrWidth.W))
  val lastStoreLineData = RegInit(0.U(lineWidth.W))

  val dirtyTrackerBits = dirtyTrackerValid.asUInt
  val dirtyTrackerHasFree = !dirtyTrackerBits.andR
  val dirtyTrackerFreeIndex = PriorityEncoder(~dirtyTrackerBits)
  val dirtyTrackerVictimMatches = VecInit((0 until dirtyTrackerEntries).map { index =>
    dirtyTrackerValid(index) && dirtyTrackerSet(index) === victimSet && dirtyTrackerWay(index) === victimWay
  }).asUInt
  val dirtyTrackerRemainingBits = dirtyTrackerBits & ~dirtyTrackerVictimMatches
  val dirtyTrackerHasRemaining = dirtyTrackerRemainingBits.orR
  val dirtyTrackerNextIndex = PriorityEncoder(dirtyTrackerRemainingBits)

  val hits = RegInit(0.U(64.W))
  val misses = RegInit(0.U(64.W))
  val refills = RegInit(0.U(64.W))
  val writebacks = RegInit(0.U(64.W))
  val evictions = RegInit(0.U(64.W))
  io.statistics.hits := hits
  io.statistics.misses := misses
  io.statistics.refills := refills
  io.statistics.writebacks := writebacks
  io.statistics.evictions := evictions
  io.drained := drained
  io.maintenanceDone := state === sMaintenanceDone

  def isMainMemory(address: UInt): Bool =
    address >= mainMemoryBase.U(addrWidth.W) &&
      address < (mainMemoryBase + mainMemorySize).U(addrWidth.W)

  def cpuBeat(address: UInt): UInt = CacheAddress.beat(address, geometry, cpuBeatBytes)

  def cpuLineBeat(line: UInt, beat: UInt): UInt = {
    val vector = line.asTypeOf(Vec(cpuBeats, UInt(dataWidth.W)))
    vector(beat)
  }

  def memoryLineBeat(line: UInt, beat: UInt): UInt = {
    if (memoryBeats == 1) line
    else line.asTypeOf(Vec(memoryBeats, UInt(effectiveMemoryDataWidth.W)))(beat)
  }

  def replaceCpuBeat(line: UInt, beat: UInt, value: UInt): UInt = {
    val vector = Wire(Vec(cpuBeats, UInt(dataWidth.W)))
    vector := line.asTypeOf(Vec(cpuBeats, UInt(dataWidth.W)))
    vector(beat) := value
    vector.asUInt
  }

  def replaceMemoryBeat(line: UInt, beat: UInt, value: UInt): UInt = {
    if (memoryBeats == 1) value
    else {
      val vector = Wire(Vec(memoryBeats, UInt(effectiveMemoryDataWidth.W)))
      vector := line.asTypeOf(Vec(memoryBeats, UInt(effectiveMemoryDataWidth.W)))
      vector(beat) := value
      vector.asUInt
    }
  }

  def mergeWrite(oldData: UInt, newData: UInt, strobe: UInt): UInt = {
    val mask = VecInit((0 until cpuBeatBytes).map(lane => Fill(8, strobe(lane)))).asUInt
    (oldData & ~mask) | (newData & mask)
  }

  def memoryWordOffset(address: UInt): UInt =
    if (memoryBeatBytes == cpuBeatBytes) 0.U(1.W)
    else address(memoryLaneBits - 1, cpuLaneBits)

  def memoryAddress(address: UInt): UInt =
    if (memoryBeatBytes == cpuBeatBytes) address
    else Mux(isMainMemory(address),
      Cat(address(addrWidth - 1, memoryLaneBits), 0.U(memoryLaneBits.W)), address)

  def expandCpuData(data: UInt, address: UInt): UInt =
    if (effectiveMemoryDataWidth == dataWidth) data
    else (Cat(0.U((effectiveMemoryDataWidth - dataWidth).W), data) <<
      (memoryWordOffset(address) << log2Ceil(dataWidth)))(effectiveMemoryDataWidth - 1, 0)

  def expandCpuStrobe(strobe: UInt, address: UInt): UInt =
    if (effectiveMemoryDataWidth == dataWidth) strobe
    else (Cat(0.U((memoryBeatBytes - cpuBeatBytes).W), strobe) <<
      (memoryWordOffset(address) << cpuLaneBits))(memoryBeatBytes - 1, 0)

  def extractCpuData(data: UInt, address: UInt): UInt =
    if (effectiveMemoryDataWidth == dataWidth) data
    else (data >> (memoryWordOffset(address) << log2Ceil(dataWidth)))(dataWidth - 1, 0)

  def cacheSet(address: UInt): UInt =
    CacheAddress.set(address, geometry, cacheIndexBitOffset)

  def cacheTag(address: UInt): UInt =
    CacheAddress.tag(address, geometry, addrWidth, cacheIndexBitOffset)

  def lineBase(address: UInt): UInt = CacheAddress.lineBase(address, geometry, addrWidth)

  def victimBase(tag: UInt, set: UInt): UInt =
    CacheAddress.lineBaseFromTagAndSet(tag, set, geometry, addrWidth,
      cacheIndexBitOffset, cacheIndexLowValue)

  def accessBytes(accessType: UInt): UInt = MuxLookup(accessType(1, 0), 1.U(4.W))(Seq(
    "b00".U -> 1.U(4.W), "b01".U -> 2.U(4.W), "b10".U -> 4.U(4.W), "b11".U -> 8.U(4.W)
  ))

  val awHeld = RegInit(false.B)
  val awAddr = Reg(UInt(addrWidth.W))
  val awSize = Reg(UInt(3.W))
  val awProt = Reg(UInt(3.W))
  val wHeld = RegInit(false.B)
  val wData = Reg(UInt(dataWidth.W))
  val wStrb = Reg(UInt(cpuBeatBytes.W))
  val startingEarlyStoreMiss = WireDefault(false.B)

  val assembledAw = awHeld || io.cpu.aw.valid
  val assembledW = wHeld || io.cpu.w.valid
  val writeReadyToEnqueue = assembledAw && assembledW
  val writeOwnsEntrance = awHeld || wHeld || io.cpu.aw.valid || io.cpu.w.valid
  val assembledWriteAddress = Mux(awHeld, awAddr, io.cpu.aw.bits.addr)
  val cacheableWriteMissRefill = enableWriteMissEarlyAcknowledgement.B &&
    missResponseSuppressed && missRequest.write && isMainMemory(missRequest.addr) &&
    (state === sRefillAddress || state === sRefillData)
  val cacheableWriteMissActive = cacheableWriteMissRefill || startingEarlyStoreMiss
  val coalescingLineAddress = Mux(cacheableWriteMissRefill, missRequest.addr, s0Request.addr)
  // 回填已在下游进行，或本拍刚确认首笔 miss 时，仅放行同一 line 的完整 AW/W 对。
  // 后续栈保存可继续进入 FIFO 并先取得 B，但读、MMIO 或另一 line 绝不会越过尚未
  // 合并的写入。
  val coalescedWrite = cacheableWriteMissActive && writeReadyToEnqueue &&
    isMainMemory(assembledWriteAddress) &&
    lineBase(assembledWriteAddress) === lineBase(coalescingLineAddress)
  // 首笔写请求的 tag 在下一拍得出；若它是 miss，s0EarlyStoreMiss 会在该拍与年轻
  // AW/W 同时生效。因而不必先探测 FIFO 出队，保持 flow-through 热路径无额外空泡。
  val normalEntranceOpen = state === sRun && !io.maintenanceRequest
  val writeEntranceOpen = normalEntranceOpen || coalescedWrite
  val readReadyToEnqueue = normalEntranceOpen && io.cpu.ar.valid && !writeOwnsEntrance
  val enqueueReady = requestQueue.io.enq.ready &&
    (!coalescedWrite || earlyWriteAcknowledgements.io.enq.ready)

  io.cpu.aw.ready := writeEntranceOpen && !awHeld &&
    (!writeReadyToEnqueue || enqueueReady)
  io.cpu.w.ready := writeEntranceOpen && !wHeld &&
    (!writeReadyToEnqueue || enqueueReady)
  io.cpu.ar.ready := normalEntranceOpen && !writeOwnsEntrance && requestQueue.io.enq.ready

  requestQueue.io.enq.valid := (writeEntranceOpen && writeReadyToEnqueue || readReadyToEnqueue) &&
    (!coalescedWrite || earlyWriteAcknowledgements.io.enq.ready)
  requestQueue.io.enq.bits.write := writeReadyToEnqueue
  requestQueue.io.enq.bits.earlyAcknowledgedWrite := coalescedWrite
  requestQueue.io.enq.bits.addr := Mux(writeReadyToEnqueue,
    Mux(awHeld, awAddr, io.cpu.aw.bits.addr), io.cpu.ar.bits.addr)
  requestQueue.io.enq.bits.size := Mux(writeReadyToEnqueue,
    Mux(awHeld, awSize, io.cpu.aw.bits.size), io.cpu.ar.bits.size)
  requestQueue.io.enq.bits.prot := Mux(writeReadyToEnqueue,
    Mux(awHeld, awProt, io.cpu.aw.bits.prot), io.cpu.ar.bits.prot)
  requestQueue.io.enq.bits.data := Mux(wHeld, wData, io.cpu.w.bits.data)
  requestQueue.io.enq.bits.strb := Mux(wHeld, wStrb, io.cpu.w.bits.strb)

  when(io.cpu.aw.fire) {
    awHeld := true.B
    awAddr := io.cpu.aw.bits.addr
    awSize := io.cpu.aw.bits.size
    awProt := io.cpu.aw.bits.prot
  }
  when(io.cpu.w.fire) {
    wHeld := true.B
    wData := io.cpu.w.bits.data
    wStrb := io.cpu.w.bits.strb
  }
  when(requestQueue.io.enq.fire && requestQueue.io.enq.bits.write) {
    awHeld := false.B
    wHeld := false.B
  }

  earlyWriteAcknowledgements.io.enq.valid := requestQueue.io.enq.fire && coalescedWrite
  earlyWriteAcknowledgements.io.enq.bits := true.B

  // 正常响应 FIFO 中的较老 R/B 必须优先；提前确认的 B 只能在其后对 CPU 可见。
  val normalResponseValid = responseQueue.io.deq.valid
  io.cpu.r.valid := normalResponseValid && !responseQueue.io.deq.bits.write
  io.cpu.r.bits.data := responseQueue.io.deq.bits.data
  io.cpu.r.bits.resp := responseQueue.io.deq.bits.resp
  io.cpu.b.valid := (normalResponseValid && responseQueue.io.deq.bits.write) ||
    (!normalResponseValid && earlyWriteAcknowledgements.io.deq.valid)
  io.cpu.b.bits.resp := Mux(normalResponseValid, responseQueue.io.deq.bits.resp, AxiLiteResp.OKAY)
  responseQueue.io.deq.ready := Mux(responseQueue.io.deq.bits.write, io.cpu.b.ready, io.cpu.r.ready)
  earlyWriteAcknowledgements.io.deq.ready := !normalResponseValid && io.cpu.b.ready

  val hitVector = VecInit(array.io.readMeta.map(meta => meta.valid &&
    meta.tag === cacheTag(s0Request.addr)))
  val readHit = hitVector.asUInt.orR
  val readHitWay = PriorityEncoder(hitVector.asUInt)
  val invalidVector = VecInit(array.io.readMeta.map(meta => !meta.valid))
  val selectedVictim = Mux(invalidVector.asUInt.orR,
    PriorityEncoder(invalidVector.asUInt), replacement.io.victimWay)

  val s0SelectedLine = if (ways == 1) array.io.readLines(0) else array.io.readLines(readHitWay)
  val s0SelectedMeta = if (ways == 1) array.io.readMeta(0) else array.io.readMeta(readHitWay)
  // I$ 可直接以 buffer 行替换任何 victim；D$ 则绝不能绕过脏 victim 的写回。
  // 因此可写 cache 仅在目标 set 还有 invalid way 时安装预取行，已满 set 仍走
  // 正常 miss 状态机，以复用其完整的 write-back/allocate 次序。
  val s0PrefetchInstallable = readOnly.B || invalidVector.asUInt.orR
  val s0PrefetchHit = nextLinePrefetch.B && !s0Request.write && !readHit &&
    s0PrefetchInstallable && prefetchBufferValid &&
    prefetchBufferAddress === lineBase(s0Request.addr)
  val s0ReadLine = Mux(s0PrefetchHit, prefetchBufferLine, s0SelectedLine)
  val s0Beat = cpuBeat(s0Request.addr)
  val s0ForwardedLine = Mux(lastStoreValid && lastStoreLine === lineBase(s0Request.addr),
    lastStoreLineData, s0ReadLine)
  val s0ReadWord = cpuLineBeat(s0ForwardedLine, s0Beat)
  // 同一 line 的相邻 store 即使落在不同 CPU word，也必须从完整转发 line 继续合并。
  val s0WriteBase = s0ReadWord
  val s0LoadData = s0WriteBase
  val s0UpdatedWord = mergeWrite(s0WriteBase, s0Request.data, s0Request.strb)
  val s0UpdatedLine = replaceCpuBeat(s0ForwardedLine, s0Beat, s0UpdatedWord)
  val s0MainMemory = isMainMemory(s0Request.addr)
  val s0HitResponse = s0Valid && s0ReadReady && s0MainMemory && (readHit || s0PrefetchHit) &&
    (!s0Request.write || readOnly.B || (cache.policy.write == CacheWritePolicy.WriteBack).B)
  // D$ 的预取行尚未写入阵列。对同一 line 的 store 无论命中或 miss 都必须撤销
  // buffer，否则写回后的后续 load 会被旧的预取副本覆盖。
  val s0WritesPrefetchLine = if (readOnly) false.B else {
    s0Valid && s0ReadReady && s0Request.write && s0MainMemory && prefetchBufferValid &&
      prefetchBufferAddress === lineBase(s0Request.addr)
  }
  val s0WriteThroughHit = s0Valid && s0ReadReady && s0MainMemory && readHit && s0Request.write &&
    !readOnly.B && (cache.policy.write == CacheWritePolicy.WriteThrough).B
  // S0 的 hit 在同步阵列读出的本拍直接进入响应 FIFO；miss 或 MMIO 在本拍关闭
  // 新入口，因此不会让较新的命中越过较早的阻塞请求。
  val s0CanComplete = state === sRun && s0HitResponse &&
    (s0Request.earlyAcknowledgedWrite || responseQueue.io.enq.ready)
  val s0Miss = state === sRun && s0Valid && s0ReadReady &&
    !s0HitResponse && !s0WriteThroughHit
  // 一笔低优先级预取仍占用下游 R 顺序，新的 demand miss 必须等它返回，避免把响应
  // 错配给 demand refill；命中仍可与预取倒计时并行服务。
  val prefetchBlocksDemand = nextLinePrefetch.B && (prefetchIssuePending || prefetchOutstanding)
  val s0EarlyStoreMiss = enableWriteMissEarlyAcknowledgement.B && s0Miss && s0Request.write &&
    s0MainMemory && (cache.policy.write == CacheWritePolicy.WriteBack).B &&
    responseQueue.io.enq.ready
  startingEarlyStoreMiss := s0EarlyStoreMiss
  // 提前 B 只适用于可缓存主存的 write-back miss。MMIO 必须走真实旁路 AW/W/B，
  // 不能因不满足提前确认条件而停留在 S0。
  val s0WriteBackStoreNeedsEarlyAck = enableWriteMissEarlyAcknowledgement.B && s0Request.write &&
    s0MainMemory && (cache.policy.write == CacheWritePolicy.WriteBack).B
  val s0DemandMiss = s0Miss && !prefetchBlocksDemand &&
    (!s0WriteBackStoreNeedsEarlyAck || s0EarlyStoreMiss)
  val s0DemandLineAddress = lineBase(s0Request.addr)
  // 低位选 bank 时，同一控制器只接收每隔 `prefetchStrideLines` 的全局 line；预取也
  // 必须维持该 bank 归属，不能把已取回的数据留在另一个控制器的私有 buffer 中。
  val s0SuccessorAddress = s0DemandLineAddress +
    (geometry.lineBytes * prefetchStrideLines).U(addrWidth.W)
  val s0SuccessorInRange = s0SuccessorAddress >= mainMemoryBase.U(addrWidth.W) &&
    s0SuccessorAddress < (mainMemoryBase + mainMemorySize).U(addrWidth.W)
  val s0SequentialDemand = prefetchLastDemandValid &&
    s0DemandLineAddress === prefetchLastDemandAddress +
      (geometry.lineBytes * prefetchStrideLines).U(addrWidth.W)

  // miss 响应进入 CPU 的同拍，下一条已排队请求可以开始同步阵列读。此时 refill
  // 已在前一拍写入阵列，且 response FIFO 的旧响应先完成握手，因而既不读写同拍
  // 也不改变响应顺序；这去除了每次 miss 恢复后额外的一拍入口空泡。
  val s0CanIssue = state === sRun ||
    (state === sRespond && responseQueue.io.enq.ready)
  // 维护请求会关闭新入口，但先前已经完成 AR/AW/W 握手的请求必须继续排空；
  // 否则维护起始条件等待 FIFO 为空，而 FIFO 又被维护请求禁止出队，形成死锁。
  requestQueue.io.deq.ready := s0CanIssue && (!s0Valid || s0CanComplete)
  val s0Issue = requestQueue.io.deq.fire

  val responseEmit = WireDefault(false.B)
  val responseEmitWrite = WireDefault(false.B)
  val responseEmitData = WireDefault(0.U(dataWidth.W))
  val responseEmitCode = WireDefault(AxiLiteResp.OKAY)
  responseQueue.io.enq.valid := responseEmit
  responseQueue.io.enq.bits.write := responseEmitWrite
  responseQueue.io.enq.bits.data := responseEmitData
  responseQueue.io.enq.bits.resp := responseEmitCode

  when(s0HitResponse && !s0Request.earlyAcknowledgedWrite) {
    responseEmit := true.B
    responseEmitWrite := s0Request.write
    responseEmitData := Mux(s0Request.write, 0.U, s0LoadData)
  }
  when(s0EarlyStoreMiss) {
    responseEmit := true.B
    responseEmitWrite := true.B
    responseEmitData := 0.U
  }
  when(state === sRespond) {
    responseEmit := true.B
    responseEmitWrite := completeWrite
    responseEmitData := completeData
    responseEmitCode := completeResp
  }

  // 新到请求可直接作为 S0 的同步读地址；miss 期间留在 S0 的年轻请求在恢复后
  // 使用相同地址重读，不能复用 refill 前已经失效的阵列输出。
  val s0ReplayRead = state === sRun && s0Valid && !s0ReadReady
  array.io.readEnable := s0Issue || s0ReplayRead || state === sMaintenanceIssue
  array.io.readSet := Mux(state === sMaintenanceIssue, maintenanceSet,
    Mux(s0Issue, cacheSet(requestQueue.io.deq.bits.addr), cacheSet(s0Request.addr)))
  array.io.dataWriteEnable := false.B
  array.io.dataWriteSet := cacheSet(s0Request.addr)
  array.io.dataWriteWay := readHitWay
  array.io.dataWriteLine := s0UpdatedLine
  array.io.metaWriteEnable := false.B
  array.io.metaWriteSet := cacheSet(s0Request.addr)
  array.io.metaWriteWay := readHitWay
  array.io.metaWrite := 0.U.asTypeOf(new CacheTagMeta(tagWidth))
  array.io.validEpoch := (if (readOnly) validEpoch else 0.U(CacheValidityEpoch.width.W))
  replacement.io.querySet := cacheSet(s0Request.addr)
  replacement.io.accessValid := false.B
  replacement.io.replaceValid := false.B
  replacement.io.accessSet := cacheSet(s0Request.addr)
  replacement.io.accessWay := readHitWay

  // 下游 AXI-Lite 默认保持静止；阻塞状态只驱动唯一的 refill、writeback 或旁路事务。
  io.memory.aw.valid := false.B
  io.memory.aw.bits.addr := memoryAddress(missRequest.addr)
  io.memory.aw.bits.size := missRequest.size
  io.memory.aw.bits.prot := missRequest.prot
  io.memory.w.valid := false.B
  io.memory.w.bits.data := expandCpuData(missRequest.data, missRequest.addr)
  io.memory.w.bits.strb := expandCpuStrobe(missRequest.strb, missRequest.addr)
  io.memory.b.ready := false.B
  io.memory.ar.valid := false.B
  io.memory.ar.bits.addr := memoryAddress(missRequest.addr)
  io.memory.ar.bits.size := missRequest.size
  io.memory.ar.bits.prot := missRequest.prot
  io.memory.r.ready := false.B

  // demand refill 先占用 AR；其下一拍开始，或 buffer line 首次命中后的空闲拍，才可
  // 发送低优先级预取。预取 R 只在正常运行/交付 demand 响应时吸收，保持两类响应顺序。
  val prefetchIssueAllowed = nextLinePrefetch.B && prefetchIssuePending &&
    (state === sRun || state === sRefillData || state === sRespond)
  val prefetchResponseAllowed = nextLinePrefetch.B && prefetchOutstanding &&
    (state === sRun || state === sRespond)
  when(prefetchIssueAllowed) {
    io.memory.ar.valid := true.B
    io.memory.ar.bits.addr := prefetchIssueAddress
    io.memory.ar.bits.size := memoryFullSize
    io.memory.ar.bits.prot := "b100".U
  }
  when(prefetchResponseAllowed) {
    io.memory.r.ready := true.B
  }
  when(prefetchIssueAllowed && io.memory.ar.fire) {
    prefetchIssuePending := false.B
    prefetchOutstanding := true.B
  }
  when(prefetchResponseAllowed && io.memory.r.fire) {
    prefetchOutstanding := false.B
    prefetchBufferValid := io.memory.r.bits.resp === AxiLiteResp.OKAY && !s0WritesPrefetchLine
    prefetchBufferAddress := prefetchIssueAddress
    prefetchBufferLine := io.memory.r.bits.data
    prefetchFollowIssued := false.B
  }
  when(s0WritesPrefetchLine) {
    prefetchBufferValid := false.B
  }

  val s0SelectedVictimLine = if (ways == 1) array.io.readLines(0) else array.io.readLines(selectedVictim)
  val s0SelectedVictimMeta = if (ways == 1) array.io.readMeta(0) else array.io.readMeta(selectedVictim)
  val s0Allocates = Mux(s0Request.write,
    (cache.policy.writeMiss == CacheWriteMissPolicy.WriteAllocate).B,
    (cache.policy.readMiss == CacheReadMissPolicy.ReadAllocate).B)
  // L1 向 L2 回写时，CPU-side 请求本身就是一条完整 cache line。write-back 的
  // write-allocate miss 可直接安装该 line；先读旧主存再逐字合并既不影响结果，
  // 又会在 HBM 路径上额外消耗一次完整延迟。
  val s0FullLineWriteAllocate = s0Request.write && s0Allocates &&
    (cache.policy.write == CacheWritePolicy.WriteBack).B && (cpuBeats == 1).B &&
    (memoryBeats == 1).B && s0Request.strb === cpuFullStrobe
  val fullLineWriteData = if (cpuBeats == 1) missRequest.data
    else replaceCpuBeat(0.U(lineWidth.W), 0.U, missRequest.data)

  val writebackAddress = victimBase(victimTag, victimSet) + writebackBeat * memoryBeatBytes.U
  val refillAddress = lineBase(missRequest.addr) + refillBeat * memoryBeatBytes.U

  // S0 命中完成的同拍可接收下一笔请求；miss 和 write-through 则在入口关闭后
  // 清空 S0，再把当前请求交给原有阻塞状态机。sRespond 交付 miss 响应时也可
  // 重新装入 S0，下一拍直接使用已经完成 refill 的同步阵列输出。
  when(s0Issue) {
    s0Valid := true.B
    s0Request := requestQueue.io.deq.bits
    s0ReadReady := true.B
  }.elsewhen(s0CanComplete || s0WriteThroughHit || s0DemandMiss) {
    s0Valid := false.B
    s0ReadReady := false.B
  }.elsewhen(s0ReplayRead) {
    s0ReadReady := true.B
  }

  when(s0HitResponse && (s0Request.earlyAcknowledgedWrite || responseQueue.io.enq.fire)) {
    hits := hits + 1.U
    when(s0PrefetchHit) {
      // buffer 行在首次向 CPU 交付时写入 I$。否则循环再次到达该 line 时，buffer
      // 已被后继预取替换，原本已取回的数据会退化为新的 HBM demand miss。
      array.io.dataWriteEnable := true.B
      array.io.dataWriteSet := cacheSet(s0Request.addr)
      array.io.dataWriteWay := selectedVictim
      array.io.dataWriteLine := prefetchBufferLine
      array.io.metaWriteEnable := true.B
      array.io.metaWriteSet := cacheSet(s0Request.addr)
      array.io.metaWriteWay := selectedVictim
      array.io.metaWrite.valid := true.B
      array.io.metaWrite.dirty := false.B
      array.io.metaWrite.tag := cacheTag(prefetchBufferAddress)
      replacement.io.accessValid := true.B
      replacement.io.replaceValid := true.B
      replacement.io.accessSet := cacheSet(s0Request.addr)
      replacement.io.accessWay := selectedVictim
    }.otherwise {
      replacement.io.accessValid := true.B
      replacement.io.accessSet := cacheSet(s0Request.addr)
      replacement.io.accessWay := readHitWay
    }
    when(s0Request.write) {
      if (!readOnly) {
        array.io.dataWriteEnable := true.B
        array.io.dataWriteSet := cacheSet(s0Request.addr)
        array.io.dataWriteWay := readHitWay
        array.io.dataWriteLine := s0UpdatedLine
        array.io.metaWriteEnable := true.B
        array.io.metaWriteSet := cacheSet(s0Request.addr)
        array.io.metaWriteWay := readHitWay
        array.io.metaWrite.valid := true.B
        array.io.metaWrite.dirty := (cache.policy.write == CacheWritePolicy.WriteBack).B
        array.io.metaWrite.tag := s0SelectedMeta.tag
        when((cache.policy.write == CacheWritePolicy.WriteBack).B) {
          drained := false.B
          when(!s0SelectedMeta.dirty) {
            dirtyLineCount := dirtyLineCount + 1.U
            when(dirtyTrackerReliable) {
              when(dirtyTrackerHasFree) {
                dirtyTrackerValid(dirtyTrackerFreeIndex) := true.B
                dirtyTrackerSet(dirtyTrackerFreeIndex) := cacheSet(s0Request.addr)
                dirtyTrackerWay(dirtyTrackerFreeIndex) := readHitWay
              }.otherwise {
                dirtyTrackerReliable := false.B
              }
            }
          }
        }
        lastStoreValid := true.B
        lastStoreLine := lineBase(s0Request.addr)
        lastStoreLineData := s0UpdatedLine
      }
    }
  }

  // 一个 buffer line 的首个消费触发其后继预取。原 line 留在 buffer，直到新 R
  // 真正到达，因而同一 line 的剩余 word 仍是命中而不会退化为重复 demand miss。
  when(nextLinePrefetch.B && s0CanComplete && s0PrefetchHit && !prefetchFollowIssued &&
    !prefetchIssuePending && !prefetchOutstanding && s0SuccessorInRange) {
    prefetchIssuePending := true.B
    prefetchIssueAddress := s0SuccessorAddress
    prefetchFollowIssued := true.B
  }

  when(state === sRun && s0WriteThroughHit) {
    // 命中也要经过真实下游写事务；B 返回前不能让 CPU 观察到未确认的数据。
    missRequest := s0Request
    completeWrite := true.B
    completeData := 0.U
    completeResp := AxiLiteResp.OKAY
    writeThroughPending := true.B
    writeThroughWay := readHitWay
    writeThroughLine := s0UpdatedLine
    sendAwDone := false.B
    sendWDone := false.B
    state := sPassWriteSend
  }.elsewhen(s0DemandMiss) {
    misses := misses + 1.U
    missRequest := s0Request
    completeWrite := s0Request.write
    completeData := 0.U
    completeResp := AxiLiteResp.OKAY
    missResponseSuppressed := s0EarlyStoreMiss
    lastStoreValid := false.B
    writebackInstallFullLine := false.B
    when(nextLinePrefetch.B && !s0Request.write && s0MainMemory) {
      // 共享 L2 能让 I$/D$ 的读在不同 bank 并发倒计时，首个 line miss 即可启动
      // 后继预取以隐藏 HBM 延迟。仅 L1 则保留二次相邻 demand 的确认，避免启动跳转
      // 让无用预取占住唯一的顺序 R 通道。
      when((eagerPrefetch.B || s0SequentialDemand) && s0SuccessorInRange) {
        prefetchIssuePending := true.B
        prefetchIssueAddress := s0SuccessorAddress
        prefetchFollowIssued := false.B
      }
      prefetchLastDemandValid := true.B
      prefetchLastDemandAddress := s0DemandLineAddress
    }.otherwise {
      prefetchLastDemandValid := false.B
    }
    when(!s0MainMemory || !s0Allocates) {
      sendAwDone := false.B
      sendWDone := false.B
      state := Mux(s0Request.write, sPassWriteSend, sPassReadAddress)
    }.otherwise {
      victimWay := selectedVictim
      victimTag := s0SelectedVictimMeta.tag
      victimLine := s0SelectedVictimLine
      victimSet := cacheSet(s0Request.addr)
      writebackInstallFullLine := s0FullLineWriteAllocate
      when(s0SelectedVictimMeta.valid) { evictions := evictions + 1.U }
      when(s0SelectedVictimMeta.valid && s0SelectedVictimMeta.dirty) {
        writebackBeat := 0.U
        writebackMaintenance := false.B
        sendAwDone := false.B
        sendWDone := false.B
        writebacks := writebacks + 1.U
        state := sWritebackSend
      }.otherwise {
        when(s0FullLineWriteAllocate) {
          state := sFullLineWriteAllocate
        }.otherwise {
          refillLine := 0.U
          refillBeat := 0.U
          state := sRefillAddress
        }
      }
    }
  }

  switch(state) {
    is(sWritebackSend) {
      io.memory.aw.valid := !sendAwDone
      io.memory.aw.bits.addr := writebackAddress
      io.memory.aw.bits.size := memoryFullSize
      io.memory.aw.bits.prot := 0.U
      io.memory.w.valid := !sendWDone
      io.memory.w.bits.data := memoryLineBeat(victimLine, writebackBeat)
      io.memory.w.bits.strb := memoryFullStrobe
      when(io.memory.aw.fire) { sendAwDone := true.B }
      when(io.memory.w.fire) { sendWDone := true.B }
      when((sendAwDone || io.memory.aw.fire) && (sendWDone || io.memory.w.fire)) {
        state := sWritebackResponse
      }
    }
    is(sWritebackResponse) {
      io.memory.b.ready := true.B
      when(io.memory.b.fire) {
        when(io.memory.b.bits.resp =/= AxiLiteResp.OKAY) {
          completeResp := io.memory.b.bits.resp
          completeData := 0.U
          when(writebackMaintenance) { state := sMaintenanceDone }.otherwise { state := sRespond }
        }.elsewhen(writebackBeat === (memoryBeats - 1).U) {
          array.io.metaWriteEnable := true.B
          array.io.metaWriteSet := victimSet
          array.io.metaWriteWay := victimWay
          array.io.metaWrite.valid := !writebackMaintenance || !maintenanceInvalidate
          array.io.metaWrite.dirty := false.B
          array.io.metaWrite.tag := victimTag
          when(writebackMaintenance) {
            when(maintenanceTrackedDirty) {
              // 当前 B 成功才移除已写回的跟踪项；剩余小表项保持原顺序，下一拍
              // 重新读其 set。若表与实际 dirty 状态不一致，直接退回完整扫描。
              for (index <- 0 until dirtyTrackerEntries) {
                when(dirtyTrackerVictimMatches(index)) { dirtyTrackerValid(index) := false.B }
              }
              when(dirtyLineCount === 1.U) {
                dirtyLineCount := 0.U
                drained := true.B
                state := sMaintenanceDone
              }.elsewhen(dirtyTrackerHasRemaining) {
                dirtyLineCount := dirtyLineCount - 1.U
                maintenanceSet := dirtyTrackerSet(dirtyTrackerNextIndex)
                maintenanceWay := dirtyTrackerWay(dirtyTrackerNextIndex)
                state := sMaintenanceIssue
              }.otherwise {
                dirtyTrackerReliable := false.B
                maintenanceTrackedDirty := false.B
                maintenanceSet := 0.U
                maintenanceWay := 0.U
                state := sMaintenanceIssue
              }
            }.otherwise {
              when(dirtyLineCount =/= 0.U) { dirtyLineCount := dirtyLineCount - 1.U }
              when(maintenanceWay === (ways - 1).U) {
                maintenanceWay := 0.U
                when(maintenanceSet === (sets - 1).U) {
                  dirtyLineCount := 0.U
                  dirtyTrackerReliable := true.B
                  for (index <- 0 until dirtyTrackerEntries) { dirtyTrackerValid(index) := false.B }
                  drained := true.B
                  state := sMaintenanceDone
                }.otherwise {
                  maintenanceSet := maintenanceSet + 1.U
                  state := sMaintenanceIssue
                }
              }.otherwise {
                maintenanceWay := maintenanceWay + 1.U
                state := sMaintenanceIssue
              }
            }
          }.otherwise {
            when(dirtyLineCount =/= 0.U) {
              dirtyLineCount := dirtyLineCount - 1.U
              when(dirtyLineCount === 1.U) { drained := true.B }
            }
            when(dirtyTrackerReliable) {
              for (index <- 0 until dirtyTrackerEntries) {
                when(dirtyTrackerVictimMatches(index)) { dirtyTrackerValid(index) := false.B }
              }
            }
            when(writebackInstallFullLine) {
              state := sFullLineWriteAllocate
            }.otherwise {
              refillLine := 0.U
              refillBeat := 0.U
              state := sRefillAddress
            }
          }
        }.otherwise {
          writebackBeat := writebackBeat + 1.U
          sendAwDone := false.B
          sendWDone := false.B
          state := sWritebackSend
        }
      }
    }
    is(sRefillAddress) {
      io.memory.ar.valid := true.B
      io.memory.ar.bits.addr := refillAddress
      io.memory.ar.bits.size := memoryFullSize
      io.memory.ar.bits.prot := missRequest.prot
      when(io.memory.ar.fire) { state := sRefillData }
    }
    is(sRefillData) {
      io.memory.r.ready := true.B
        when(io.memory.r.fire) {
          when(io.memory.r.bits.resp =/= AxiLiteResp.OKAY) {
            completeResp := io.memory.r.bits.resp
            completeData := 0.U
            state := Mux(missResponseSuppressed, sRun, sRespond)
            missResponseSuppressed := false.B
        }.otherwise {
          val completedLine = replaceMemoryBeat(refillLine, refillBeat, io.memory.r.bits.data)
          when(refillBeat === (memoryBeats - 1).U) {
            val installedLine = WireDefault(completedLine)
            when(missRequest.write && (cache.policy.write == CacheWritePolicy.WriteBack).B) {
              installedLine := replaceCpuBeat(completedLine, cpuBeat(missRequest.addr),
                mergeWrite(cpuLineBeat(completedLine, cpuBeat(missRequest.addr)),
                  missRequest.data, missRequest.strb))
            }
            val writeThroughMiss = missRequest.write &&
              (cache.policy.write == CacheWritePolicy.WriteThrough).B
            when(!writeThroughMiss) {
              array.io.dataWriteEnable := true.B
              array.io.dataWriteSet := cacheSet(missRequest.addr)
              array.io.dataWriteWay := victimWay
              array.io.dataWriteLine := installedLine
              array.io.metaWriteEnable := true.B
              array.io.metaWriteSet := cacheSet(missRequest.addr)
              array.io.metaWriteWay := victimWay
              array.io.metaWrite.valid := true.B
              array.io.metaWrite.dirty := missRequest.write &&
                (cache.policy.write == CacheWritePolicy.WriteBack).B
              array.io.metaWrite.tag := cacheTag(missRequest.addr)
              replacement.io.accessValid := true.B
              replacement.io.replaceValid := true.B
              replacement.io.accessSet := cacheSet(missRequest.addr)
              replacement.io.accessWay := victimWay
            }
            refills := refills + 1.U
            when(missRequest.write && (cache.policy.write == CacheWritePolicy.WriteBack).B) {
              dirtyLineCount := dirtyLineCount + 1.U
              when(dirtyTrackerReliable) {
                when(dirtyTrackerHasFree) {
                  dirtyTrackerValid(dirtyTrackerFreeIndex) := true.B
                  dirtyTrackerSet(dirtyTrackerFreeIndex) := cacheSet(missRequest.addr)
                  dirtyTrackerWay(dirtyTrackerFreeIndex) := victimWay
                }.otherwise {
                  dirtyTrackerReliable := false.B
                }
              }
              drained := false.B
              lastStoreValid := true.B
              lastStoreLine := lineBase(missRequest.addr)
              lastStoreLineData := installedLine
            }
            completeData := Mux(missRequest.write, 0.U,
              cpuLineBeat(completedLine, cpuBeat(missRequest.addr)))
            completeResp := AxiLiteResp.OKAY
            when(writeThroughMiss) {
              // WT write-allocate 的 line 在下游 B 成功前只保存在寄存器中。
              writeThroughPending := true.B
              writeThroughWay := victimWay
              writeThroughLine := replaceCpuBeat(completedLine, cpuBeat(missRequest.addr),
                mergeWrite(cpuLineBeat(completedLine, cpuBeat(missRequest.addr)),
                  missRequest.data, missRequest.strb))
              sendAwDone := false.B
              sendWDone := false.B
              state := sPassWriteSend
            }.otherwise {
              state := Mux(missResponseSuppressed, sRun, sRespond)
              missResponseSuppressed := false.B
            }
          }.otherwise {
            refillLine := completedLine
            refillBeat := refillBeat + 1.U
            state := sRefillAddress
          }
        }
      }
    }
    is(sFullLineWriteAllocate) {
      array.io.dataWriteEnable := true.B
      array.io.dataWriteSet := cacheSet(missRequest.addr)
      array.io.dataWriteWay := victimWay
      array.io.dataWriteLine := fullLineWriteData
      array.io.metaWriteEnable := true.B
      array.io.metaWriteSet := cacheSet(missRequest.addr)
      array.io.metaWriteWay := victimWay
      array.io.metaWrite.valid := true.B
      array.io.metaWrite.dirty := true.B
      array.io.metaWrite.tag := cacheTag(missRequest.addr)
      replacement.io.accessValid := true.B
      replacement.io.replaceValid := true.B
      replacement.io.accessSet := cacheSet(missRequest.addr)
      replacement.io.accessWay := victimWay
      refills := refills + 1.U
      dirtyLineCount := dirtyLineCount + 1.U
      when(dirtyTrackerReliable) {
        when(dirtyTrackerHasFree) {
          dirtyTrackerValid(dirtyTrackerFreeIndex) := true.B
          dirtyTrackerSet(dirtyTrackerFreeIndex) := cacheSet(missRequest.addr)
          dirtyTrackerWay(dirtyTrackerFreeIndex) := victimWay
        }.otherwise {
          dirtyTrackerReliable := false.B
        }
      }
      drained := false.B
      lastStoreValid := true.B
      lastStoreLine := lineBase(missRequest.addr)
      lastStoreLineData := fullLineWriteData
      completeWrite := true.B
      completeData := 0.U
      completeResp := AxiLiteResp.OKAY
      writebackInstallFullLine := false.B
      state := sRespond
    }
    is(sPassReadAddress) {
      io.memory.ar.valid := true.B
      when(io.memory.ar.fire) { state := sPassReadData }
    }
    is(sPassReadData) {
      io.memory.r.ready := true.B
      when(io.memory.r.fire) {
        completeData := extractCpuData(io.memory.r.bits.data, missRequest.addr)
        completeResp := io.memory.r.bits.resp
        state := sRespond
      }
    }
    is(sPassWriteSend) {
      io.memory.aw.valid := !sendAwDone
      io.memory.w.valid := !sendWDone
      when(io.memory.aw.fire) { sendAwDone := true.B }
      when(io.memory.w.fire) { sendWDone := true.B }
      when((sendAwDone || io.memory.aw.fire) && (sendWDone || io.memory.w.fire)) {
        state := sPassWriteResponse
      }
    }
    is(sPassWriteResponse) {
      io.memory.b.ready := true.B
      when(io.memory.b.fire) {
        completeData := 0.U
        completeResp := io.memory.b.bits.resp
        when(writeThroughPending && io.memory.b.bits.resp === AxiLiteResp.OKAY) {
          array.io.dataWriteEnable := true.B
          array.io.dataWriteSet := cacheSet(missRequest.addr)
          array.io.dataWriteWay := writeThroughWay
          array.io.dataWriteLine := writeThroughLine
          array.io.metaWriteEnable := true.B
          array.io.metaWriteSet := cacheSet(missRequest.addr)
          array.io.metaWriteWay := writeThroughWay
          array.io.metaWrite.valid := true.B
          array.io.metaWrite.dirty := false.B
          array.io.metaWrite.tag := cacheTag(missRequest.addr)
          replacement.io.accessValid := true.B
          replacement.io.replaceValid := true.B
          replacement.io.accessSet := cacheSet(missRequest.addr)
          replacement.io.accessWay := writeThroughWay
          lastStoreValid := true.B
          lastStoreLine := lineBase(missRequest.addr)
          lastStoreLineData := writeThroughLine
        }
        writeThroughPending := false.B
        state := sRespond
      }
    }
    is(sRespond) {
      when(responseQueue.io.enq.fire) { state := sRun }
    }
    is(sMaintenanceIssue) {
      state := sMaintenanceInspect
    }
    is(sMaintenanceInspect) {
      val maintenanceMeta = if (ways == 1) array.io.readMeta(0) else array.io.readMeta(maintenanceWay)
      val maintenanceLine = if (ways == 1) array.io.readLines(0) else array.io.readLines(maintenanceWay)
      when(maintenanceMeta.valid && maintenanceMeta.dirty) {
        victimWay := maintenanceWay
        victimTag := maintenanceMeta.tag
        victimLine := maintenanceLine
        victimSet := maintenanceSet
        writebackBeat := 0.U
        writebackMaintenance := true.B
        sendAwDone := false.B
        sendWDone := false.B
        writebacks := writebacks + 1.U
        state := sWritebackSend
      }.otherwise {
        when(maintenanceTrackedDirty) {
          // 跟踪表只是一条性能提示，不能参与正确性假设。若定点读取不是 dirty
          // line，下一拍从头做完整扫描，并在结束时重建空表状态。
          dirtyTrackerReliable := false.B
          maintenanceTrackedDirty := false.B
          maintenanceSet := 0.U
          maintenanceWay := 0.U
          state := sMaintenanceIssue
        }.otherwise {
          when(maintenanceMeta.valid && maintenanceInvalidate) {
            array.io.metaWriteEnable := true.B
            array.io.metaWriteSet := maintenanceSet
            array.io.metaWriteWay := maintenanceWay
            array.io.metaWrite.valid := false.B
            array.io.metaWrite.dirty := false.B
            array.io.metaWrite.tag := maintenanceMeta.tag
          }
          when(maintenanceWay === (ways - 1).U) {
            maintenanceWay := 0.U
            when(maintenanceSet === (sets - 1).U) {
              dirtyLineCount := 0.U
              dirtyTrackerReliable := true.B
              for (index <- 0 until dirtyTrackerEntries) { dirtyTrackerValid(index) := false.B }
              drained := true.B
              state := sMaintenanceDone
            }.otherwise {
              maintenanceSet := maintenanceSet + 1.U
              state := sMaintenanceIssue
            }
          }.otherwise {
            maintenanceWay := maintenanceWay + 1.U
            state := sMaintenanceIssue
          }
        }
      }
    }
    is(sMaintenanceDone) {
      if (readOnly) {
        // 回绕前的完整扫描已清空全部物理 valid 位，随后重置代际才不会复活旧 line。
        when(io.maintenanceInvalidate && validEpoch === CacheValidityEpoch.maximum.U(CacheValidityEpoch.width.W)) {
          validEpoch := 0.U
        }
      }
      when(!io.maintenanceRequest) { state := sRun }
    }
  }

  // 维护仅在入口、S0 和两个 FIFO 均空时启动，防止 FENCE 观察到较早的命中响应。
  val maintenanceCanStart = state === sRun && io.maintenanceRequest && !s0Valid &&
    !requestQueue.io.deq.valid && !responseQueue.io.deq.valid && !awHeld && !wHeld &&
    !prefetchIssuePending && !prefetchOutstanding && !earlyWriteAcknowledgements.io.deq.valid &&
    !missResponseSuppressed
  when(maintenanceCanStart) {
    maintenanceInvalidate := io.maintenanceInvalidate
    when(io.maintenanceInvalidate) {
      prefetchBufferValid := false.B
      prefetchFollowIssued := false.B
      prefetchLastDemandValid := false.B
    }
    when(fastInstructionInvalidate) {
      validEpoch := validEpoch + 1.U
      drained := true.B
      state := sMaintenanceDone
    }.otherwise {
      val useDirtyTracker = !io.maintenanceInvalidate && dirtyLineCount =/= 0.U &&
        dirtyTrackerReliable && dirtyTrackerBits.orR
      maintenanceTrackedDirty := useDirtyTracker
      when(!io.maintenanceInvalidate && dirtyLineCount === 0.U) {
        // 没有脏 line 的普通 FENCE 无需读取阵列；保持 valid/tag 及 I$ 内容不变。
        drained := true.B
        state := sMaintenanceDone
      }.otherwise {
        maintenanceSet := Mux(useDirtyTracker, dirtyTrackerSet(PriorityEncoder(dirtyTrackerBits)), 0.U)
        maintenanceWay := Mux(useDirtyTracker, dirtyTrackerWay(PriorityEncoder(dirtyTrackerBits)), 0.U)
        state := sMaintenanceIssue
      }
    }
  }
}
