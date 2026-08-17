package accelerators.spmv.input

import accelerators.spmv.SpmvInputConfig
import accelerators.spmv.reader.SpmvReaderBeat
import chisel3._
import chisel3.util._
import npc.ip.arithmetic.ArithmeticIpTiming

/** FMUL 响应的真实产品流。
  *
  * A slot 的 PE-local 行标和 3-bit tag 经 IP req/resp tag 返回，并由固定 PE 身份反解全局行；checksum 仅是这一流的
  * 验收旁路，后续 L1 必须消费本接口而不是重算或猜测行标。
  */
final class SpmvProduct(config: SpmvInputConfig) extends Bundle {
  val product = UInt(config.xElementWidth.W)
  val row = UInt(SpmvCuperDecode.globalRowBits.W)
  val tag = UInt(SpmvCuperDecode.tagBits.W)
  val batch = UInt(SpmvCuperMap.batchIndexWidth.W)
  val pe = UInt(log2Ceil(config.fp64MultiplyTotalLaneCount).W)
  val lane = UInt(log2Ceil(config.fp64MultiplyLaneCount).W)
}

/** 一个 Cuper/Callipepla PE 的 Mixed-V3 FP64 乘法验证引擎。
  *
  * 每个实例对应一个 Cuper A HBM channel。每拍接受一个 512-bit Cuper A beat，并把
  * 8 个 slot 同时送入固定的 `p / 2` local_X 副本。下一拍，8 条独立 FP64 req/resp
  * IP lane 对每个合法列号 slot 同时发射，tag 不参与筛选；同一拍可以读取并接受下一 A beat，因此每个 PE 的
  * 稳定吞吐为 II=1。乘法响应以 Decoupled 真实产品流导出，并同时做位型 XOR 供仿真
  * 验收；checksum 不属于后续 L1 数据路径。
  */
final class SpmvMulEngine(config: SpmvInputConfig, channel: Int = 0) extends Module {
  private val slotsPerBeat = SpmvCuperDecode.lanesPerBeat
  require(channel >= 0 && channel < config.aReaderCount,
    s"Cuper 乘法引擎 channel 越界：$channel/${config.aReaderCount}")
  require(config.fp64MultiplyLaneCount == slotsPerBeat,
    s"乘法 IP lane 数必须与 Cuper A beat slot 数一致，实际为 ${config.fp64MultiplyLaneCount}/$slotsPerBeat")
  require(config.cuperSlotColumnBits == SpmvCuperDecode.columnBits &&
    config.cuperSlotTagBits == SpmvCuperDecode.tagBits &&
    config.cuperSlotRowBits == SpmvCuperDecode.rowBits,
    "SPMV profile 的 Cuper slot v4 位域必须与 RTL decoder 一致")
  private val checkerCount = 8
  require(config.aReaderCount % checkerCount == 0,
    s"Cuper PE-local 行映射要求 A HBM channel 数是 8 的倍数，实际为 ${config.aReaderCount}")
  private val columnWidth = log2Ceil(config.xWindowSize)
  private val xPageElements = SpmvCuperDecode.xPageElements
  private val xPageCount = config.xWindowSize / xPageElements
  private val xPageIndexWidth = log2Ceil(xPageCount)
  require(config.xWindowSize % xPageElements == 0,
    s"Cuper X 窗口必须按 page 对齐，实际为 ${config.xWindowSize}/$xPageElements")
  private val outstandingWidth = math.max(1, log2Ceil(
    slotsPerBeat * (config.fp64MultiplyLatency + config.fp64MultiplyResponseFifoDepth + 1) + 1))
  private val productTagWidth = SpmvCuperDecode.rowBits + SpmvCuperDecode.tagBits +
    SpmvCuperMap.batchIndexWidth

