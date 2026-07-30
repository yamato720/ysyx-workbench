package npc

import chisel3._
import chisel3.util._
import npc.protocol._

/**
  * Blocking, single-MSHR AXI-Lite cache controller.
  *
  * Refills and dirty writebacks are deliberately emitted as one AXI-Lite beat
  * at a time. The external AXI4 bridge and board ports therefore keep their
  * existing non-burst ABI.
  */
class CacheController(
  cache: CacheConfig,
  addrWidth: Int,
  dataWidth: Int,
  mainMemoryBase: Long,
  mainMemorySize: Long,
  readOnly: Boolean
) extends Module {
  require(cache.enabled, "CacheController requires an enabled CacheConfig")
  cache.validate(addrWidth, dataWidth)

  private val geometry = cache.geometry
  private val sets = geometry.sets
  private val ways = geometry.ways
  private val beatBytes = dataWidth / 8
  private val beats = geometry.lineBytes / beatBytes
  private val lineWidth = beats * dataWidth
  private val setWidth = math.max(1, log2Ceil(sets))
  private val wayWidth = math.max(1, log2Ceil(ways))
  private val beatWidth = math.max(1, log2Ceil(beats))
  private val tagWidth = geometry.tagBits(addrWidth)
  private val fullStrobe = ((BigInt(1) << beatBytes) - 1).U(beatBytes.W)
  private val fullBeatSize = log2Ceil(beatBytes).U(3.W)

  val io = IO(new Bundle {
    val cpu = Flipped(new AxiLiteMasterIO(addrWidth, dataWidth))
    val memory = new AxiLiteMasterIO(addrWidth, dataWidth)
    val maintenanceRequest = Input(Bool())
    val maintenanceInvalidate = Input(Bool())
    val maintenanceDone = Output(Bool())
    val drained = Output(Bool())
    val statistics = Output(new CacheStatistics)
  })

  val array = Module(new CacheArray(cache, addrWidth, dataWidth, hasDirty = !readOnly))
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
  val reqStrobe = Reg(UInt(beatBytes.W))
  val reqSet = CacheAddress.set(reqAddr, geometry)
  val reqTag = CacheAddress.tag(reqAddr, geometry, addrWidth)
  val reqBeat = CacheAddress.beat(reqAddr, geometry, beatBytes)

  val awCaptured = RegInit(false.B)
  val awAddr = Reg(UInt(addrWidth.W))
  val awSize = Reg(UInt(3.W))
  val awProt = Reg(UInt(3.W))
  val wCaptured = RegInit(false.B)
  val wData = Reg(UInt(dataWidth.W))
  val wStrobe = Reg(UInt(beatBytes.W))

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
  val writebackBeat = RegInit(0.U(beatWidth.W))
  val writebackMaintenance = RegInit(false.B)
  val refillLine = Reg(UInt(lineWidth.W))
  val refillBeat = RegInit(0.U(beatWidth.W))

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

  def lineBeat(line: UInt, beat: UInt): UInt = {
    val vector = line.asTypeOf(Vec(beats, UInt(dataWidth.W)))
    vector(beat)
  }

  def selectLine(index: UInt): UInt =
    if (ways == 1) array.io.readLines(0) else array.io.readLines(index)

  def selectMeta(index: UInt): CacheTagMeta =
    if (ways == 1) array.io.readMeta(0) else array.io.readMeta(index)

  def replaceBeat(line: UInt, beat: UInt, data: UInt): UInt = {
    val next = Wire(Vec(beats, UInt(dataWidth.W)))
    next := line.asTypeOf(Vec(beats, UInt(dataWidth.W)))
    next(beat) := data
    next.asUInt
  }

  def writeMask(strobe: UInt): UInt =
    VecInit((0 until beatBytes).map(lane => Fill(8, strobe(lane)))).asUInt

  def mergeWrite(oldData: UInt, data: UInt, strobe: UInt): UInt = {
    val mask = writeMask(strobe)
    (oldData & ~mask) | (data & mask)
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
  io.memory.aw.bits.addr := reqAddr
  io.memory.aw.bits.size := reqSize
  io.memory.aw.bits.prot := reqProt
  io.memory.w.valid := false.B
  io.memory.w.bits.data := reqData
  io.memory.w.bits.strb := reqStrobe
  io.memory.b.ready := false.B
  io.memory.ar.valid := false.B
  io.memory.ar.bits.addr := reqAddr
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
    Mux(writebackMaintenance, maintenanceSet, reqSet)) + (writebackBeat * beatBytes.U)
  val refillAddress = CacheAddress.lineBase(reqAddr, geometry, addrWidth) + (refillBeat * beatBytes.U)

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
          val updatedBeat = mergeWrite(lineBeat(hitLine, reqBeat), reqData, reqStrobe)
          if (cache.policy.write == CacheWritePolicy.WriteBack) {
            array.io.dataWriteEnable := true.B
            array.io.dataWriteWay := hitWay
            array.io.dataWriteLine := replaceBeat(hitLine, reqBeat, updatedBeat)
            array.io.metaWriteEnable := true.B
            array.io.metaWriteWay := hitWay
            array.io.metaWrite.valid := true.B
            array.io.metaWrite.tag := reqTag
            array.io.metaWrite.dirty := true.B
            drained := false.B
            responseCode := AxiLiteResp.OKAY
            state := sRespondWrite
          } else {
            beginWriteThrough(replaceBeat(hitLine, reqBeat, updatedBeat), hitWay)
          }
        }.otherwise {
          responseData := lineBeat(hitLine, reqBeat)
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
        responseData := io.memory.r.bits.data
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
      io.memory.aw.bits.size := fullBeatSize
      io.memory.aw.bits.prot := 0.U
      io.memory.w.valid := !sendWDone
      io.memory.w.bits.data := lineBeat(victimLine, writebackBeat)
      io.memory.w.bits.strb := fullStrobe
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
        }.elsewhen(writebackBeat === (beats - 1).U) {
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
      io.memory.ar.bits.size := fullBeatSize
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
          val completedLine = replaceBeat(refillLine, refillBeat, io.memory.r.bits.data)
          when(refillBeat === (beats - 1).U) {
            val installedLine = WireDefault(completedLine)
            when(reqWrite && (cache.policy.write == CacheWritePolicy.WriteBack).B) {
              installedLine := replaceBeat(completedLine, reqBeat,
                mergeWrite(lineBeat(completedLine, reqBeat), reqData, reqStrobe))
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
                beginWriteThrough(replaceBeat(completedLine, reqBeat,
                  mergeWrite(lineBeat(completedLine, reqBeat), reqData, reqStrobe)), victimWay)
              }
            }.otherwise {
              responseData := lineBeat(completedLine, reqBeat)
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
