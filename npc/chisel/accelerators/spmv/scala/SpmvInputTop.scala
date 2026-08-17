package accelerators.spmv

import chisel3._
import chisel3.util._
import accelerators.spmv.input.{
  SpmvAInput,
  SpmvCtrlInput,
  SpmvCuperDecode,
  SpmvCuperMap,
  SpmvInputConsumer,
  SpmvLocalX,
  SpmvMulEngine,
  SpmvXInput
}
import accelerators.spmv.reader.SpmvReaderRequest
import npc.ip.axi.Axi4ReadMasterIO

/** SPMV 输入层顶层接口。
  *
  * 一个 A 输入封装承载全部 A HBM，每个内部 reader 连接一个消费端和一个独立的
  * Cuper PE；两路 X HBM 按偶/奇 beat 条带输入，写入每个 PE 自己的 local_X。
  * 一路控制 HBM 把 map 等侧带数据同样广播给全部消费端，供后续 JPCG 复用。
  * Ctrl map 就绪后，preload 先写满 X 再启动 A；pingpong 则在 X page 完整后以四
  * lane 半拍提前启动 A，X 写入仍保持每拍 8 个 FP64。A 在被 PE 接受的同拍旁路到
  * 消费端校验，并进入 Mixed-V3 FP64 乘法 IP 验证引擎。
  */
final class SpmvInputTopIO(config: SpmvInputConfig) extends Bundle {
  private val requestType = new SpmvReaderRequest(config.axiAddrWidth)
  private val hbmType = new Axi4ReadMasterIO(
    config.axiAddrWidth,
    config.axiDataWidth,
    config.axiIdWidth
  )

  val aRequest = Vec(config.aReaderCount, Flipped(Decoupled(requestType)))
  val xRequest = Vec(config.xReaderCount, Flipped(Decoupled(requestType)))
  val ctrlRequest = Vec(config.ctrlReaderCount, Flipped(Decoupled(requestType)))

  val aHbm = Vec(config.aReaderCount, hbmType)
  val xHbm = Vec(config.xReaderCount, hbmType)
  val ctrlHbm = Vec(config.ctrlReaderCount, hbmType)

