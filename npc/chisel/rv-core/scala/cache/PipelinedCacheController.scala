package npc

import chisel3._
import chisel3.util._
import npc.protocol.{AxiLiteMasterIO, AxiLiteResp}

/** 两拍缓存内部保存的完整 CPU-side AXI-Lite 请求。 */
class PipelinedCacheRequest(addrWidth: Int, dataWidth: Int) extends Bundle {
  val write = Bool()
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
  memoryDataWidth: Int = 0
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
  private val tagWidth = geometry.tagBits(addrWidth)
  private val cpuLaneBits = log2Ceil(cpuBeatBytes)
  private val memoryLaneBits = log2Ceil(memoryBeatBytes)
  private val memoryFullStrobe = ((BigInt(1) << memoryBeatBytes) - 1).U(memoryBeatBytes.W)
  private val memoryFullSize = log2Ceil(memoryBeatBytes).U(3.W)

  cache.validate(addrWidth, dataWidth)
  cache.validateMemoryBus(effectiveMemoryDataWidth)
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

  val array = Module(new CacheArray(cache, addrWidth, effectiveMemoryDataWidth, hasDirty = !readOnly))
  val replacement = Module(new CacheReplacementUnit(sets, ways, cache.replacement))
  val requestQueue = Module(new Queue(new PipelinedCacheRequest(addrWidth, dataWidth),
    queues.requestDepth, pipe = false, flow = true))
  val responseQueue = Module(new Queue(new PipelinedCacheResponse(dataWidth),
    queues.responseDepth, pipe = false, flow = true))

  val sRun :: sWritebackSend :: sWritebackResponse :: sRefillAddress :: sRefillData :: sPassReadAddress :: sPassReadData :: sPassWriteSend :: sPassWriteResponse :: sRespond :: sMaintenanceIssue :: sMaintenanceInspect :: sMaintenanceDone :: Nil = Enum(13)
  val state = RegInit(sRun)

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

  val maintenanceSet = RegInit(0.U(setWidth.W))
  val maintenanceWay = RegInit(0.U(wayWidth.W))
  val maintenanceInvalidate = RegInit(false.B)
  val drained = RegInit(true.B)

  // 同一 line 的连续 store/load 可能先于同步阵列写入可见；保存完整 line，避免下一笔
  // S0 读到旧快照后覆盖较老 store 的其他 CPU word。
  val lastStoreValid = RegInit(false.B)
  val lastStoreLine = RegInit(0.U(addrWidth.W))
  val lastStoreLineData = RegInit(0.U(lineWidth.W))

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

  def lineBase(address: UInt): UInt = CacheAddress.lineBase(address, geometry, addrWidth)

  def victimBase(tag: UInt, set: UInt): UInt =
    if (geometry.indexBits == 0) Cat(tag, 0.U(geometry.offsetBits.W))
    else Cat(tag, set(geometry.indexBits - 1, 0), 0.U(geometry.offsetBits.W))

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

  val entranceOpen = state === sRun && !io.maintenanceRequest
  val assembledAw = awHeld || io.cpu.aw.valid
  val assembledW = wHeld || io.cpu.w.valid
  val writeReadyToEnqueue = assembledAw && assembledW
  val writeOwnsEntrance = awHeld || wHeld || io.cpu.aw.valid || io.cpu.w.valid
  val readReadyToEnqueue = io.cpu.ar.valid && !writeOwnsEntrance

  io.cpu.aw.ready := entranceOpen && !awHeld &&
    (!writeReadyToEnqueue || requestQueue.io.enq.ready)
  io.cpu.w.ready := entranceOpen && !wHeld &&
    (!writeReadyToEnqueue || requestQueue.io.enq.ready)
  io.cpu.ar.ready := entranceOpen && !writeOwnsEntrance && requestQueue.io.enq.ready