  val io = IO(new Bundle {
    val enable = Input(Bool())
    /** 当前 Cuper 列窗口；与 A slot 的 localRow/tag 一起穿过 FMUL。 */
    val batch = Input(UInt(SpmvCuperMap.batchIndexWidth.W))
    val a = Flipped(Decoupled(new SpmvReaderBeat(config.axiDataWidth)))
    val xReadEnable = Output(Vec(slotsPerBeat, Bool()))
    val xReadColumn = Output(Vec(slotsPerBeat, UInt(columnWidth.W)))
    val xReadData = Input(Vec(slotsPerBeat, UInt(config.xElementWidth.W)))
    /** 本 batch 中已完整写入 local_X 的物理 X page。 */
    val pageReady = Input(Vec(xPageCount, Bool()))
    /** X 已完整写满；preload 在此之后才允许 A 读取。 */
    val xWindowReady = Input(Bool())
    /** 重叠写入期把一个 A beat 拆为偶/奇两个四 lane 半拍。 */
    val portSafeOverlap = Input(Bool())
    /** 每路 A 请求都已接受且对应 reader 均已完成时为真。 */
    val streamsComplete = Input(Bool())
    /** 当前 Cuper map batch 是否在这个 A HBM channel 上有 beat。 */
    val workExpected = Input(Bool())
    val ready = Output(Bool())
    val busy = Output(Bool())
    val computeDone = Output(Bool())
    val error = Output(Bool())
    val timingBeatAccepted = Output(Bool())
    val timingSlot = Output(UInt(log2Ceil(slotsPerBeat).W))
    val timingDecode = Output(Bool())
    val timingXRead = Output(Bool())
    val timingMulRequest = Output(Bool())
    val timingMulResponse = Output(Bool())
    /** 每个 bit 对应一个 Cuper lane p，供 host 区分向量 beat 吞吐与 FP64 lane 数。 */
    val timingValidSlotMask = Output(UInt(slotsPerBeat.W))
    val timingXReadMask = Output(UInt(slotsPerBeat.W))
    val timingMulRequestMask = Output(UInt(slotsPerBeat.W))
    val timingMulResponseMask = Output(UInt(slotsPerBeat.W))
    val timingStreamsComplete = Output(Bool())
    /** 每个有效 A slot 一条产品流；由顶层或后续 L1 施加响应背压。 */
    val product = Vec(slotsPerBeat, Decoupled(new SpmvProduct(config)))
    /** 已响应有效乘积的按位 XOR，供 host 对照编码 slot golden。 */
    val productChecksum = Output(UInt(config.xElementWidth.W))
  })

  // local_X 的读数据在一个寄存器阶段后返回。该 stage 可在本拍发完旧命令时替换为新
  // A beat，从而把 read、FMUL issue 和 A 输入握手叠在一起。
  private val stageValid = RegInit(false.B)
  private val stageValidMask = RegInit(0.U(slotsPerBeat.W))
  private val stageFp32 = Reg(Vec(slotsPerBeat, UInt(32.W)))
  private val stageLocalRow = Reg(Vec(slotsPerBeat, UInt(SpmvCuperDecode.rowBits.W)))
  private val stageTag = Reg(Vec(slotsPerBeat, UInt(SpmvCuperDecode.tagBits.W)))
  private val stageBatch = RegInit(0.U(SpmvCuperMap.batchIndexWidth.W))
  private val halfPending = RegInit(false.B)
  private val halfValidMask = RegInit(0.U(slotsPerBeat.W))
  private val halfFp32 = Reg(Vec(slotsPerBeat, UInt(32.W)))
  private val halfLocalRow = Reg(Vec(slotsPerBeat, UInt(SpmvCuperDecode.rowBits.W)))
  private val halfTag = Reg(Vec(slotsPerBeat, UInt(SpmvCuperDecode.tagBits.W)))
  private val halfColumn = Reg(Vec(slotsPerBeat, UInt(columnWidth.W)))
  private val halfBatch = RegInit(0.U(SpmvCuperMap.batchIndexWidth.W))
  private val productChecksum = RegInit(0.U(config.xElementWidth.W))
  private val error = RegInit(false.B)
  private val started = RegInit(false.B)
  private val outstanding = RegInit(0.U(outstandingWidth.W))

  private val promote = Seq.fill(slotsPerBeat)(Module(new SpmvFp32ToFp64))
  private val multiply = Seq.fill(slotsPerBeat)(Module(new SpmvFp64Mul(ArithmeticIpTiming(
    latency = config.fp64MultiplyLatency,
    initiationInterval = config.fp64MultiplyInitiationInterval,
    responseFifoDepth = config.fp64MultiplyResponseFifoDepth
  ), productTagWidth, config.fp64MulProvider)))