  val aIdle = Output(Vec(config.aReaderCount, Bool()))
  val aBusy = Output(Vec(config.aReaderCount, Bool()))
  val aDone = Output(Vec(config.aReaderCount, Bool()))
  val aError = Output(Vec(config.aReaderCount, Bool()))
  val xIdle = Output(Vec(config.xReaderCount, Bool()))
  val xBusy = Output(Vec(config.xReaderCount, Bool()))
  val xDone = Output(Vec(config.xReaderCount, Bool()))
  val xError = Output(Vec(config.xReaderCount, Bool()))
  val ctrlIdle = Output(Vec(config.ctrlReaderCount, Bool()))
  val ctrlBusy = Output(Vec(config.ctrlReaderCount, Bool()))
  val ctrlDone = Output(Vec(config.ctrlReaderCount, Bool()))
  val ctrlError = Output(Vec(config.ctrlReaderCount, Bool()))
  val consumerABeats = Output(Vec(config.aReaderCount, UInt(32.W)))
  val consumerXBeats = Output(Vec(config.aReaderCount, UInt(32.W)))
  val consumerCtrlBeats = Output(Vec(config.aReaderCount, UInt(32.W)))
  val consumerAChecksum = Output(Vec(config.aReaderCount, UInt(64.W)))
  val consumerXChecksum = Output(Vec(config.aReaderCount, UInt(64.W)))
  val consumerCtrlChecksum = Output(Vec(config.aReaderCount, UInt(64.W)))
  val consumerError = Output(Vec(config.aReaderCount, Bool()))
  val mulEnable = Input(Bool())
  /** 当前 Cuper X/A 窗口编号；边界由 Ctrl map 中相邻 pointer 决定。 */
  val mulBatch = Input(UInt(SpmvCuperMap.batchIndexWidth.W))
  /** Ctrl map 已被完整接收且可用于校验该 batch 的 A 请求。 */
  val ctrlMapReady = Output(Bool())
  val mulReady = Output(Bool())
  val computeDone = Output(Bool())
  val mulError = Output(Bool())
  val timingBeatAccepted = Output(Bool())
  val timingChannel = Output(UInt(log2Ceil(config.aReaderCount).W))
  val timingSlot = Output(UInt(log2Ceil(SpmvCuperDecode.lanesPerBeat).W))
  val timingDecode = Output(Bool())
  val timingXRead = Output(Bool())
  val timingMulRequest = Output(Bool())
  val timingMulResponse = Output(Bool())
  /** 每个 bit 对应当前 A beat 的一个 Cuper slot。 */
  val timingValidSlotMask = Output(UInt(SpmvCuperDecode.lanesPerBeat.W))
  val timingXReadMask = Output(UInt(SpmvCuperDecode.lanesPerBeat.W))
  val timingMulRequestMask = Output(UInt(SpmvCuperDecode.lanesPerBeat.W))
  val timingMulResponseMask = Output(UInt(SpmvCuperDecode.lanesPerBeat.W))
  /** 当前周期写入每个 local_X 副本的 X 元素数。 */
  val timingXWriteLanes = Output(UInt(log2Ceil(config.xElementsPerBeat + 1).W))
  val timingStreamsComplete = Output(Bool())
  /** 按 Cuper A channel 导出的计算时序；每个向量元素对应一个独立 PE。 */
  val timingBeatAcceptedByChannel = Output(Vec(config.aReaderCount, Bool()))
  val timingValidSlotMaskByChannel = Output(
    Vec(config.aReaderCount, UInt(SpmvCuperDecode.lanesPerBeat.W)))
  val timingXReadMaskByChannel = Output(
    Vec(config.aReaderCount, UInt(SpmvCuperDecode.lanesPerBeat.W)))
  val timingMulRequestMaskByChannel = Output(
    Vec(config.aReaderCount, UInt(SpmvCuperDecode.lanesPerBeat.W)))
  val timingMulResponseMaskByChannel = Output(
    Vec(config.aReaderCount, UInt(SpmvCuperDecode.lanesPerBeat.W)))
  val timingComputeDoneByChannel = Output(Vec(config.aReaderCount, Bool()))
  val mulProductChecksum = Output(UInt(config.xElementWidth.W))
}

/** 跟踪一轮多路 reader 请求，避免把尚未发起的 idle reader 误判成已完成。 */
private[spmv] final class SpmvRequestCompletionTracker(streamCount: Int) extends Module {
  require(streamCount > 0, s"完成跟踪的 stream 数量必须为正数，实际为 $streamCount")

  val io = IO(new Bundle {
    val enable = Input(Bool())
    /** map 指明本 batch 真正拥有 A beat 的 reader。 */
    val active = Input(Vec(streamCount, Bool()))
    val requestAccepted = Input(Vec(streamCount, Bool()))
    val idle = Input(Vec(streamCount, Bool()))
    val complete = Output(Bool())
  })

  private val requestSeen = RegInit(VecInit(Seq.fill(streamCount)(false.B)))
  when(!io.enable) {
    requestSeen.foreach(_ := false.B)
  }.otherwise {
    for (index <- 0 until streamCount) {
      when(io.requestAccepted(index)) {
        requestSeen(index) := true.B
      }
    }
  }

  private val requestedOrInactive = VecInit((0 until streamCount).map { index =>
    !io.active(index) || requestSeen(index)
  })
  private val idleOrInactive = VecInit((0 until streamCount).map { index =>
    !io.active(index) || io.idle(index)
  })
  io.complete := io.enable && requestedOrInactive.asUInt.andR && idleOrInactive.asUInt.andR
}