  requestQueue.io.enq.valid := entranceOpen && (writeReadyToEnqueue || readReadyToEnqueue)
  requestQueue.io.enq.bits.write := writeReadyToEnqueue
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

  io.cpu.r.valid := responseQueue.io.deq.valid && !responseQueue.io.deq.bits.write
  io.cpu.r.bits.data := responseQueue.io.deq.bits.data
  io.cpu.r.bits.resp := responseQueue.io.deq.bits.resp
  io.cpu.b.valid := responseQueue.io.deq.valid && responseQueue.io.deq.bits.write
  io.cpu.b.bits.resp := responseQueue.io.deq.bits.resp
  responseQueue.io.deq.ready := Mux(responseQueue.io.deq.bits.write, io.cpu.b.ready, io.cpu.r.ready)

  val hitVector = VecInit(array.io.readMeta.map(meta => meta.valid &&
    meta.tag === CacheAddress.tag(s0Request.addr, geometry, addrWidth)))
  val readHit = hitVector.asUInt.orR
  val readHitWay = PriorityEncoder(hitVector.asUInt)
  val invalidVector = VecInit(array.io.readMeta.map(meta => !meta.valid))
  val selectedVictim = Mux(invalidVector.asUInt.orR,
    PriorityEncoder(invalidVector.asUInt), replacement.io.victimWay)

  val s0SelectedLine = if (ways == 1) array.io.readLines(0) else array.io.readLines(readHitWay)
  val s0SelectedMeta = if (ways == 1) array.io.readMeta(0) else array.io.readMeta(readHitWay)
  val s0Beat = cpuBeat(s0Request.addr)
  val s0ForwardedLine = Mux(lastStoreValid && lastStoreLine === lineBase(s0Request.addr),
    lastStoreLineData, s0SelectedLine)
  val s0ReadWord = cpuLineBeat(s0ForwardedLine, s0Beat)
  // 同一 line 的相邻 store 即使落在不同 CPU word，也必须从完整转发 line 继续合并。
  val s0WriteBase = s0ReadWord
  val s0LoadData = s0WriteBase
  val s0UpdatedWord = mergeWrite(s0WriteBase, s0Request.data, s0Request.strb)
  val s0UpdatedLine = replaceCpuBeat(s0ForwardedLine, s0Beat, s0UpdatedWord)
  val s0MainMemory = isMainMemory(s0Request.addr)
  val s0HitResponse = s0Valid && s0ReadReady && s0MainMemory && readHit &&
    (!s0Request.write || readOnly.B || (cache.policy.write == CacheWritePolicy.WriteBack).B)
  val s0WriteThroughHit = s0Valid && s0ReadReady && s0MainMemory && readHit && s0Request.write &&
    !readOnly.B && (cache.policy.write == CacheWritePolicy.WriteThrough).B
  // S0 的 hit 在同步阵列读出的本拍直接进入响应 FIFO；miss 或 MMIO 在本拍关闭
  // 新入口，因此不会让较新的命中越过较早的阻塞请求。
  val s0CanComplete = state === sRun && s0HitResponse && responseQueue.io.enq.ready
  val s0Miss = state === sRun && s0Valid && s0ReadReady &&
    !s0HitResponse && !s0WriteThroughHit

  // 维护请求会关闭新入口，但先前已经完成 AR/AW/W 握手的请求必须继续排空；
  // 否则维护起始条件等待 FIFO 为空，而 FIFO 又被维护请求禁止出队，形成死锁。
  requestQueue.io.deq.ready := state === sRun &&
    (!s0Valid || s0CanComplete)
  val s0Issue = requestQueue.io.deq.fire

  val responseEmit = WireDefault(false.B)
  val responseEmitWrite = WireDefault(false.B)
  val responseEmitData = WireDefault(0.U(dataWidth.W))
  val responseEmitCode = WireDefault(AxiLiteResp.OKAY)
  responseQueue.io.enq.valid := responseEmit
  responseQueue.io.enq.bits.write := responseEmitWrite
  responseQueue.io.enq.bits.data := responseEmitData
  responseQueue.io.enq.bits.resp := responseEmitCode

