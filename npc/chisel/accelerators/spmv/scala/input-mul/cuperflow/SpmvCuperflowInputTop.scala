package accelerators.spmv.inputmul.cuperflow

import accelerators.spmv.SpmvCuperflowConfig
import accelerators.spmv.inputmul.common.SpmvMulEngine
import accelerators.spmv.reader.SpmvXReader
import chisel3._
import chisel3.util._
import npc.ip.axi.Axi4ReadMasterIO

/** 一个 HBM PC 在一个 `(wave, batch)` work 中应处理的独占 X/A range。
  *
  * host 固定采用 `PC p -> sliceGroup(wave * hbmPcCount + p)`：同一 wave 的 16 条
  * work 因而指向完全不同的列区间。一个 wave 的 batch 依次执行，只有 batch 0 的
  * `xLoad` 为 true；后续 batch 复用该 PC 的 active local_X。尾 wave 没有 group 的
  * PC 发送 `active=false, xLoad=false, aBeats=0` 的空 work。
  *
  * `xOffsetBeats` 相对该 PC 的低地址 X 区，`aOffsetBeats` 相对高地址 A 区；二者
  * 都由硬件与各自分区大小比较，不能通过 work 绕过分区。`xWords` 是实际 token 数，
  * 让 decoder 跳过最后一个 512-bit beat 的 lane padding。
  */
final class SpmvCuperflowWork(config: SpmvCuperflowConfig) extends Bundle {
  val active = Bool()
  /** true 时本 work 读取并切换 inactive local_X；false 时复用当前 active X。 */
  val xLoad = Bool()
  val batch = UInt(8.W)
  val xOffsetBeats = UInt(32.W)
  val xBeats = UInt(32.W)
  val xWords = UInt(log2Ceil(config.xMaxEncodedWords + 1).W)
  val xElements = UInt(log2Ceil(config.xWindowSize + 1).W)
  val aOffsetBeats = UInt(32.W)
  val aBeats = UInt(32.W)
  /** A union range 内每条 lane 的有效窗口；offset 相对 aOffsetBeats。 */
  val aLaneOffsetBeats = Vec(config.xWordsPerBeat, UInt(32.W))
  val aLaneBeats = Vec(config.xWordsPerBeat, UInt(32.W))
}

