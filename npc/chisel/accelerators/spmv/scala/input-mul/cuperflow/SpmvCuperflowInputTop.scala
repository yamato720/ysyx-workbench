package accelerators.spmv.inputmul.cuperflow

import accelerators.spmv.SpmvCuperflowConfig
import accelerators.spmv.inputmul.common.{SpmvCuperDecode, SpmvMulEngine}
import accelerators.spmv.reader.{SpmvReaderBeat, SpmvXReader}
import chisel3._
import chisel3.util._
import npc.ip.axi.Axi4ReadMasterIO

/** 一个 PC 的 map / 读写控制单元。
  *
  * 从低地址 X 区按序读取：1-beat map → xBeats 个连续 FP64 value beat → 切到 map 给出的
  * 高地址 A 区间连读并乘法。本组结束后 xPtr 已指向下一张 map。16 个 PC 互不等待。
  */
private[cuperflow] final class SpmvCuperflowLane(config: SpmvCuperflowConfig, pc: Int)
    extends Module {
  require(pc >= 0 && pc < config.hbmPcCount,
    s"Cuperflow PC 编号越界：$pc/${config.hbmPcCount}")

  private val xWordCountWidth = log2Ceil(config.xMaxEncodedWords + 1)
  private val xSegmentCount = 1 << SpmvCuperDecode.tagBits
  private val xSegmentStartWidth = log2Ceil(config.xWindowSize)
  private val xSegmentLengthWidth = log2Ceil(config.xWindowSize + 1)
  private val states = Enum(14)
  private val stateIdle = states(0)
  private val stateRequestMap = states(1)
  private val stateReadMap = states(2)
  private val stateDecodeMap = states(3)
  private val stateRequestX = states(4)
  private val stateReadX = states(5)
  private val stateRequestDescriptor = states(6)
  private val stateReadDescriptor = states(7)
  private val stateDecodeDescriptor = states(8)
  private val stateRequestContributor = states(9)
  private val stateReadContributor = states(10)
  private val stateRequestA = states(11)
  private val stateReadA = states(12)
  private val stateComplete = states(13)

  val io = IO(new Bundle {
    val start = Input(Bool())
    val hbm = new Axi4ReadMasterIO(config.axiAddrWidth, config.axiDataWidth, config.axiIdWidth)
    val done = Output(Bool())
    val error = Output(Bool())
    val productChecksum = Output(UInt(config.xElementWidth.W))
    /** 单 PC 的原子 FMUL 输出边界；后续 L2 只接收本接口。 */
    val product = Decoupled(new SpmvCuperflowProductBeat(config))
  })

  private val reader = Module(new SpmvXReader(
    config.axiAddrWidth,
    config.axiDataWidth,
    config.axiIdWidth,
    config.maxOutstandingBursts,
    bufferOutput = true
  ))
  private val localX = Module(new SpmvCuperflowLocalX(config))
  private val mul = Module(new SpmvMulEngine(config.mulConfig, pc))

  private val state = RegInit(stateIdle)
  private val error = RegInit(false.B)
  private val xPtrBeats = RegInit(0.U(32.W))
  private val xBeats = RegInit(0.U(32.W))
  private val xWordsRemaining = RegInit(0.U(xWordCountWidth.W))
  private val xSegmentStarts = RegInit(VecInit(Seq.fill(xSegmentCount)(0.U(xSegmentStartWidth.W))))
  private val xSegmentLengths = RegInit(VecInit(Seq.fill(xSegmentCount)(0.U(xSegmentLengthWidth.W))))
  private val descriptorRemaining = RegInit(0.U(32.W))
  private val contributorBeats = RegInit(0.U(32.W))
  private val aOffsetBeats = RegInit(0.U(32.W))
  private val aBeats = RegInit(0.U(32.W))
  private val firstBatch = RegInit(0.U(8.W))
  private val currentWave = RegInit(0.U(16.W))
  private val aBeatSequence = RegInit(0.U(32.W))
  private val lastGroup = RegInit(false.B)
  private val xReaderDone = RegInit(false.B)
  private val aReaderDone = RegInit(false.B)
  private val aBeatIndex = RegInit(0.U(32.W))
  private val mapReg = Reg(new SpmvReaderBeat(config.axiDataWidth))
  private val descriptorReg = Reg(new SpmvReaderBeat(config.axiDataWidth))

  private val mapWords = mapReg.data.asTypeOf(
    Vec(config.xWordsPerBeat, UInt(config.xElementWidth.W)))
  private val mapValid = SpmvCuperflowMapMarker.isMarker(mapWords(0))
  private val parsedLast = mapWords(0)(0)
  private val parsedXBeats = mapWords(1)(31, 0)
  private val parsedXWords = mapWords(1)(63, 32)
  private val parsedDescriptorCount = mapWords(2)(31, 0)
  private val parsedMapReserved = mapWords(2)(63, 32).orR || mapWords(3)(63, 32).orR
  private val parsedSliceGroup = SpmvCuperflowMapFormat.sliceGroup(mapWords(3))
  private val parsedXElements = SpmvCuperflowMapFormat.xElements(mapWords(3))
  private val parsedSegmentDescriptors = VecInit(Seq.tabulate(xSegmentCount) { segment =>
    val word = mapWords(4 + segment / 2)
    word(32 * (segment % 2) + 31, 32 * (segment % 2))
  })
  private val parsedSegmentStarts = VecInit(parsedSegmentDescriptors.map(_(12, 0)))
  private val parsedSegmentLengths = VecInit(parsedSegmentDescriptors.map(_(26, 13)))
  private val parsedSegmentReserved = VecInit(parsedSegmentDescriptors.map(_(31, 27))).asUInt.orR
  private val parsedSegmentStartsZeroWhenUnused = VecInit(
    parsedSegmentStarts.zip(parsedSegmentLengths).map { case (start, length) =>
      length =/= 0.U || start === 0.U
    }).asUInt.andR
  private val parsedSegmentInBounds = VecInit(
    parsedSegmentStarts.zip(parsedSegmentLengths).map { case (start, length) =>
      val end = Cat(0.U(1.W), start) +& length
      end <= config.xWindowSize.U
    }).asUInt.andR
  private val parsedSegmentLengthSum = parsedSegmentLengths.foldLeft(0.U(17.W)) {
    case (sum, length) => sum +% Cat(0.U(3.W), length)
  }
  private val parsedXWordsWide = Cat(0.U(15.W), parsedXWords)
  private val expectedXBeats = (parsedXWords + (config.xWordsPerBeat - 1).U) >>
    log2Ceil(config.xWordsPerBeat)
  private val xEndBytes = (xPtrBeats + 1.U + parsedXBeats) << log2Ceil(config.beatBytes)
  private val mapInvalid = mapReg.error || !mapValid ||
    parsedXWords > config.xWindowSize.U ||
    parsedXElements > config.xWindowSize.U ||
    parsedXWords =/= parsedXElements ||
    parsedSegmentLengthSum =/= parsedXWordsWide ||
    !parsedSegmentStartsZeroWhenUnused || !parsedSegmentInBounds || parsedSegmentReserved ||
    parsedXBeats =/= expectedXBeats ||
    xEndBytes > config.xRegionBytes.U ||
    parsedMapReserved ||
    parsedSliceGroup % config.hbmPcCount.U =/= pc.U ||
    (parsedDescriptorCount === 0.U && parsedXWords =/= 0.U)

  private val descriptorWords = descriptorReg.data.asTypeOf(
    Vec(config.xWordsPerBeat, UInt(config.xElementWidth.W))
  )
  private val descriptorValid = SpmvCuperflowBatchDescriptorMarker.isMarker(descriptorWords(0))
  private val descriptorLast = descriptorWords(0)(0)
  private val descriptorBatchWide = descriptorWords(1)(31, 0)
  private val descriptorAOffset = descriptorWords(1)(63, 32)
  private val descriptorABeats = descriptorWords(2)(31, 0)
  private val descriptorContributorWords = descriptorWords(3)(31, 0)
  private val descriptorActiveRows = descriptorWords(3)(63, 32)
  private val descriptorReserved = VecInit(descriptorWords.drop(4).map(_ =/= 0.U)).asUInt.orR
  private val descriptorBatchFits = !descriptorBatchWide(31, 16).orR
  private val descriptorExpectedContributorWords = (descriptorActiveRows + 63.U) >> 6
  private val descriptorContributorBeats = (descriptorContributorWords + 7.U) >> 3
  private val descriptorAEndBytes = (descriptorAOffset +& descriptorABeats) <<
    log2Ceil(config.beatBytes)
  private val descriptorXEndBytes = (xPtrBeats + 1.U + descriptorContributorBeats) <<
    log2Ceil(config.beatBytes)
  private val descriptorInvalid = descriptorReg.error || !descriptorValid || descriptorReserved ||
    !descriptorBatchFits || descriptorActiveRows > config.rowBatchSize.U ||
    descriptorContributorWords =/= descriptorExpectedContributorWords ||
    descriptorAEndBytes > config.aRegionBytes.U || descriptorXEndBytes > config.xRegionBytes.U ||
    descriptorRemaining === 0.U || descriptorLast =/= (descriptorRemaining === 1.U)

  io.hbm <> reader.io.axi
  private val requestIsMap = state === stateRequestMap
  private val requestIsX = state === stateRequestX
  private val requestIsDescriptor = state === stateRequestDescriptor
  private val requestIsContributor = state === stateRequestContributor
  reader.io.request.valid := requestIsMap || requestIsX || requestIsDescriptor ||
    requestIsContributor || state === stateRequestA
  reader.io.request.bits.address := Mux(state === stateRequestA,
    config.aRegionBase.U(config.axiAddrWidth.W) + (aOffsetBeats << log2Ceil(config.beatBytes)),
    config.hbmBase.U(config.axiAddrWidth.W) + (xPtrBeats << log2Ceil(config.beatBytes)))
  reader.io.request.bits.beats := MuxCase(1.U, Seq(
    requestIsX -> xBeats,
    requestIsContributor -> contributorBeats,
    (state === stateRequestA) -> aBeats
  ))

  private val loadedElementsThisBeat = Mux(xWordsRemaining > config.xWordsPerBeat.U,
    config.xWordsPerBeat.U(xWordCountWidth.W), xWordsRemaining)
  // map 先被寄存，下一拍才提交 X/A 元数据并复位顺序装填指针。
  localX.io.clearLoad := state === stateDecodeMap && !mapInvalid
  localX.io.write.valid := state === stateReadX && reader.io.output.valid &&
    xWordsRemaining =/= 0.U
  localX.io.write.bits.data := reader.io.output.bits.data
  localX.io.write.bits.validElements :=
    loadedElementsThisBeat(log2Ceil(config.xWordsPerBeat + 1) - 1, 0)
  localX.io.loadBank := !localX.io.activeBank
  localX.io.activate := false.B

  reader.io.output.ready := false.B
  when(state === stateReadMap || state === stateReadDescriptor ||
    state === stateReadContributor) {
    reader.io.output.ready := true.B
  }.elsewhen(state === stateReadX) {
    reader.io.output.ready := localX.io.write.ready && xWordsRemaining =/= 0.U
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
  private val aSlots = reader.io.output.bits.data.asTypeOf(
    Vec(config.xWordsPerBeat, UInt(64.W)))
  private val aSlotNonZeroMask = VecInit(aSlots.map(_ =/= 0.U)).asUInt
  /** 全零物理填充不访问 span 外的 X；全零实际项的乘积也恒为零。 */
  mul.io.aSlotValidMask := Mux(aBeats =/= 0.U, aSlotNonZeroMask, 0.U)
  private val xSegmentPrefixes = Wire(Vec(xSegmentCount, UInt(xWordCountWidth.W)))
  private var xPrefix = 0.U(xWordCountWidth.W)
  for (segment <- 0 until xSegmentCount) {
    xSegmentPrefixes(segment) := xPrefix
    xPrefix = xPrefix +% xSegmentLengths(segment)
  }
  private val xReadSegmentKnown = VecInit(mul.io.xReadSegmentId.map(_ < xSegmentCount.U))
  private val xReadSegmentStart = VecInit(mul.io.xReadSegmentId.map { segmentId =>
    MuxLookup(segmentId, 0.U(xSegmentStartWidth.W))(
      (0 until xSegmentCount).map(index => index.U -> xSegmentStarts(index)))
  })
  private val xReadSegmentLength = VecInit(mul.io.xReadSegmentId.map { segmentId =>
    MuxLookup(segmentId, 0.U(xSegmentLengthWidth.W))(
      (0 until xSegmentCount).map(index => index.U -> xSegmentLengths(index)))
  })
  private val xReadSegmentPrefix = VecInit(mul.io.xReadSegmentId.map { segmentId =>
    MuxLookup(segmentId, 0.U(xWordCountWidth.W))(
      (0 until xSegmentCount).map(index => index.U -> xSegmentPrefixes(index)))
  })
  private val xReadLegal = VecInit(mul.io.xReadColumn.zipWithIndex.map { case (column, lane) =>
    val offset = column - xReadSegmentStart(lane)
    xReadSegmentKnown(lane) && xReadSegmentLength(lane) =/= 0.U &&
      column >= xReadSegmentStart(lane) && offset < xReadSegmentLength(lane)
  })
  localX.io.readEnable := VecInit(mul.io.xReadEnable.zip(xReadLegal).zipWithIndex.map {
    case ((enable, legal), lane) => enable && legal && aSlotNonZeroMask(lane)
  })
  localX.io.readColumn := VecInit(mul.io.xReadColumn.zipWithIndex.map { case (column, lane) =>
    val offset = column - xReadSegmentStart(lane)
    (xReadSegmentPrefix(lane) +% offset)(xSegmentStartWidth - 1, 0)
  })
  private val productJoin = Module(new SpmvCuperflowProductBeatJoin(config, pc))
  private val acceptedProductBeat = Wire(new SpmvCuperflowProductBeat(config))
  acceptedProductBeat := 0.U.asTypeOf(new SpmvCuperflowProductBeat(config))
  acceptedProductBeat.pc := pc.U
  acceptedProductBeat.wave := currentWave
  acceptedProductBeat.batch := firstBatch
  acceptedProductBeat.beatSeq := aBeatSequence
  acceptedProductBeat.laneValid := aSlotNonZeroMask
  acceptedProductBeat.chunkMode := Mux1H((0 until config.xWordsPerBeat).map { lane =>
    aSlotNonZeroMask(lane) -> SpmvCuperDecode.decodeSlot(aSlots(lane)).chunkMode
  })
  for (lane <- 0 until config.xWordsPerBeat) {
    val slot = SpmvCuperDecode.decodeSlot(aSlots(lane))
    acceptedProductBeat.localRow(lane) := slot.localRow
    acceptedProductBeat.rowLast(lane) := slot.rowLast
  }
  // context 与 A beat 必须在同一拍被各自接受；reader 的 valid 可在 FMUL 反压期间保持，
  // 不能让 join 因自身 FIFO 仍有空间而重复记录尚未进入 FMUL 的 beat。
  productJoin.io.accept.valid := state === stateReadA && reader.io.output.valid && mul.io.a.ready
  productJoin.io.accept.bits := acceptedProductBeat
  productJoin.io.clear := io.start && state === stateIdle
  for (lane <- 0 until config.xWordsPerBeat) {
    productJoin.io.product(lane) <> mul.io.product(lane)
  }
  io.product.valid := productJoin.io.out.valid
  io.product.bits := productJoin.io.out.bits
  productJoin.io.out.ready := io.product.ready

  // FMUL 与 context FIFO 必须原子接受同一个 A beat。只反压 reader 而让 FMUL 前进会
  // 产生无法与 ProductBeat sideband 配对的 response。
  mul.io.a.valid := state === stateReadA && reader.io.output.valid && productJoin.io.accept.ready
  mul.io.a.bits := reader.io.output.bits
  when(state === stateReadA) {
    reader.io.output.ready := mul.io.a.ready && productJoin.io.accept.ready
  }

  io.done := state === stateComplete
  io.error := error
  io.productChecksum := mul.io.productChecksum

  when(localX.io.write.fire) {
    xWordsRemaining := xWordsRemaining - loadedElementsThisBeat
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
    aBeatSequence := aBeatSequence + 1.U
  }
  when((mul.io.xReadEnable.asUInt & ~xReadLegal.asUInt & aSlotNonZeroMask).orR) {
    error := true.B
  }
  when(reader.io.error || localX.io.error || mul.io.error || productJoin.io.error) {
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
        aBeatSequence := 0.U
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
        mapReg := reader.io.output.bits
        state := stateDecodeMap
      }
    }
    is(stateDecodeMap) {
      when(mapInvalid) {
        error := true.B
        state := stateComplete
      }.otherwise {
        xBeats := parsedXBeats
        xWordsRemaining := parsedXWords
        xSegmentStarts := parsedSegmentStarts
        xSegmentLengths := parsedSegmentLengths
        descriptorRemaining := parsedDescriptorCount
        lastGroup := parsedLast
        currentWave := parsedSliceGroup / config.hbmPcCount.U
        xPtrBeats := xPtrBeats + 1.U
        xReaderDone := false.B
        aReaderDone := false.B
        aBeatIndex := 0.U
        when(parsedXWords === 0.U) {
          state := Mux(parsedDescriptorCount === 0.U,
            Mux(parsedLast, stateComplete, stateRequestMap), stateRequestDescriptor)
        }.otherwise {
          state := stateRequestX
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
      when(xReaderDone && xWordsRemaining === 0.U && localX.io.writeIdle) {
        localX.io.activate := true.B
        xPtrBeats := xPtrBeats + xBeats
        state := Mux(descriptorRemaining === 0.U,
          Mux(lastGroup, stateComplete, stateRequestMap), stateRequestDescriptor)
      }
    }
    is(stateRequestDescriptor) {
      when(reader.io.request.fire) {
        state := stateReadDescriptor
      }
    }
    is(stateReadDescriptor) {
      when(reader.io.output.fire) {
        descriptorReg := reader.io.output.bits
        state := stateDecodeDescriptor
      }
    }
    is(stateDecodeDescriptor) {
      when(descriptorInvalid) {
        error := true.B
        state := stateComplete
      }.otherwise {
        firstBatch := descriptorBatchWide(15, 0)
        aOffsetBeats := descriptorAOffset
        aBeats := descriptorABeats
        contributorBeats := descriptorContributorBeats
        xPtrBeats := xPtrBeats + 1.U
        when(descriptorContributorWords =/= 0.U) {
          state := stateRequestContributor
        }.elsewhen(descriptorABeats =/= 0.U) {
          state := stateRequestA
        }.otherwise {
          descriptorRemaining := descriptorRemaining - 1.U
          state := Mux(descriptorRemaining === 1.U,
            Mux(lastGroup, stateComplete, stateRequestMap), stateRequestDescriptor)
        }
      }
    }
    is(stateRequestContributor) {
      when(reader.io.request.fire) {
        xReaderDone := false.B
        state := stateReadContributor
      }
    }
    is(stateReadContributor) {
      when(reader.io.done) {
        xPtrBeats := xPtrBeats + contributorBeats
        when(aBeats =/= 0.U) {
          state := stateRequestA
        }.otherwise {
          descriptorRemaining := descriptorRemaining - 1.U
          state := Mux(descriptorRemaining === 1.U,
            Mux(lastGroup, stateComplete, stateRequestMap), stateRequestDescriptor)
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
      when(mul.io.computeDone && productJoin.io.idle) {
        descriptorRemaining := descriptorRemaining - 1.U
        state := Mux(descriptorRemaining === 1.U,
          Mux(lastGroup, stateComplete, stateRequestMap), stateRequestDescriptor)
      }
    }
  }
}

/** Cuperflow 输入顶层：每条 HBM 自己走 map → X → A。 */
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
    /** 每个 PC 一条原子 ProductBeat 流；后续 L2 只需按同一 PC Config 对接。 */
    val product = Vec(config.hbmPcCount, Decoupled(new SpmvCuperflowProductBeat(config)))
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
    io.product(pc) <> lanes(pc).io.product
  }
  io.done := started && VecInit(lanes.map(_.io.done)).asUInt.andR
  io.error := VecInit(lanes.map(_.io.error)).asUInt.orR
  io.productChecksum := lanes.map(_.io.productChecksum).reduce(_ ^ _)
}