/**
  * SPMV 输入顶层组装。
  *
  * [[SpmvAInput]] 和 [[SpmvXInput]] 负责独立 HBM reader 阵列，
  * [[SpmvInputConsumer]] 负责输入观测；每个 [[SpmvLocalX]] 按与 Cuper A
  * 分片同宽的 8192 列窗口把广播后的 FP64 X 写入自己的 4 份片上副本。
  * 每个 [[SpmvMulEngine]] 对应一个 A HBM channel，在唯一一次 A 输入上并行验证
  * Mixed-V3 FP64 乘法。本层连接 16 路 A、两路 X 条带、一路控制面广播、片上 X
  * 填充和顶层状态端口。
  */
final class SpmvInputTop(config: SpmvInputConfig) extends Module {
  require(config.xReaderCount == 2,
    s"当前输入顶层要求两路 X 输入，实际为 ${config.xReaderCount}")
  require(config.ctrlReaderCount == 1,
    s"当前输入顶层要求一路控制面广播，实际为 ${config.ctrlReaderCount}")
  require(config.xWindowSize % SpmvCuperDecode.xPageElements == 0,
    s"Cuper X 窗口必须按 page 对齐，实际为 ${config.xWindowSize}/${SpmvCuperDecode.xPageElements}")
  require(config.xWriteLanes <= SpmvCuperDecode.xPageElements &&
    SpmvCuperDecode.xPageElements % config.xWriteLanes == 0,
    s"X 广播写宽必须整除 Cuper page，实际为 ${config.xWriteLanes}/${SpmvCuperDecode.xPageElements}")
  val io = IO(new SpmvInputTopIO(config))

  private val aInput = Module(new SpmvAInput(config, config.aReaderCount))
  private val xInput = Module(new SpmvXInput(config, config.xReaderCount))
  private val ctrlInput = Module(new SpmvCtrlInput(config, config.ctrlReaderCount))
  private val localXs = Seq.tabulate(config.aReaderCount) { _ =>
    Module(new SpmvLocalX(config))
  }
  private val ctrlMap = Module(new SpmvCuperMap(config))
  private val mulEngines = Seq.tabulate(config.aReaderCount) { channel =>
    Module(new SpmvMulEngine(config, channel))
  }
  private val aCompletion = Module(new SpmvRequestCompletionTracker(config.aReaderCount))
  private val consumers = Seq.tabulate(config.aReaderCount) { _ =>
    Module(new SpmvInputConsumer(config.axiDataWidth, config.xReaderCount))
  }
  aCompletion.io.enable := io.mulEnable
  aCompletion.io.idle := aInput.io.idle
  aCompletion.io.active := ctrlMap.io.batchActive
  ctrlMap.io.batchIndex := io.mulBatch
  private val aRequestMapError = RegInit(false.B)
  when(!io.mulEnable) {
    aRequestMapError := false.B
  }

  // 每路 A 保持独立的请求、HBM 端口和 Cuper PE。计算时 PE 接受 beat 的同一拍
  // 才旁路给消费端，使唯一一次 A 读取同时完成乘法 IP 驱动与 checksum 观测。
  consumers.zipWithIndex.foreach { case (consumer, index) =>
    val mulEngine = mulEngines(index)
    io.aRequest(index) <> aInput.io.request(index)
    io.aHbm(index) <> aInput.io.axi(index)
    consumer.io.a.valid := aInput.io.output(index).valid && (!io.mulEnable || mulEngine.io.a.ready)
    consumer.io.a.bits := aInput.io.output(index).bits
    mulEngine.io.a.valid := io.mulEnable && aInput.io.output(index).valid &&
      consumer.io.a.ready
    mulEngine.io.a.bits := aInput.io.output(index).bits
    aInput.io.output(index).ready := Mux(
      io.mulEnable,
      mulEngine.io.a.ready && consumer.io.a.ready,
      consumer.io.a.ready
    )
    io.aIdle(index) := aInput.io.idle(index)
    io.aBusy(index) := aInput.io.busy(index)
    io.aDone(index) := aInput.io.done(index)
    io.aError(index) := aInput.io.error(index)
    aCompletion.io.requestAccepted(index) := io.aRequest(index).fire
    when(io.mulEnable && io.aRequest(index).fire &&
      (!ctrlMap.io.batchActive(index) ||
        io.aRequest(index).bits.beats =/= ctrlMap.io.batchBeatCount(index))) {
      aRequestMapError := true.B
    }
  }