  when(s0HitResponse) {
    responseEmit := true.B
    responseEmitWrite := s0Request.write
    responseEmitData := Mux(s0Request.write, 0.U, s0LoadData)
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
    Mux(s0Issue, CacheAddress.set(requestQueue.io.deq.bits.addr, geometry),
      CacheAddress.set(s0Request.addr, geometry)))
  array.io.dataWriteEnable := false.B
  array.io.dataWriteSet := CacheAddress.set(s0Request.addr, geometry)
  array.io.dataWriteWay := readHitWay
  array.io.dataWriteLine := s0UpdatedLine
  array.io.metaWriteEnable := false.B
  array.io.metaWriteSet := CacheAddress.set(s0Request.addr, geometry)
  array.io.metaWriteWay := readHitWay
  array.io.metaWrite := 0.U.asTypeOf(new CacheTagMeta(tagWidth))
  replacement.io.querySet := CacheAddress.set(s0Request.addr, geometry)
  replacement.io.accessValid := false.B
  replacement.io.replaceValid := false.B
  replacement.io.accessSet := CacheAddress.set(s0Request.addr, geometry)
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

  val s0SelectedVictimLine = if (ways == 1) array.io.readLines(0) else array.io.readLines(selectedVictim)
  val s0SelectedVictimMeta = if (ways == 1) array.io.readMeta(0) else array.io.readMeta(selectedVictim)
  val s0Allocates = Mux(s0Request.write,
    (cache.policy.writeMiss == CacheWriteMissPolicy.WriteAllocate).B,
    (cache.policy.readMiss == CacheReadMissPolicy.ReadAllocate).B)

  val writebackAddress = victimBase(victimTag, victimSet) + writebackBeat * memoryBeatBytes.U
  val refillAddress = lineBase(missRequest.addr) + refillBeat * memoryBeatBytes.U

  // S0 命中完成的同拍可接收下一笔请求；miss 和 write-through 则在入口关闭后
  // 清空 S0，再把当前请求交给原有阻塞状态机。
  when(s0Issue) {
    s0Valid := true.B
    s0Request := requestQueue.io.deq.bits
    s0ReadReady := true.B
  }.elsewhen(s0CanComplete || s0WriteThroughHit || s0Miss) {
    s0Valid := false.B
    s0ReadReady := false.B
  }.elsewhen(s0ReplayRead) {
    s0ReadReady := true.B
  }