  for (slot <- 0 until slotsPerBeat) {
    promote(slot).io.in := stageFp32(slot)
    multiply(slot).io.req.valid := stageValid && stageValidMask(slot)
    multiply(slot).io.req.bits := 0.U.asTypeOf(multiply(slot).io.req.bits)
    multiply(slot).io.req.bits.operandA := promote(slot).io.out
    multiply(slot).io.req.bits.operandB := io.xReadData(slot)
    multiply(slot).io.req.bits.operation := 0.U
    multiply(slot).io.req.bits.tag := Cat(stageBatch, stageTag(slot), stageLocalRow(slot))
    io.product(slot).valid := multiply(slot).io.resp.valid
    io.product(slot).bits.product := multiply(slot).io.resp.bits.result
    val responseLocalRow = multiply(slot).io.resp.bits.tag(SpmvCuperDecode.rowBits - 1, 0)
    val physicalPe = channel * slotsPerBeat + slot
    val accumulatorGroupSize = config.aReaderCount / checkerCount
    val block = physicalPe / slotsPerBeat
    val checker = block / accumulatorGroupSize
    val accumulatorOffset = block % accumulatorGroupSize
    val peInAccumulator = physicalPe % slotsPerBeat
    val logicalPacket = checker + checkerCount * accumulatorOffset +
      config.aReaderCount * peInAccumulator
    val packet = (responseLocalRow >> 1) * (config.aReaderCount * slotsPerBeat).U +
      logicalPacket.U
    io.product(slot).bits.row := (packet << 1) | responseLocalRow(0)
    io.product(slot).bits.tag := multiply(slot).io.resp.bits.tag(
      SpmvCuperDecode.rowBits + SpmvCuperDecode.tagBits - 1, SpmvCuperDecode.rowBits)
    io.product(slot).bits.batch := multiply(slot).io.resp.bits.tag(productTagWidth - 1,
      SpmvCuperDecode.rowBits + SpmvCuperDecode.tagBits)
    io.product(slot).bits.pe := (channel * slotsPerBeat + slot).U
    io.product(slot).bits.lane := slot.U
    multiply(slot).io.resp.ready := io.product(slot).ready
  }

  private val stageIssueReady = VecInit(multiply.zipWithIndex.map { case (unit, slot) =>
    !stageValidMask(slot) || unit.io.req.ready
  }).asUInt.andR
  private val stageIssueFire = stageValid && stageIssueReady
  private val stageCanLoad = !stageValid || stageIssueFire

  private val incomingSlots = io.a.bits.data.asTypeOf(Vec(slotsPerBeat, UInt(64.W)))
  private val incomingDecoded = incomingSlots.map(SpmvCuperDecode.decodeSlot)
  private val incomingColumnErrorMask = VecInit(incomingDecoded.map { slot =>
    slot.localColumn >= config.xWindowSize.U
  }).asUInt
  private val incomingValidMask = VecInit(incomingDecoded.map { slot =>
    slot.localColumn < config.xWindowSize.U
  }).asUInt
  private val incomingPagesReady = incomingDecoded.map { slot =>
    val page = (slot.localColumn / xPageElements.U)(xPageIndexWidth - 1, 0)
    Mux(slot.localColumn < config.xWindowSize.U, io.pageReady(page), false.B)
  }
  private val incomingBeatReady = io.xWindowReady || incomingPagesReady.reduce(_ && _)

  // PingPong 时一个原始 512-bit A beat 先发偶 lane，再在下一拍发奇 lane。每个
  // local_X replica 因而只有一条读请求，可与该拍 8 个 bank 的 1W 共存。
  private val firstHalfMask = "h55".U(slotsPerBeat.W)
  private val secondHalfMask = "hAA".U(slotsPerBeat.W)
  io.a.ready := io.enable && stageCanLoad && !halfPending && incomingBeatReady
  private val canAccept = io.a.fire
  private val acceptMask = Mux(io.portSafeOverlap, incomingValidMask & firstHalfMask,
    incomingValidMask)
  private val issueSecondHalf = halfPending && stageIssueFire
  private val loadMask = Mux(canAccept, acceptMask,
    Mux(issueSecondHalf, halfValidMask, 0.U(slotsPerBeat.W)))