  private val pingPongSchedule = config.xPortSchedule == SpmvXPortSchedule.PingPong
  private val xGroupActive = RegInit(false.B)
  private val xFinished = RegInit(false.B)
  private val x0Seen = RegInit(false.B)
  private val x1Seen = RegInit(false.B)
  private val xHoldValid = RegInit(false.B)
  private val xHoldData = Reg(UInt(config.axiDataWidth.W))
  private val xHoldLast = RegInit(false.B)
  private val xWindowReady = RegInit(false.B)
  private val xColumn = RegInit(0.U(log2Ceil(config.xWindowSize).W))
  private val xPageCount = config.xWindowSize / SpmvCuperDecode.xPageElements
  private val xPageReady = RegInit(VecInit(Seq.fill(xPageCount)(false.B)))
  for (lane <- 0 until config.xReaderCount) {
    io.xRequest(lane) <> xInput.io.request(lane)
    io.xHbm(lane) <> xInput.io.axi(lane)
    io.xIdle(lane) := xInput.io.idle(lane)
    io.xBusy(lane) := xInput.io.busy(lane)
    io.xDone(lane) := xInput.io.done(lane)
    io.xError(lane) := xInput.io.error(lane)
  }
  private val xRequestFire = io.xRequest.map(_.fire).reduce(_ || _)

  // 双路 X 同拍捕获：X0 当拍写入 8 个 FP64，X1 寄存在下一拍写入。这样两路 HBM
  // 都能按真实 512-bit PC 供数，而每个 URAM bank 仍保持 1W。
  private val xPending = xGroupActive && !xFinished
  private val x0Available = xPending && xInput.io.output(0).valid
  private val x1Available = xPending && xInput.io.output(1).valid
  private val x1Complete = x1Seen &&
    xInput.io.idle(1) && !xInput.io.output(1).valid && !xHoldValid
  private val x0OnlyWindow = x0Seen && !x1Seen &&
    xInput.io.idle(1) && !io.xRequest(1).valid
  private val consumerXReady = consumers.map { consumer =>
    consumer.io.x.map(_.ready).reduce(_ && _)
  }.reduce(_ && _)
  private val takePair = x0Available && !xHoldValid && consumerXReady &&
    (x1Available || x1Complete || x0OnlyWindow)
  private val writeHeld = xHoldValid
  private val writeX = takePair || writeHeld
  private val writeLast = Mux(writeHeld, xHoldLast,
    xInput.io.output(0).bits.last && !x1Available)
  private val writeData = Mux(writeHeld, xHoldData, xInput.io.output(0).bits.data)
  consumers.foreach { consumer =>
    consumer.io.x(0).valid := takePair
    consumer.io.x(0).bits := xInput.io.output(0).bits
    consumer.io.x(1).valid := takePair && x1Available
    consumer.io.x(1).bits := xInput.io.output(1).bits
  }
  xInput.io.output(0).ready := takePair
  xInput.io.output(1).ready := takePair && x1Available
  private val xWriteElements = config.xWriteLanes.U
  private val xWriteEnd = xColumn +& xWriteElements
  private val xElements = writeData.asTypeOf(
    Vec(config.xElementsPerBeat, UInt(config.xElementWidth.W)))