  when(s0HitResponse && responseQueue.io.enq.fire) {
    hits := hits + 1.U
    replacement.io.accessValid := true.B
    replacement.io.accessSet := CacheAddress.set(s0Request.addr, geometry)
    replacement.io.accessWay := readHitWay
    when(s0Request.write) {
      if (!readOnly) {
        array.io.dataWriteEnable := true.B
        array.io.dataWriteSet := CacheAddress.set(s0Request.addr, geometry)
        array.io.dataWriteWay := readHitWay
        array.io.dataWriteLine := s0UpdatedLine
        array.io.metaWriteEnable := true.B
        array.io.metaWriteSet := CacheAddress.set(s0Request.addr, geometry)
        array.io.metaWriteWay := readHitWay
        array.io.metaWrite.valid := true.B
        array.io.metaWrite.dirty := (cache.policy.write == CacheWritePolicy.WriteBack).B
        array.io.metaWrite.tag := s0SelectedMeta.tag
        when((cache.policy.write == CacheWritePolicy.WriteBack).B) { drained := false.B }
        lastStoreValid := true.B
        lastStoreLine := lineBase(s0Request.addr)
        lastStoreLineData := s0UpdatedLine
      }
    }
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
  }.elsewhen(s0Miss) {
    misses := misses + 1.U
    missRequest := s0Request
    completeWrite := s0Request.write
    completeData := 0.U
    completeResp := AxiLiteResp.OKAY
    lastStoreValid := false.B
    when(!s0MainMemory || !s0Allocates) {
      sendAwDone := false.B
      sendWDone := false.B
      state := Mux(s0Request.write, sPassWriteSend, sPassReadAddress)
    }.otherwise {
      victimWay := selectedVictim
      victimTag := s0SelectedVictimMeta.tag
      victimLine := s0SelectedVictimLine
      victimSet := CacheAddress.set(s0Request.addr, geometry)
      when(s0SelectedVictimMeta.valid) { evictions := evictions + 1.U }
      when(s0SelectedVictimMeta.valid && s0SelectedVictimMeta.dirty) {
        writebackBeat := 0.U
        writebackMaintenance := false.B
        sendAwDone := false.B
        sendWDone := false.B
        writebacks := writebacks + 1.U
        state := sWritebackSend
      }.otherwise {
        refillLine := 0.U
        refillBeat := 0.U
        state := sRefillAddress
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
            when(maintenanceWay === (ways - 1).U) {
              maintenanceWay := 0.U
              when(maintenanceSet === (sets - 1).U) {
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
          }.otherwise {
            refillLine := 0.U
            refillBeat := 0.U
            state := sRefillAddress
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
          state := sRespond
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
              array.io.dataWriteSet := CacheAddress.set(missRequest.addr, geometry)
              array.io.dataWriteWay := victimWay
              array.io.dataWriteLine := installedLine
              array.io.metaWriteEnable := true.B
              array.io.metaWriteSet := CacheAddress.set(missRequest.addr, geometry)
              array.io.metaWriteWay := victimWay
              array.io.metaWrite.valid := true.B
              array.io.metaWrite.dirty := missRequest.write &&
                (cache.policy.write == CacheWritePolicy.WriteBack).B
              array.io.metaWrite.tag := CacheAddress.tag(missRequest.addr, geometry, addrWidth)
              replacement.io.accessValid := true.B
              replacement.io.replaceValid := true.B
              replacement.io.accessSet := CacheAddress.set(missRequest.addr, geometry)
              replacement.io.accessWay := victimWay
            }
            refills := refills + 1.U
            when(missRequest.write && (cache.policy.write == CacheWritePolicy.WriteBack).B) {
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
              state := sRespond
            }
          }.otherwise {
            refillLine := completedLine
            refillBeat := refillBeat + 1.U
            state := sRefillAddress
          }
        }
      }
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
          array.io.dataWriteSet := CacheAddress.set(missRequest.addr, geometry)
          array.io.dataWriteWay := writeThroughWay
          array.io.dataWriteLine := writeThroughLine
          array.io.metaWriteEnable := true.B
          array.io.metaWriteSet := CacheAddress.set(missRequest.addr, geometry)
          array.io.metaWriteWay := writeThroughWay
          array.io.metaWrite.valid := true.B
          array.io.metaWrite.dirty := false.B
          array.io.metaWrite.tag := CacheAddress.tag(missRequest.addr, geometry, addrWidth)
          replacement.io.accessValid := true.B
          replacement.io.replaceValid := true.B
          replacement.io.accessSet := CacheAddress.set(missRequest.addr, geometry)
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
    is(sMaintenanceDone) {
      when(!io.maintenanceRequest) { state := sRun }
    }
  }

  // 维护仅在入口、S0 和两个 FIFO 均空时启动，防止 FENCE 观察到较早的命中响应。
  val maintenanceCanStart = state === sRun && io.maintenanceRequest && !s0Valid &&
    !requestQueue.io.deq.valid && !responseQueue.io.deq.valid && !awHeld && !wHeld
  when(maintenanceCanStart) {
    maintenanceSet := 0.U
    maintenanceWay := 0.U
    maintenanceInvalidate := io.maintenanceInvalidate
    state := sMaintenanceIssue
  }
}