/** 一个 PC 的串行 Cuperflow reader、X preload 和 A/FMUL 控制。 */
private[cuperflow] final class SpmvCuperflowLane(config: SpmvCuperflowConfig, pc: Int)
    extends Module {
  require(pc >= 0 && pc < config.hbmPcCount,
    s"Cuperflow PC 编号越界：$pc/${config.hbmPcCount}")

  private val xWordCountWidth = log2Ceil(config.xMaxEncodedWords + 1)
  private val states = Enum(7)
  private val stateIdle = states(0)
  private val stateRequestX = states(1)
  private val stateReadX = states(2)
  private val stateWaitBarrier = states(3)
  private val stateRequestA = states(4)
  private val stateReadA = states(5)
  private val stateComplete = states(6)

  val io = IO(new Bundle {
    val work = Flipped(Decoupled(new SpmvCuperflowWork(config)))
    /** 由顶层在本轮所有 PC 的 X 均完成后统一拉高。 */
    val allowA = Input(Bool())
    /** 本轮所有 PC 完成后释放 lane，下一轮将写入另一个 ping/pong bank。 */
    val roundReset = Input(Bool())
    val hbm = new Axi4ReadMasterIO(config.axiAddrWidth, config.axiDataWidth, config.axiIdWidth)
    val xLoaded = Output(Bool())
    val aReadStarted = Output(Bool())
    val complete = Output(Bool())
    val error = Output(Bool())
    val activeXBank = Output(Bool())
    val aValidSlotMask = Output(UInt(config.xWordsPerBeat.W))
    val productChecksum = Output(UInt(config.xElementWidth.W))
  })

  private val reader = Module(new SpmvXReader(
    config.axiAddrWidth,
    config.axiDataWidth,
    config.axiIdWidth,
    config.maxOutstandingBursts
  ))
  private val decoder = Module(new SpmvCuperflowXDecoder8(config))
  private val localX = Module(new SpmvCuperflowLocalX(config))
  private val mul = Module(new SpmvMulEngine(config.mulConfig, pc))

  private val state = RegInit(stateIdle)
  private val active = RegInit(false.B)
  private val batch = RegInit(0.U(8.W))
  private val xOffsetBeats = RegInit(0.U(32.W))
  private val xBeats = RegInit(0.U(32.W))
  private val xWordsRemaining = RegInit(0.U(xWordCountWidth.W))
  private val xElements = RegInit(0.U(log2Ceil(config.xWindowSize + 1).W))
  private val aOffsetBeats = RegInit(0.U(32.W))
  private val aBeats = RegInit(0.U(32.W))
  private val aLaneOffsetBeats = RegInit(VecInit(
    Seq.fill(config.xWordsPerBeat)(0.U(32.W))))
  private val aLaneBeats = RegInit(VecInit(
    Seq.fill(config.xWordsPerBeat)(0.U(32.W))))
  private val aBeatIndex = RegInit(0.U(32.W))
  private val xReaderDone = RegInit(false.B)
  private val aReaderDone = RegInit(false.B)
  private val xLoaded = RegInit(false.B)
  private val aReadStarted = RegInit(false.B)
  private val error = RegInit(false.B)
  private val expectedXBeats = (io.work.bits.xWords + (config.xWordsPerBeat - 1).U) >>
    log2Ceil(config.xWordsPerBeat)
  private val xEndBytes = (io.work.bits.xOffsetBeats +& io.work.bits.xBeats) << log2Ceil(config.beatBytes)
  private val aEndBytes = (io.work.bits.aOffsetBeats +& io.work.bits.aBeats) << log2Ceil(config.beatBytes)
  private val aLaneRangeInvalid = VecInit((0 until config.xWordsPerBeat).map { lane =>
    val offset = io.work.bits.aLaneOffsetBeats(lane)
    val length = io.work.bits.aLaneBeats(lane)
    offset > io.work.bits.aBeats || (io.work.bits.aBeats - offset < length)
  }).asUInt.orR
  private val workInvalid = io.work.bits.xLoad && (
    io.work.bits.xElements > config.xWindowSize.U ||
      io.work.bits.xWords > config.xMaxEncodedWords.U ||
      io.work.bits.xBeats =/= expectedXBeats ||
      xEndBytes > config.xRegionBytes.U ||
      aEndBytes > config.aRegionBytes.U ||
      aLaneRangeInvalid ||
      (io.work.bits.aBeats =/= 0.U && io.work.bits.xWords === 0.U)
  )

  io.work.ready := state === stateIdle
  io.hbm <> reader.io.axi
  reader.io.request.valid := state === stateRequestX || state === stateRequestA
  reader.io.request.bits.address := Mux(
    state === stateRequestX,
    config.hbmBase.U(config.axiAddrWidth.W) + (xOffsetBeats << log2Ceil(config.beatBytes)),
    config.aRegionBase.U(config.axiAddrWidth.W) + (aOffsetBeats << log2Ceil(config.beatBytes))
  )
  reader.io.request.bits.beats := Mux(state === stateRequestX, xBeats, aBeats)

  private val decodedWordsThisBeat = Mux(xWordsRemaining > config.xWordsPerBeat.U,
    config.xWordsPerBeat.U(xWordCountWidth.W), xWordsRemaining)
  decoder.io.clear := io.work.fire && io.work.bits.xLoad
  decoder.io.rangeElements := xElements
  decoder.io.input.valid := state === stateReadX && reader.io.output.valid && xWordsRemaining =/= 0.U
  decoder.io.input.bits.data := reader.io.output.bits.data
  decoder.io.input.bits.validWords := decodedWordsThisBeat(log2Ceil(config.xWordsPerBeat + 1) - 1, 0)
  decoder.io.input.bits.last := xWordsRemaining <= config.xWordsPerBeat.U
  localX.io.write <> decoder.io.write
  localX.io.loadBank := !localX.io.activeBank
  localX.io.activate := false.B
  io.activeXBank := localX.io.activeBank
  io.aValidSlotMask := mul.io.aSlotValidMask

  reader.io.output.ready := false.B
  when(state === stateReadX) {
    reader.io.output.ready := decoder.io.input.ready && xWordsRemaining =/= 0.U
  }.elsewhen(state === stateReadA) {
    reader.io.output.ready := mul.io.a.ready
  }

  mul.io.enable := state === stateReadA
  mul.io.clearChecksum := io.work.fire
  mul.io.batch := batch
  mul.io.workExpected := active && aBeats =/= 0.U
  mul.io.pageReady := VecInit(Seq.fill(config.mulConfig.xWindowSize / 64)(true.B))
  mul.io.xWindowReady := state === stateReadA
  mul.io.portSafeOverlap := false.B
  mul.io.xReadData := localX.io.readData
  mul.io.streamsComplete := aReaderDone
  mul.io.aSlotValidMask := VecInit((0 until config.xWordsPerBeat).map { lane =>
    val offset = aLaneOffsetBeats(lane)
    aBeatIndex >= offset && (aBeatIndex - offset) < aLaneBeats(lane)
  }).asUInt
  localX.io.readEnable := mul.io.xReadEnable
  localX.io.readColumn := mul.io.xReadColumn
  mul.io.a.valid := state === stateReadA && reader.io.output.valid
  mul.io.a.bits := reader.io.output.bits
  mul.io.product.foreach(_.ready := true.B)

  when(io.roundReset) {
    state := stateIdle
    active := false.B
    xLoaded := false.B
    aReadStarted := false.B
    xReaderDone := false.B
    aReaderDone := false.B
    xWordsRemaining := 0.U
    aLaneOffsetBeats.foreach(_ := 0.U)
    aLaneBeats.foreach(_ := 0.U)
    aBeatIndex := 0.U
    error := false.B
  }.otherwise {
    when(io.work.fire) {
      active := io.work.bits.active
      batch := io.work.bits.batch
      xOffsetBeats := io.work.bits.xOffsetBeats
      xBeats := io.work.bits.xBeats
      xWordsRemaining := io.work.bits.xWords
      xElements := io.work.bits.xElements
      aOffsetBeats := io.work.bits.aOffsetBeats
      aBeats := io.work.bits.aBeats
      aLaneOffsetBeats := io.work.bits.aLaneOffsetBeats
      aLaneBeats := io.work.bits.aLaneBeats
      aBeatIndex := 0.U
      xReaderDone := false.B
      aReaderDone := false.B
      // X 是每个 PC 的 local_X 状态；即使本轮该 PC 没有 A，也必须把 group X
      // 装入自己的 inactive bank，后续 batch 可能重新使用这个 PC。
      xLoaded := !io.work.bits.xLoad || workInvalid || io.work.bits.xWords === 0.U
      aReadStarted := false.B
      error := workInvalid
      state := Mux(!io.work.bits.xLoad || workInvalid ||
        io.work.bits.xWords === 0.U,
        stateWaitBarrier, stateRequestX)
    }

    when(decoder.io.input.fire) {
      xWordsRemaining := xWordsRemaining - decodedWordsThisBeat
      when(reader.io.output.bits.error) {
        error := true.B
      }
    }
    when(reader.io.done && state === stateReadX) {
      xReaderDone := true.B
    }
    when(reader.io.done && state === stateReadA) {
      aReaderDone := true.B
    }
    when(reader.io.output.fire && state === stateReadA) {
      aBeatIndex := aBeatIndex + 1.U
    }
    when(reader.io.error || decoder.io.error || mul.io.error) {
      error := true.B
    }

    switch(state) {
      is(stateRequestX) {
        when(reader.io.request.fire) {
          state := stateReadX
        }
      }
      is(stateReadX) {
        when(xReaderDone && xWordsRemaining === 0.U && !decoder.io.write.valid && localX.io.writeIdle) {
          localX.io.activate := true.B
          xLoaded := true.B
          state := stateWaitBarrier
        }
      }
      is(stateWaitBarrier) {
        when(io.allowA) {
          when(!active || error || aBeats === 0.U) {
            state := stateComplete
          }.otherwise {
            state := stateRequestA
          }
        }
      }
      is(stateRequestA) {
        when(reader.io.request.fire) {
          aReadStarted := true.B
          state := stateReadA
        }
      }
      is(stateReadA) {
        when(mul.io.computeDone) {
          state := stateComplete
        }
      }
    }
  }

  when(reader.io.request.fire && state === stateRequestA) {
    assert(io.allowA, "Cuperflow A request must wait for the global X preload barrier")
  }
  io.xLoaded := xLoaded
  io.aReadStarted := aReadStarted
  io.complete := state === stateComplete
  io.error := error
  io.productChecksum := mul.io.productChecksum
}

