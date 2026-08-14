package accelerators.spmv.input

import accelerators.spmv.SpmvInputConfig
import accelerators.spmv.reader.SpmvReaderBeat
import chisel3._
import chisel3.util._
import npc.ip.arithmetic.{ArithmeticIpTiming, FloatingOperation}

/** 一个 Cuper/Callipepla PE 的 Mixed-V3 FP64 乘法验证引擎。
  *
  * 每个实例对应一个 Cuper A HBM channel。每拍接受一个 512-bit Cuper A beat，并把
  * 8 个 slot 同时送入固定的 `p / 2` local_X 副本。下一拍，8 条独立 FP64 req/resp
  * IP lane 对有效 slot 同时发射；同一拍可以读取并接受下一 A beat，因此每个 PE 的
  * 稳定吞吐为 II=1。乘法响应持续回收并做位型 XOR，只用于仿真功能校验，不构成
  * 浮点累加或 Y 写回路径。
  */
final class SpmvMulEngine(config: SpmvInputConfig) extends Module {
  private val slotsPerBeat = SpmvCuperDecode.lanesPerBeat
  require(config.fp64MultiplyLaneCount == slotsPerBeat,
    s"乘法 IP lane 数必须与 Cuper A beat slot 数一致，实际为 ${config.fp64MultiplyLaneCount}/$slotsPerBeat")
  private val columnWidth = log2Ceil(config.xWindowSize)
  private val outstandingWidth = math.max(1, log2Ceil(
    slotsPerBeat * (config.fp64MultiplyLatency + config.fp64MultiplyResponseFifoDepth + 1) + 1))

  val io = IO(new Bundle {
    val enable = Input(Bool())
    val a = Flipped(Decoupled(new SpmvReaderBeat(config.axiDataWidth)))
    val xReadEnable = Output(Vec(slotsPerBeat, Bool()))
    val xReadColumn = Output(Vec(slotsPerBeat, UInt(columnWidth.W)))
    val xReadData = Input(Vec(slotsPerBeat, UInt(config.xElementWidth.W)))
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
    val timingPadding = Output(Bool())
    val timingXRead = Output(Bool())
    val timingMulRequest = Output(Bool())
    val timingMulResponse = Output(Bool())
    /** 每个 bit 对应一个 Cuper lane p，供 host 区分向量 beat 吞吐与 FP64 lane 数。 */
    val timingValidSlotMask = Output(UInt(slotsPerBeat.W))
    val timingPaddingMask = Output(UInt(slotsPerBeat.W))
    val timingXReadMask = Output(UInt(slotsPerBeat.W))
    val timingMulRequestMask = Output(UInt(slotsPerBeat.W))
    val timingMulResponseMask = Output(UInt(slotsPerBeat.W))
    val timingStreamsComplete = Output(Bool())
    /** 已响应有效乘积的按位 XOR，供 host 对照编码 slot golden。 */
    val productChecksum = Output(UInt(config.xElementWidth.W))
  })

  // local_X 的读数据在一个寄存器阶段后返回。该 stage 可在本拍发完旧命令时替换为新
  // A beat，从而把 read、FMUL issue 和 A 输入握手叠在一起。
  private val stageValid = RegInit(false.B)
  private val stageValidMask = RegInit(0.U(slotsPerBeat.W))
  private val stageFp32 = Reg(Vec(slotsPerBeat, UInt(32.W)))
  private val productChecksum = RegInit(0.U(config.xElementWidth.W))
  private val error = RegInit(false.B)
  private val started = RegInit(false.B)
  private val outstanding = RegInit(0.U(outstandingWidth.W))

  private val promote = Seq.fill(slotsPerBeat)(Module(new SpmvFp32ToFp64))
  private val multiply = Seq.fill(slotsPerBeat)(Module(new SpmvFp64Mul(ArithmeticIpTiming(
    latency = config.fp64MultiplyLatency,
    initiationInterval = config.fp64MultiplyInitiationInterval,
    responseFifoDepth = config.fp64MultiplyResponseFifoDepth
  ))))