  for (slot <- 0 until slotsPerBeat) {
    io.xReadEnable(slot) := loadMask(slot)
    io.xReadColumn(slot) := Mux(issueSecondHalf, halfColumn(slot),
      incomingDecoded(slot).localColumn(columnWidth - 1, 0))
  }

  private val requestMask = VecInit(multiply.map(_.io.req.fire)).asUInt
  private val responseMask = VecInit(io.product.map(_.fire)).asUInt
  private val responseChecksum = io.product.map { product =>
    Mux(product.fire, product.bits.product, 0.U(config.xElementWidth.W))
  }.reduce(_ ^ _)
  private val responseError = multiply.map { unit =>
    unit.io.resp.fire && unit.io.resp.bits.illegal
  }.reduce(_ || _)
  private val requestCount = PopCount(requestMask)
  private val responseCount = PopCount(responseMask)
  private val inFlightBeforeResponse = outstanding +& requestCount
  private val outstandingNext = inFlightBeforeResponse - responseCount
  private val responseUnderflow = responseCount > inFlightBeforeResponse

  when(!io.enable) {
    stageValid := false.B
    stageValidMask := 0.U
    stageFp32.foreach(_ := 0.U)
    stageLocalRow.foreach(_ := 0.U)
    stageTag.foreach(_ := 0.U)
    stageBatch := 0.U
    halfPending := false.B
    halfValidMask := 0.U
    halfFp32.foreach(_ := 0.U)
    halfLocalRow.foreach(_ := 0.U)
    halfTag.foreach(_ := 0.U)
    halfColumn.foreach(_ := 0.U)
    halfBatch := 0.U
    error := false.B
    started := false.B
    productChecksum := 0.U
    outstanding := 0.U
  }.otherwise {
    when(canAccept) {
      stageValid := true.B
      stageValidMask := acceptMask
      stageFp32 := VecInit(incomingDecoded.map(_.fp32))
      stageLocalRow := VecInit(incomingDecoded.map(_.localRow))
      stageTag := VecInit(incomingDecoded.map(_.tag))
      stageBatch := io.batch
      halfPending := io.portSafeOverlap
      when(io.portSafeOverlap) {
        halfValidMask := incomingValidMask & secondHalfMask
        halfFp32 := VecInit(incomingDecoded.map(_.fp32))
        halfLocalRow := VecInit(incomingDecoded.map(_.localRow))
        halfTag := VecInit(incomingDecoded.map(_.tag))
        halfColumn := VecInit(incomingDecoded.map(
          _.localColumn(columnWidth - 1, 0)))
        halfBatch := io.batch
      }
      started := true.B
    }.elsewhen(issueSecondHalf) {
      stageValid := true.B
      stageValidMask := halfValidMask
      stageFp32 := halfFp32
      stageLocalRow := halfLocalRow
      stageTag := halfTag
      stageBatch := halfBatch
      halfPending := false.B
    }.elsewhen(stageIssueFire) {
      stageValid := false.B
      stageValidMask := 0.U
    }
    when(responseMask.orR) {
      productChecksum := productChecksum ^ responseChecksum
    }
    when(canAccept && (io.a.bits.error || incomingColumnErrorMask.orR) ||
      responseError || responseUnderflow) {
      error := true.B
    }
    outstanding := outstandingNext(outstandingWidth - 1, 0)
  }

  private val aPending = io.enable && io.a.valid
  io.ready := io.enable && stageCanLoad && !halfPending
  io.busy := io.enable && (stageValid || halfPending || aPending || outstanding =/= 0.U)
  io.computeDone := io.enable && (started || !io.workExpected) && io.streamsComplete &&
    !stageValid && !halfPending && !aPending && outstanding === 0.U
  io.error := error
  io.timingBeatAccepted := canAccept
  io.timingSlot := Mux(canAccept && incomingValidMask.orR, PriorityEncoder(incomingValidMask), 0.U)
  io.timingDecode := canAccept
  io.timingXRead := io.xReadEnable.asUInt.orR
  io.timingMulRequest := requestMask.orR
  io.timingMulResponse := responseMask.orR
  io.timingValidSlotMask := loadMask
  io.timingXReadMask := io.xReadEnable.asUInt
  io.timingMulRequestMask := requestMask
  io.timingMulResponseMask := responseMask
  io.timingStreamsComplete := io.streamsComplete
  io.productChecksum := productChecksum
}