/** Cuperflow 16-PC 输入顶层。
  *
  * 每轮接收每个 PC 各一条 work。各 PC 先并行从各自低地址 X 区读取、串行解码为
  * `2 x 4` ping/pong local-X；只有所有 X 均已切换为 active bank，才同时放行所有
  * PC 对高地址 A 区的读取。顶层不广播 X：每个 work 的 X/A range 都仅属于对应 PC。
  * host 在同一 wave 内按 batch 推进，顶层在所有 A/FMUL 流清空后释放下一条 batch work。
 */
final class SpmvCuperflowInputTop(config: SpmvCuperflowConfig = SpmvCuperflowConfig.Simulation)
    extends Module {
  val io = IO(new Bundle {
    val work = Vec(config.hbmPcCount, Flipped(Decoupled(new SpmvCuperflowWork(config))))
    val hbm = Vec(config.hbmPcCount,
      new Axi4ReadMasterIO(config.axiAddrWidth, config.axiDataWidth, config.axiIdWidth))
    val xPreloaded = Output(Vec(config.hbmPcCount, Bool()))
    val aReadStarted = Output(Vec(config.hbmPcCount, Bool()))
    val complete = Output(Vec(config.hbmPcCount, Bool()))
    val error = Output(Vec(config.hbmPcCount, Bool()))
    val activeXBank = Output(Vec(config.hbmPcCount, Bool()))
    val aValidSlotMask = Output(Vec(config.hbmPcCount, UInt(config.xWordsPerBeat.W)))
    val productChecksumByPc = Output(Vec(config.hbmPcCount, UInt(config.xElementWidth.W)))
    /** 每个 PC 的产品校验旁路，便于 host 对齐 X bank 和 A stream。 */
    val globalXReady = Output(Bool())
    val roundDone = Output(Bool())
    val productChecksum = Output(UInt(config.xElementWidth.W))
  })

  private val lanes = Seq.tabulate(config.hbmPcCount)(pc => Module(new SpmvCuperflowLane(config, pc)))
  private val workSeen = RegInit(VecInit(Seq.fill(config.hbmPcCount)(false.B)))
  private val allWorkSeen = workSeen.asUInt.andR
  private val allXLoaded = VecInit(lanes.map(_.io.xLoaded)).asUInt.andR
  private val allComplete = VecInit(lanes.map(_.io.complete)).asUInt.andR
  private val globalXReady = allWorkSeen && allXLoaded
  private val roundDone = allWorkSeen && allComplete

  for (pc <- 0 until config.hbmPcCount) {
    lanes(pc).io.work <> io.work(pc)
    lanes(pc).io.hbm <> io.hbm(pc)
    lanes(pc).io.allowA := globalXReady
    lanes(pc).io.roundReset := roundDone
    io.xPreloaded(pc) := lanes(pc).io.xLoaded
    io.aReadStarted(pc) := lanes(pc).io.aReadStarted
    io.complete(pc) := lanes(pc).io.complete
    io.error(pc) := lanes(pc).io.error
    io.activeXBank(pc) := lanes(pc).io.activeXBank
    io.aValidSlotMask(pc) := lanes(pc).io.aValidSlotMask
    io.productChecksumByPc(pc) := lanes(pc).io.productChecksum
  }
  when(roundDone) {
    workSeen.foreach(_ := false.B)
  }.otherwise {
    for (pc <- 0 until config.hbmPcCount) {
      when(io.work(pc).fire) {
        workSeen(pc) := true.B
      }
    }
  }

  io.globalXReady := globalXReady
  io.roundDone := roundDone
  io.productChecksum := lanes.map(_.io.productChecksum).reduce(_ ^ _)
}