  when(xRequestFire) {
    xGroupActive := true.B
    xFinished := false.B
    x0Seen := io.xRequest(0).fire
    x1Seen := io.xRequest(1).fire
    xHoldValid := false.B
    xHoldLast := false.B
    xWindowReady := false.B
    xColumn := 0.U
    xPageReady.foreach(_ := false.B)
  }.otherwise {
    when(io.xRequest(0).fire) {
      x0Seen := true.B
    }
    when(io.xRequest(1).fire) {
      x1Seen := true.B
    }
    when(takePair && x1Available) {
      xHoldValid := true.B
      xHoldData := xInput.io.output(1).bits.data
      xHoldLast := xInput.io.output(1).bits.last
    }
    when(writeHeld) {
      xHoldValid := false.B
    }
    when(writeX) {
      xColumn := xColumn + xWriteElements
      for (page <- 0 until xPageCount) {
        val pageEnd = ((page + 1) * SpmvCuperDecode.xPageElements).U
        when(xColumn < pageEnd && xWriteEnd >= pageEnd) {
          xPageReady(page) := true.B
        }
      }
    }
    when(writeX && writeLast) {
      xFinished := true.B
      xGroupActive := false.B
      xHoldValid := false.B
      xWindowReady := true.B
    }
  }
  localXs.zip(mulEngines).zipWithIndex.foreach { case ((localX, mulEngine), index) =>
    localX.io.writeValid := writeX
    localX.io.writeColumn := xColumn
    for (bank <- 0 until config.xBankCount) {
      localX.io.writeElements(bank) := Mux(writeX, xElements(bank), 0.U)
      localX.io.writeMask(bank) := writeX
    }
    mulEngine.io.enable := io.mulEnable
    mulEngine.io.batch := io.mulBatch
    mulEngine.io.workExpected := ctrlMap.io.batchActive(index)
    mulEngine.io.pageReady := xPageReady
    mulEngine.io.xWindowReady := xWindowReady
    mulEngine.io.portSafeOverlap := (if (pingPongSchedule) writeX && !xWindowReady else false.B)
    mulEngine.io.xReadData := localX.io.readData
    // idle 无法区分“已完成”和“从未请求”；必须先看见全部 A 请求，再允许完成。
    mulEngine.io.streamsComplete := aCompletion.io.complete
    localX.io.readEnable := mulEngine.io.xReadEnable
    localX.io.readColumn := mulEngine.io.xReadColumn
    // 当前乘法-only 顶层只 drain 产品流并验收 checksum；新的 L1 会直接替换这一接收端。
    mulEngine.io.product.foreach(_.ready := true.B)
  }
  private val timingBeatAcceptedByChannel = VecInit(mulEngines.map(_.io.timingBeatAccepted))
  private val timingValidSlotMaskByChannel = VecInit(mulEngines.map(_.io.timingValidSlotMask))
  private val timingXReadMaskByChannel = VecInit(mulEngines.map(_.io.timingXReadMask))
  private val timingMulRequestMaskByChannel = VecInit(mulEngines.map(_.io.timingMulRequestMask))
  private val timingMulResponseMaskByChannel = VecInit(mulEngines.map(_.io.timingMulResponseMask))
  private val timingComputeDoneByChannel = VecInit(mulEngines.map(_.io.computeDone))
  private val timingAnyBeatAccepted = timingBeatAcceptedByChannel.asUInt.orR

