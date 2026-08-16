package npc

import chisel3._
import chisel3.util._
import npc.protocol._

/**
  * Blocking, single-MSHR AXI-Lite cache controller.
  *
  * Refills and dirty writebacks are deliberately emitted as one AXI-Lite beat
  * at a time. The CPU-side port can remain XLEN-wide while the line-memory
  * port is wider, allowing a 64-byte line to use one 512-bit HBM AXI beat.
  */
class CacheController(
  cache: CacheConfig,
  addrWidth: Int,
  dataWidth: Int,
  mainMemoryBase: Long,
  mainMemorySize: Long,
  readOnly: Boolean,
  memoryDataWidth: Int = 0
) extends Module {
  require(cache.enabled, "CacheController requires an enabled CacheConfig")
  private val effectiveMemoryDataWidth = if (memoryDataWidth == 0) dataWidth else memoryDataWidth
  cache.validate(addrWidth, dataWidth)
  cache.validateMemoryBus(effectiveMemoryDataWidth)
  require(effectiveMemoryDataWidth >= dataWidth &&
    (effectiveMemoryDataWidth & (effectiveMemoryDataWidth - 1)) == 0,
    s"cache memory width must be a power of two no narrower than CPU width ($dataWidth), got $effectiveMemoryDataWidth")

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
  private val memoryFullStrobe = ((BigInt(1) << memoryBeatBytes) - 1).U(memoryBeatBytes.W)
  private val memoryFullBeatSize = log2Ceil(memoryBeatBytes).U(3.W)
  private val memoryLaneBits = log2Ceil(memoryBeatBytes)
  private val cpuLaneBits = log2Ceil(cpuBeatBytes)

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

  val Seq(sIdle, sCollectWrite, sLookupIssue, sLookupEvaluate,
    sRespondRead, sRespondWrite, sPassReadAddress, sPassReadData,
    sPassWriteSend, sPassWriteResponse, sWritebackSend, sWritebackResponse,
    sRefillAddress, sRefillData, sMaintenanceIssue, sMaintenanceInspect,
    sMaintenanceDone) = Enum(17)
  val state = RegInit(sIdle)

  val reqWrite = RegInit(false.B)
  val reqAddr = Reg(UInt(addrWidth.W))
  val reqSize = Reg(UInt(3.W))
  val reqProt = Reg(UInt(3.W))
  val reqData = Reg(UInt(dataWidth.W))
  val reqStrobe = Reg(UInt(cpuBeatBytes.W))
  val reqSet = CacheAddress.set(reqAddr, geometry)
  val reqTag = CacheAddress.tag(reqAddr, geometry, addrWidth)
  val reqBeat = CacheAddress.beat(reqAddr, geometry, cpuBeatBytes)

  val awCaptured = RegInit(false.B)
  val awAddr = Reg(UInt(addrWidth.W))
  val awSize = Reg(UInt(3.W))
  val awProt = Reg(UInt(3.W))
  val wCaptured = RegInit(false.B)
  val wData = Reg(UInt(dataWidth.W))
  val wStrobe = Reg(UInt(cpuBeatBytes.W))

  val responseData = RegInit(0.U(dataWidth.W))
  val responseCode = RegInit(AxiLiteResp.OKAY)
  val sendAwDone = RegInit(false.B)
  val sendWDone = RegInit(false.B)
  val writeThroughPending = RegInit(false.B)
  val writeThroughWay = Reg(UInt(wayWidth.W))
  val writeThroughLine = Reg(UInt(lineWidth.W))

  val victimWay = Reg(UInt(wayWidth.W))
  val victimTag = Reg(UInt(tagWidth.W))
  val victimLine = Reg(UInt(lineWidth.W))
  val writebackBeat = RegInit(0.U(memoryBeatWidth.W))
  val writebackMaintenance = RegInit(false.B)
  val refillLine = Reg(UInt(lineWidth.W))
  val refillBeat = RegInit(0.U(memoryBeatWidth.W))

  val maintenanceSet = RegInit(0.U(setWidth.W))
  val maintenanceWay = RegInit(0.U(wayWidth.W))
  val maintenanceInvalidate = RegInit(false.B)
  val drained = RegInit(true.B)

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

  def isMainMemory(addr: UInt): Bool =
    addr >= mainMemoryBase.U(addrWidth.W) &&
      addr < (mainMemoryBase + mainMemorySize).U(addrWidth.W)

  def cpuLineBeat(line: UInt, beat: UInt): UInt = {
    val vector = line.asTypeOf(Vec(cpuBeats, UInt(dataWidth.W)))
    vector(beat)
  }

  def memoryLineBeat(line: UInt, beat: UInt): UInt = {
    if (memoryBeats == 1) line
    else {
      val vector = line.asTypeOf(Vec(memoryBeats, UInt(effectiveMemoryDataWidth.W)))
      vector(beat)
    }
  }

  def selectLine(index: UInt): UInt =
    if (ways == 1) array.io.readLines(0) else array.io.readLines(index)

  def selectMeta(index: UInt): CacheTagMeta =
    if (ways == 1) array.io.readMeta(0) else array.io.readMeta(index)

  def replaceCpuBeat(line: UInt, beat: UInt, data: UInt): UInt = {
    val next = Wire(Vec(cpuBeats, UInt(dataWidth.W)))
    next := line.asTypeOf(Vec(cpuBeats, UInt(dataWidth.W)))
    next(beat) := data
    next.asUInt
  }

  def replaceMemoryBeat(line: UInt, beat: UInt, data: UInt): UInt = {
    if (memoryBeats == 1) data
    else {
      val next = Wire(Vec(memoryBeats, UInt(effectiveMemoryDataWidth.W)))
      next := line.asTypeOf(Vec(memoryBeats, UInt(effectiveMemoryDataWidth.W)))
      next(beat) := data
      next.asUInt
    }
  }

  def writeMask(strobe: UInt): UInt =
    VecInit((0 until cpuBeatBytes).map(lane => Fill(8, strobe(lane)))).asUInt

  def mergeWrite(oldData: UInt, data: UInt, strobe: UInt): UInt = {
    val mask = writeMask(strobe)
    (oldData & ~mask) | (data & mask)
  }

  // CPU data/strobe 已位于其 XLEN 宽 word 内的正确位置。应把该 word 放入宽主存的
  // 对应 word 位置，而不能再按原始 byte offset 移位；后者会丢失宽 beat 最后一个
  // CPU word 的访问，例如位于 512-bit beat 第 60 byte 的 32-bit store 会移出 bit 511。
  private val memoryWordOffset =
    if (memoryBeatBytes == cpuBeatBytes) 0.U(1.W)
    else reqAddr(memoryLaneBits - 1, cpuLaneBits)
  private val memoryDataShift = memoryWordOffset << log2Ceil(dataWidth)
  private val memoryStrobeShift = memoryWordOffset << cpuLaneBits
  private val memoryRequestAddress =
    if (memoryBeatBytes == cpuBeatBytes) reqAddr
    else Mux(isMainMemory(reqAddr),
      Cat(reqAddr(addrWidth - 1, memoryLaneBits), 0.U(memoryLaneBits.W)), reqAddr)

  /** 将 CPU 宽度的 bypass/write-through 事务展开到选中的宽 AXI lane。 */
  def expandCpuData(data: UInt): UInt = {
    if (effectiveMemoryDataWidth == dataWidth) data
    else (Cat(0.U((effectiveMemoryDataWidth - dataWidth).W), data) << memoryDataShift)(effectiveMemoryDataWidth - 1, 0)
  }

  def expandCpuStrobe(strobe: UInt): UInt = {
    if (effectiveMemoryDataWidth == dataWidth) strobe
    else (Cat(0.U((memoryBeatBytes - cpuBeatBytes).W), strobe) << memoryStrobeShift)(memoryBeatBytes - 1, 0)
  }

  def extractCpuData(data: UInt): UInt = {
    if (effectiveMemoryDataWidth == dataWidth) data
    else (data >> memoryDataShift)(dataWidth - 1, 0)
  }

  def victimBase(tag: UInt, set: UInt): UInt = {
    if (geometry.indexBits == 0) Cat(tag, 0.U(geometry.offsetBits.W))
    else Cat(tag, set(geometry.indexBits - 1, 0), 0.U(geometry.offsetBits.W))
  }

  def beginRequest(address: UInt, write: Bool): Unit = {
    when(isMainMemory(address)) {
      state := sLookupIssue
    }.otherwise {
      writeThroughPending := false.B
      state := Mux(write, sPassWriteSend, sPassReadAddress)
      sendAwDone := false.B
      sendWDone := false.B
    }
  }

  def beginPassWrite(): Unit = {
    writeThroughPending := false.B
    sendAwDone := false.B
    sendWDone := false.B
    state := sPassWriteSend
  }

  def beginWriteThrough(line: UInt, way: UInt): Unit = {
    beginPassWrite()
    writeThroughPending := true.B
    writeThroughLine := line
    writeThroughWay := way
  }

  def beginRefill(): Unit = {
    refillLine := 0.U
    refillBeat := 0.U
    state := sRefillAddress
  }

  def advanceMaintenance(): Unit = {
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

  // CPU-side defaults. Write channels are collected independently, as AXI-Lite permits.
  val collectingWrite = (state === sIdle || state === sCollectWrite) && !io.maintenanceRequest
  io.cpu.aw.ready := collectingWrite && !awCaptured
  io.cpu.w.ready := collectingWrite && !wCaptured
  val writeIntent = awCaptured || wCaptured || io.cpu.aw.valid || io.cpu.w.valid
  io.cpu.ar.ready := state === sIdle && !io.maintenanceRequest && !writeIntent
  io.cpu.b.valid := state === sRespondWrite
  io.cpu.b.bits.resp := responseCode
  io.cpu.r.valid := state === sRespondRead
  io.cpu.r.bits.data := responseData
  io.cpu.r.bits.resp := responseCode

  // Downstream defaults.
  io.memory.aw.valid := false.B
  io.memory.aw.bits.addr := memoryRequestAddress
  io.memory.aw.bits.size := reqSize
  io.memory.aw.bits.prot := reqProt
  io.memory.w.valid := false.B
  io.memory.w.bits.data := expandCpuData(reqData)
  io.memory.w.bits.strb := expandCpuStrobe(reqStrobe)
  io.memory.b.ready := false.B
  io.memory.ar.valid := false.B
  io.memory.ar.bits.addr := memoryRequestAddress
  io.memory.ar.bits.size := reqSize
  io.memory.ar.bits.prot := reqProt
  io.memory.r.ready := false.B

  array.io.readEnable := false.B
  array.io.readSet := Mux(state === sMaintenanceIssue || state === sMaintenanceInspect,
    maintenanceSet, reqSet)
  array.io.dataWriteEnable := false.B
  array.io.dataWriteSet := reqSet
  array.io.dataWriteWay := victimWay
  array.io.dataWriteLine := refillLine
  array.io.metaWriteEnable := false.B
  array.io.metaWriteSet := reqSet
  array.io.metaWriteWay := victimWay
  array.io.metaWrite := 0.U.asTypeOf(new CacheTagMeta(tagWidth))
  array.io.validEpoch := 0.U
  replacement.io.querySet := reqSet
  replacement.io.accessValid := false.B
  replacement.io.replaceValid := false.B
  replacement.io.accessSet := reqSet
  replacement.io.accessWay := 0.U

  val hitVector = VecInit(array.io.readMeta.map(meta => meta.valid && meta.tag === reqTag))
  val hit = hitVector.asUInt.orR
  val hitWay = PriorityEncoder(hitVector.asUInt)
  val invalidVector = VecInit(array.io.readMeta.map(meta => !meta.valid))
  val selectedVictim = Mux(invalidVector.asUInt.orR,
    PriorityEncoder(invalidVector.asUInt), replacement.io.victimWay)
  val allocateMiss = Mux(reqWrite,
    (cache.policy.writeMiss == CacheWriteMissPolicy.WriteAllocate).B,
    (cache.policy.readMiss == CacheReadMissPolicy.ReadAllocate).B)

  val writebackAddress = victimBase(victimTag,
    Mux(writebackMaintenance, maintenanceSet, reqSet)) + (writebackBeat * memoryBeatBytes.U)
  val refillAddress = CacheAddress.lineBase(reqAddr, geometry, addrWidth) + (refillBeat * memoryBeatBytes.U)

  switch(state) {
    is(sIdle) {
      when(io.maintenanceRequest) {
        maintenanceSet := 0.U
        maintenanceWay := 0.U
        maintenanceInvalidate := io.maintenanceInvalidate
        state := sMaintenanceIssue
      }.elsewhen(io.cpu.ar.fire) {
        reqWrite := false.B
        reqAddr := io.cpu.ar.bits.addr
        reqSize := io.cpu.ar.bits.size
        reqProt := io.cpu.ar.bits.prot
        beginRequest(io.cpu.ar.bits.addr, false.B)
      }.otherwise {
        val acceptedAw = io.cpu.aw.fire
        val acceptedW = io.cpu.w.fire
        when(acceptedAw) {
          awCaptured := true.B
          awAddr := io.cpu.aw.bits.addr
          awSize := io.cpu.aw.bits.size
          awProt := io.cpu.aw.bits.prot
        }
        when(acceptedW) {
          wCaptured := true.B
          wData := io.cpu.w.bits.data
          wStrobe := io.cpu.w.bits.strb
        }
        val haveAw = awCaptured || acceptedAw
        val haveW = wCaptured || acceptedW
        when(haveAw && haveW) {
          val address = Mux(awCaptured, awAddr, io.cpu.aw.bits.addr)
          reqWrite := true.B
          reqAddr := address
          reqSize := Mux(awCaptured, awSize, io.cpu.aw.bits.size)
          reqProt := Mux(awCaptured, awProt, io.cpu.aw.bits.prot)
          reqData := Mux(wCaptured, wData, io.cpu.w.bits.data)
          reqStrobe := Mux(wCaptured, wStrobe, io.cpu.w.bits.strb)
          awCaptured := false.B
          wCaptured := false.B
          beginRequest(address, true.B)
        }.elsewhen(acceptedAw || acceptedW) {
          state := sCollectWrite
        }
      }
    }

    is(sCollectWrite) {
      val acceptedAw = io.cpu.aw.fire
      val acceptedW = io.cpu.w.fire
      when(acceptedAw) {
        awCaptured := true.B
        awAddr := io.cpu.aw.bits.addr
        awSize := io.cpu.aw.bits.size
        awProt := io.cpu.aw.bits.prot
      }
      when(acceptedW) {
        wCaptured := true.B
        wData := io.cpu.w.bits.data
        wStrobe := io.cpu.w.bits.strb
      }
      val haveAw = awCaptured || acceptedAw
      val haveW = wCaptured || acceptedW
      when(haveAw && haveW) {
        val address = Mux(awCaptured, awAddr, io.cpu.aw.bits.addr)
        reqWrite := true.B
        reqAddr := address
        reqSize := Mux(awCaptured, awSize, io.cpu.aw.bits.size)
        reqProt := Mux(awCaptured, awProt, io.cpu.aw.bits.prot)
        reqData := Mux(wCaptured, wData, io.cpu.w.bits.data)
        reqStrobe := Mux(wCaptured, wStrobe, io.cpu.w.bits.strb)
        awCaptured := false.B
        wCaptured := false.B
        beginRequest(address, true.B)
      }
    }

    is(sLookupIssue) {
      array.io.readEnable := true.B
      state := sLookupEvaluate
    }

    is(sLookupEvaluate) {
      when(hit) {
        hits := hits + 1.U
        replacement.io.accessValid := true.B
        replacement.io.accessWay := hitWay
        val hitLine = selectLine(hitWay)
        when(reqWrite) {
          val updatedBeat = mergeWrite(cpuLineBeat(hitLine, reqBeat), reqData, reqStrobe)
          if (cache.policy.write == CacheWritePolicy.WriteBack) {
            array.io.dataWriteEnable := true.B
            array.io.dataWriteWay := hitWay
            array.io.dataWriteLine := replaceCpuBeat(hitLine, reqBeat, updatedBeat)
            array.io.metaWriteEnable := true.B
            array.io.metaWriteWay := hitWay
            array.io.metaWrite.valid := true.B
            array.io.metaWrite.tag := reqTag
            array.io.metaWrite.dirty := true.B
            drained := false.B
            responseCode := AxiLiteResp.OKAY
            state := sRespondWrite
          } else {
            beginWriteThrough(replaceCpuBeat(hitLine, reqBeat, updatedBeat), hitWay)
          }
        }.otherwise {
          responseData := cpuLineBeat(hitLine, reqBeat)
          responseCode := AxiLiteResp.OKAY
          state := sRespondRead
        }
      }.otherwise {
        misses := misses + 1.U
        when(!allocateMiss) {
          when(reqWrite) { beginPassWrite() }.otherwise { state := sPassReadAddress }
        }.otherwise {
          victimWay := selectedVictim
          victimTag := selectMeta(selectedVictim).tag
          victimLine := selectLine(selectedVictim)
          when(selectMeta(selectedVictim).valid) { evictions := evictions + 1.U }
          when(selectMeta(selectedVictim).valid && selectMeta(selectedVictim).dirty) {
            writebackBeat := 0.U
            writebackMaintenance := false.B
            sendAwDone := false.B
            sendWDone := false.B
            writebacks := writebacks + 1.U
            state := sWritebackSend
          }.otherwise {
            beginRefill()
          }
        }
      }
    }

    is(sRespondRead) {
      when(io.cpu.r.fire) { state := sIdle }
    }
    is(sRespondWrite) {
      when(io.cpu.b.fire) { state := sIdle }
    }

    is(sPassReadAddress) {
      io.memory.ar.valid := true.B
      when(io.memory.ar.fire) { state := sPassReadData }
    }
    is(sPassReadData) {
      io.memory.r.ready := true.B
      when(io.memory.r.fire) {
        responseData := extractCpuData(io.memory.r.bits.data)
        responseCode := io.memory.r.bits.resp
        state := sRespondRead
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
        responseCode := io.memory.b.bits.resp
        when(writeThroughPending && io.memory.b.bits.resp === AxiLiteResp.OKAY) {
          array.io.dataWriteEnable := true.B
          array.io.dataWriteWay := writeThroughWay
          array.io.dataWriteLine := writeThroughLine
        }
        writeThroughPending := false.B
        state := sRespondWrite
      }
    }

    is(sWritebackSend) {
      io.memory.aw.valid := !sendAwDone
      io.memory.aw.bits.addr := writebackAddress
      io.memory.aw.bits.size := memoryFullBeatSize
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
          responseCode := io.memory.b.bits.resp
          when(writebackMaintenance) {
            state := sMaintenanceDone
          }.elsewhen(reqWrite) {
            state := sRespondWrite
          }.otherwise {
            responseData := 0.U
            state := sRespondRead
          }
        }.elsewhen(writebackBeat === (memoryBeats - 1).U) {
          array.io.metaWriteEnable := true.B
          array.io.metaWriteSet := Mux(writebackMaintenance, maintenanceSet, reqSet)
          array.io.metaWriteWay := victimWay
          array.io.metaWrite.valid := !writebackMaintenance || !maintenanceInvalidate
          array.io.metaWrite.dirty := false.B
          array.io.metaWrite.tag := victimTag
          when(writebackMaintenance) {
            advanceMaintenance()
          }.otherwise {
            beginRefill()
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
      io.memory.ar.bits.size := memoryFullBeatSize
      io.memory.ar.bits.prot := reqProt
      when(io.memory.ar.fire) { state := sRefillData }
    }
    is(sRefillData) {
      io.memory.r.ready := true.B
      when(io.memory.r.fire) {
        when(io.memory.r.bits.resp =/= AxiLiteResp.OKAY) {
          responseCode := io.memory.r.bits.resp
          when(reqWrite) { state := sRespondWrite }.otherwise {
            responseData := 0.U
            state := sRespondRead
          }
        }.otherwise {
          val completedLine = replaceMemoryBeat(refillLine, refillBeat, io.memory.r.bits.data)
          when(refillBeat === (memoryBeats - 1).U) {
            val installedLine = WireDefault(completedLine)
            when(reqWrite && (cache.policy.write == CacheWritePolicy.WriteBack).B) {
              installedLine := replaceCpuBeat(completedLine, reqBeat,
                mergeWrite(cpuLineBeat(completedLine, reqBeat), reqData, reqStrobe))
            }
            array.io.dataWriteEnable := true.B
            array.io.dataWriteWay := victimWay
            array.io.dataWriteLine := installedLine
            array.io.metaWriteEnable := true.B
            array.io.metaWriteWay := victimWay
            array.io.metaWrite.valid := true.B
            array.io.metaWrite.tag := reqTag
            array.io.metaWrite.dirty := reqWrite &&
              (cache.policy.write == CacheWritePolicy.WriteBack).B
            replacement.io.accessValid := true.B
            replacement.io.replaceValid := true.B
            replacement.io.accessWay := victimWay
            refills := refills + 1.U
            when(reqWrite) {
              if (cache.policy.write == CacheWritePolicy.WriteBack) {
                drained := false.B
                responseCode := AxiLiteResp.OKAY
                state := sRespondWrite
              } else {
                beginWriteThrough(replaceCpuBeat(completedLine, reqBeat,
                  mergeWrite(cpuLineBeat(completedLine, reqBeat), reqData, reqStrobe)), victimWay)
              }
            }.otherwise {
              responseData := cpuLineBeat(completedLine, reqBeat)
              responseCode := AxiLiteResp.OKAY
              state := sRespondRead
            }
          }.otherwise {
            refillLine := completedLine
            refillBeat := refillBeat + 1.U
            state := sRefillAddress
          }
        }
      }
    }

    is(sMaintenanceIssue) {
      array.io.readEnable := true.B
      state := sMaintenanceInspect
    }
    is(sMaintenanceInspect) {
      val meta = selectMeta(maintenanceWay)
      when(meta.valid && meta.dirty) {
        victimWay := maintenanceWay
        victimTag := meta.tag
        victimLine := selectLine(maintenanceWay)
        writebackBeat := 0.U
        writebackMaintenance := true.B
        sendAwDone := false.B
        sendWDone := false.B
        writebacks := writebacks + 1.U
        state := sWritebackSend
      }.otherwise {
        when(meta.valid && maintenanceInvalidate) {
          array.io.metaWriteEnable := true.B
          array.io.metaWriteSet := maintenanceSet
          array.io.metaWriteWay := maintenanceWay
          array.io.metaWrite.valid := false.B
          array.io.metaWrite.dirty := false.B
          array.io.metaWrite.tag := meta.tag
        }
        advanceMaintenance()
      }
    }
    is(sMaintenanceDone) {
      when(!io.maintenanceRequest) { state := sIdle }
    }
  }
}