  for (slot <- 0 until slotsPerBeat) {
    promote(slot).io.in := stageFp32(slot)
    multiply(slot).io.req.valid := stageValid && stageValidMask(slot)
    multiply(slot).io.req.bits := 0.U.asTypeOf(multiply(slot).io.req.bits)
    multiply(slot).io.req.bits.operandA := promote(slot).io.out
    multiply(slot).io.req.bits.operandB := io.xReadData(slot)
    multiply(slot).io.req.bits.operation := FloatingOperation.multiply.asUInt
    multiply(slot).io.req.bits.roundingMode := 0.U
    // 本阶段没有结果依赖；持续消费响应才能让每条 II=1 IP lane 不产生回压。
    multiply(slot).io.resp.ready := true.B
  }

  private val stageIssueReady = VecInit(multiply.zipWithIndex.map { case (unit, slot) =>
    !stageValidMask(slot) || unit.io.req.ready
  }).asUInt.andR
  private val stageIssueFire = stageValid && stageIssueReady
  private val stageCanLoad = !stageValid || stageIssueFire
  io.a.ready := io.enable && stageCanLoad
  private val canAccept = io.a.fire

  private val incomingSlots = io.a.bits.data.asTypeOf(Vec(slotsPerBeat, UInt(64.W)))
  private val incomingDecoded = incomingSlots.map(SpmvCuperDecode.decodeSlot)
  private val incomingPaddingMask = VecInit(incomingDecoded.map(_.padding)).asUInt
  private val incomingColumnErrorMask = VecInit(incomingDecoded.map { slot =>
    !slot.padding && slot.localColumn >= config.xWindowSize.U
  }).asUInt
  private val incomingValidMask = VecInit(incomingDecoded.map { slot =>
    !slot.padding && slot.localColumn < config.xWindowSize.U
  }).asUInt

  for (slot <- 0 until slotsPerBeat) {
    io.xReadEnable(slot) := canAccept && incomingValidMask(slot)
    io.xReadColumn(slot) := incomingDecoded(slot).localColumn(columnWidth - 1, 0)
  }

  private val requestMask = VecInit(multiply.map(_.io.req.fire)).asUInt
  private val responseMask = VecInit(multiply.map(_.io.resp.fire)).asUInt
  private val responseChecksum = multiply.map { unit =>
    Mux(unit.io.resp.fire, unit.io.resp.bits.result, 0.U(config.xElementWidth.W))
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
    error := false.B
    started := false.B
    productChecksum := 0.U
    outstanding := 0.U
  }.otherwise {
    when(canAccept) {
      stageValid := true.B
      stageValidMask := incomingValidMask
      stageFp32 := VecInit(incomingDecoded.map(_.fp32))
      started := true.B
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
  io.ready := io.enable && stageCanLoad
  io.busy := io.enable && (stageValid || aPending || outstanding =/= 0.U)
  io.computeDone := io.enable && (started || !io.workExpected) && io.streamsComplete &&
    !stageValid && !aPending && outstanding === 0.U
  io.error := error
  io.timingBeatAccepted := canAccept
  io.timingSlot := Mux(canAccept && incomingValidMask.orR, PriorityEncoder(incomingValidMask), 0.U)
  io.timingDecode := canAccept
  io.timingPadding := canAccept && incomingPaddingMask.orR
  io.timingXRead := io.xReadEnable.asUInt.orR
  io.timingMulRequest := requestMask.orR
  io.timingMulResponse := responseMask.orR
  io.timingValidSlotMask := Mux(canAccept, incomingValidMask, 0.U)
  io.timingPaddingMask := Mux(canAccept, incomingPaddingMask, 0.U)
  io.timingXReadMask := io.xReadEnable.asUInt
  io.timingMulRequestMask := requestMask
  io.timingMulResponseMask := responseMask
  io.timingStreamsComplete := io.streamsComplete
  io.productChecksum := productChecksum
}