  io.ctrlMapReady := ctrlMap.io.loaded
  io.mulReady := ctrlMap.io.batchValid && VecInit(mulEngines.map(_.io.ready)).asUInt.andR
  io.computeDone := timingComputeDoneByChannel.asUInt.andR
  io.mulError := ctrlMap.io.error || aRequestMapError || mulEngines.map(_.io.error).reduce(_ || _)
  io.timingBeatAccepted := timingAnyBeatAccepted
  io.timingChannel := Mux(timingAnyBeatAccepted, PriorityEncoder(timingBeatAcceptedByChannel), 0.U)
  io.timingSlot := Mux(timingAnyBeatAccepted,
    PriorityMux(mulEngines.map(engine => engine.io.timingBeatAccepted -> engine.io.timingSlot)), 0.U)
  io.timingDecode := mulEngines.map(_.io.timingDecode).reduce(_ || _)
  io.timingXRead := mulEngines.map(_.io.timingXRead).reduce(_ || _)
  io.timingMulRequest := mulEngines.map(_.io.timingMulRequest).reduce(_ || _)
  io.timingMulResponse := mulEngines.map(_.io.timingMulResponse).reduce(_ || _)
  io.timingValidSlotMask := timingValidSlotMaskByChannel.reduce(_ | _)
  io.timingXReadMask := timingXReadMaskByChannel.reduce(_ | _)
  io.timingMulRequestMask := timingMulRequestMaskByChannel.reduce(_ | _)
  io.timingMulResponseMask := timingMulResponseMaskByChannel.reduce(_ | _)
  io.timingXWriteLanes := Mux(writeX, xWriteElements, 0.U)
  io.timingStreamsComplete := aCompletion.io.complete
  io.timingBeatAcceptedByChannel := timingBeatAcceptedByChannel
  io.timingValidSlotMaskByChannel := timingValidSlotMaskByChannel
  io.timingXReadMaskByChannel := timingXReadMaskByChannel
  io.timingMulRequestMaskByChannel := timingMulRequestMaskByChannel
  io.timingMulResponseMaskByChannel := timingMulResponseMaskByChannel
  io.timingComputeDoneByChannel := timingComputeDoneByChannel
  io.mulProductChecksum := mulEngines.map(_.io.productChecksum).reduce(_ ^ _)

  // 一路控制 HBM 原子广播到全部消费端，并在同一握手周期解析 Cuper map；之后同一条
  // 通道可以改成读写其它 JPCG 侧带数据。
  private val ctrlActive = RegInit(false.B)
  private val ctrlFinished = RegInit(false.B)
  io.ctrlRequest(0) <> ctrlInput.io.request(0)
  io.ctrlHbm(0) <> ctrlInput.io.axi(0)
  io.ctrlIdle(0) := ctrlInput.io.idle(0)
  io.ctrlBusy(0) := ctrlInput.io.busy(0)
  io.ctrlDone(0) := ctrlInput.io.done(0)
  io.ctrlError(0) := ctrlInput.io.error(0)
  when(io.ctrlRequest(0).fire) {
    ctrlActive := true.B
    ctrlFinished := false.B
  }
  private val ctrlPending = ctrlActive && !ctrlFinished
  private val broadcastCtrl = ctrlPending &&
    ctrlInput.io.output(0).valid &&
    consumers.map(_.io.ctrl.ready).reduce(_ && _)
  consumers.foreach { consumer =>
    consumer.io.ctrl.valid := broadcastCtrl
    consumer.io.ctrl.bits := ctrlInput.io.output(0).bits
  }
  ctrlInput.io.output(0).ready := broadcastCtrl
  private val ctrlFinishing = ctrlInput.io.output(0).fire && ctrlInput.io.output(0).bits.last
  ctrlMap.io.fire := ctrlInput.io.output(0).fire
  ctrlMap.io.data := ctrlInput.io.output(0).bits.data
  ctrlMap.io.last := ctrlInput.io.output(0).bits.last
  when(ctrlFinishing) {
    ctrlFinished := true.B
  }
  when(ctrlActive && (ctrlFinished || ctrlFinishing)) {
    ctrlActive := false.B
  }

  // 消费观测始终导出；乘法状态见 mulReady/computeDone/mulProductChecksum。
  consumers.zipWithIndex.foreach { case (consumer, index) =>
    io.consumerABeats(index) := consumer.io.aBeats
    io.consumerXBeats(index) := consumer.io.xBeats
    io.consumerCtrlBeats(index) := consumer.io.ctrlBeats
    io.consumerAChecksum(index) := consumer.io.aChecksum
    io.consumerXChecksum(index) := consumer.io.xChecksum
    io.consumerCtrlChecksum(index) := consumer.io.ctrlChecksum
    io.consumerError(index) := consumer.io.error
  }
}
