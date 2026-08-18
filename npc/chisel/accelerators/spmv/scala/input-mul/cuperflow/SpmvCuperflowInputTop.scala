package accelerators.spmv.inputmul.cuperflow

import accelerators.spmv.SpmvCuperflowConfig
import accelerators.spmv.inputmul.common.SpmvMulEngine
import accelerators.spmv.reader.SpmvXReader
import chisel3._
import chisel3.util._
import npc.ip.axi.Axi4ReadMasterIO

/** 一个 PC 的 map / 读写控制单元。
  *
  * 从低地址 X 区按序读取：1-beat map → xBeats 个 X token → 切到 map 给出的
  * 高地址 A 区间连读并乘法。本组结束后 xPtr 已指向下一张 map。16 个 PC 互不等待。
  */
private[cuperflow] final class SpmvCuperflowLane(config: SpmvCuperflowConfig, pc: Int)
    extends Module {
  require(pc >= 0 && pc < config.hbmPcCount,
    s"Cuperflow PC 编号越界：$pc/${config.hbmPcCount}")

  private val xWordCountWidth = log2Ceil(config.xMaxEncodedWords + 1)
  private val states = Enum(8)
  private val stateIdle = states(0)
  private val stateRequestMap = states(1)
  private val stateReadMap = states(2)
  private val stateRequestX = states(3)
  private val stateReadX = states(4)
  private val stateRequestA = states(5)
  private val stateReadA = states(6)
  private val stateComplete = states(7)

  val io = IO(new Bundle {
    val start = Input(Bool())
    val hbm = new Axi4ReadMasterIO(config.axiAddrWidth, config.axiDataWidth, config.axiIdWidth)
    val done = Output(Bool())
    val error = Output(Bool())
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
  private val error = RegInit(false.B)
  private val xPtrBeats = RegInit(0.U(32.W))
  private val xBeats = RegInit(0.U(32.W))
  private val xWordsRemaining = RegInit(0.U(xWordCountWidth.W))
  private val xElements = RegInit(0.U(log2Ceil(config.xWindowSize + 1).W))
  private val aOffsetBeats = RegInit(0.U(32.W))
  private val aBeats = RegInit(0.U(32.W))
  private val firstBatch = RegInit(0.U(8.W))
  private val lastGroup = RegInit(false.B)
  private val xReaderDone = RegInit(false.B)
  private val aReaderDone = RegInit(false.B)
  private val aBeatIndex = RegInit(0.U(32.W))

  private val mapWords = reader.io.output.bits.data.asTypeOf(
    Vec(config.xWordsPerBeat, UInt(config.xElementWidth.W)))
  private val mapValid = SpmvCuperflowMapMarker.isMarker(mapWords(0))
  private val parsedLast = mapWords(0)(0) || mapWords(3)(48)
  private val parsedXBeats = mapWords(1)(31, 0)
  private val parsedXWords = mapWords(1)(63, 32)
  private val parsedAOffset = mapWords(2)(31, 0)
  private val parsedABeats = mapWords(2)(63, 32)
  private val parsedXElements = mapWords(3)(47, 32)
  private val parsedBatch = mapWords(3)(15, 0)
  private val expectedXBeats = (parsedXWords + (config.xWordsPerBeat - 1).U) >>
    log2Ceil(config.xWordsPerBeat)
  private val xEndBytes = (xPtrBeats + 1.U + parsedXBeats) << log2Ceil(config.beatBytes)
  private val aEndBytes = (parsedAOffset +& parsedABeats) << log2Ceil(config.beatBytes)
  private val mapInvalid = !mapValid ||
    parsedXWords > config.xMaxEncodedWords.U ||
    parsedXElements > config.xWindowSize.U ||
    parsedXBeats =/= expectedXBeats ||
    xEndBytes > config.xRegionBytes.U ||
    aEndBytes > config.aRegionBytes.U ||
    (parsedABeats =/= 0.U && parsedXWords === 0.U)

  io.hbm <> reader.io.axi
  private val requestIsMap = state === stateRequestMap
  private val requestIsX = state === stateRequestX
  reader.io.request.valid := requestIsMap || requestIsX || state === stateRequestA
  reader.io.request.bits.address := Mux(state === stateRequestA,
    config.aRegionBase.U(config.axiAddrWidth.W) + (aOffsetBeats << log2Ceil(config.beatBytes)),
    config.hbmBase.U(config.axiAddrWidth.W) + (xPtrBeats << log2Ceil(config.beatBytes)))
  reader.io.request.bits.beats := MuxCase(1.U, Seq(
    requestIsX -> xBeats,
    (state === stateRequestA) -> aBeats
  ))

  private val decodedWordsThisBeat = Mux(xWordsRemaining > config.xWordsPerBeat.U,
    config.xWordsPerBeat.U(xWordCountWidth.W), xWordsRemaining)
  decoder.io.clear := state === stateReadMap && reader.io.output.valid
  decoder.io.rangeElements := xElements
  decoder.io.input.valid := state === stateReadX && reader.io.output.valid && xWordsRemaining =/= 0.U
  decoder.io.input.bits.data := reader.io.output.bits.data
  decoder.io.input.bits.validWords := decodedWordsThisBeat(log2Ceil(config.xWordsPerBeat + 1) - 1, 0)
  decoder.io.input.bits.last := xWordsRemaining <= config.xWordsPerBeat.U
  localX.io.write <> decoder.io.write
  localX.io.loadBank := !localX.io.activeBank
  localX.io.activate := false.B

  reader.io.output.ready := false.B
  when(state === stateReadMap) {
    reader.io.output.ready := true.B
  }.elsewhen(state === stateReadX) {
    reader.io.output.ready := decoder.io.input.ready && xWordsRemaining =/= 0.U
  }.elsewhen(state === stateReadA) {
    reader.io.output.ready := mul.io.a.ready
  }

  mul.io.enable := state === stateReadA
  mul.io.clearChecksum := io.start && state === stateIdle
  mul.io.batch := firstBatch
  mul.io.workExpected := aBeats =/= 0.U
  mul.io.pageReady := VecInit(Seq.fill(config.mulConfig.xWindowSize / 64)(true.B))
  mul.io.xWindowReady := state === stateReadA
  mul.io.portSafeOverlap := false.B
  mul.io.xReadData := localX.io.readData
  mul.io.streamsComplete := aReaderDone
  mul.io.aSlotValidMask := Fill(config.xWordsPerBeat, aBeats =/= 0.U)
  localX.io.readEnable := mul.io.xReadEnable
  localX.io.readColumn := mul.io.xReadColumn
  mul.io.a.valid := state === stateReadA && reader.io.output.valid
  mul.io.a.bits := reader.io.output.bits
  mul.io.product.foreach(_.ready := true.B)

  io.done := state === stateComplete
  io.error := error
  io.productChecksum := mul.io.productChecksum

  when(decoder.io.input.fire) {
    xWordsRemaining := xWordsRemaining - decodedWordsThisBeat
    when(reader.io.output.bits.error) {
      error := true.B
    }
  }
  when(reader.io.done && (state === stateReadMap || state === stateReadX)) {
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
    is(stateIdle) {
      when(io.start) {
        error := false.B
        xPtrBeats := 0.U
        xReaderDone := false.B
        aReaderDone := false.B
        aBeatIndex := 0.U
        state := stateRequestMap
      }
    }
    is(stateRequestMap) {
      when(reader.io.request.fire) {
        xReaderDone := false.B
        state := stateReadMap
      }
    }
    is(stateReadMap) {
      when(reader.io.output.fire) {
        when(mapInvalid || reader.io.output.bits.error) {
          error := true.B
          state := stateComplete
        }.otherwise {
          xBeats := parsedXBeats
          xWordsRemaining := parsedXWords
          xElements := parsedXElements
          aOffsetBeats := parsedAOffset
          aBeats := parsedABeats
          firstBatch := parsedBatch
          lastGroup := parsedLast
          xPtrBeats := xPtrBeats + 1.U
          xReaderDone := false.B
          aReaderDone := false.B
          aBeatIndex := 0.U
          when(parsedXWords === 0.U && parsedABeats === 0.U) {
            state := Mux(parsedLast, stateComplete, stateRequestMap)
          }.elsewhen(parsedXWords === 0.U) {
            localX.io.activate := true.B
            state := stateRequestA
          }.otherwise {
            state := stateRequestX
          }
        }
      }
    }
    is(stateRequestX) {
      when(reader.io.request.fire) {
        xReaderDone := false.B
        state := stateReadX
      }
    }
    is(stateReadX) {
      when(xReaderDone && xWordsRemaining === 0.U && !decoder.io.write.valid &&
          localX.io.writeIdle) {
        localX.io.activate := true.B
        xPtrBeats := xPtrBeats + xBeats
        when(aBeats === 0.U) {
          state := Mux(lastGroup, stateComplete, stateRequestMap)
        }.otherwise {
          state := stateRequestA
        }
      }
    }
    is(stateRequestA) {
      when(reader.io.request.fire) {
        aReaderDone := false.B
        aBeatIndex := 0.U
        state := stateReadA
      }
    }
    is(stateReadA) {
      when(mul.io.computeDone) {
        state := Mux(lastGroup, stateComplete, stateRequestMap)
      }
    }
  }
}

/** Cuperflow 16-PC 输入顶层：每条 HBM 自己走 map → X → A。 */
final class SpmvCuperflowInputTop(config: SpmvCuperflowConfig = SpmvCuperflowConfig.Simulation)
    extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val hbm = Vec(config.hbmPcCount,
      new Axi4ReadMasterIO(config.axiAddrWidth, config.axiDataWidth, config.axiIdWidth))
    val done = Output(Bool())
    val error = Output(Bool())
    val productChecksumByPc = Output(Vec(config.hbmPcCount, UInt(config.xElementWidth.W)))
    val productChecksum = Output(UInt(config.xElementWidth.W))
  })

  private val lanes = Seq.tabulate(config.hbmPcCount)(pc => Module(new SpmvCuperflowLane(config, pc)))
  private val started = RegInit(false.B)
  when(io.start) {
    started := true.B
  }
  for (pc <- 0 until config.hbmPcCount) {
    lanes(pc).io.start := io.start
    lanes(pc).io.hbm <> io.hbm(pc)
    io.productChecksumByPc(pc) := lanes(pc).io.productChecksum
  }
  io.done := started && VecInit(lanes.map(_.io.done)).asUInt.andR
  io.error := VecInit(lanes.map(_.io.error)).asUInt.orR
  io.productChecksum := lanes.map(_.io.productChecksum).reduce(_ ^ _)
}
