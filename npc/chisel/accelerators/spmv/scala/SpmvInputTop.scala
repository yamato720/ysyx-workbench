package accelerators.spmv

import chisel3._
import chisel3.util._
import accelerators.spmv.input.{SpmvAInput, SpmvInputConsumer, SpmvXInput}
import accelerators.spmv.reader.SpmvReaderRequest
import npc.ip.axi.Axi4ReadMasterIO

/** SPMV 输入层顶层接口。
  *
  * 一个 A 输入封装承载全部 A HBM，每个内部 reader 连接一个消费端；当前一个
  * 1-HBM X 输入封装把同一个 beat 原子广播给全部消费端。消费端暂不计算，只导出
  * 输入计数、校验值和错误状态。
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

  val aHbm = Vec(config.aReaderCount, hbmType)
  val xHbm = Vec(config.xReaderCount, hbmType)

  val aIdle = Output(Vec(config.aReaderCount, Bool()))
  val aBusy = Output(Vec(config.aReaderCount, Bool()))
  val aDone = Output(Vec(config.aReaderCount, Bool()))
  val aError = Output(Vec(config.aReaderCount, Bool()))
  val xIdle = Output(Vec(config.xReaderCount, Bool()))
  val xBusy = Output(Vec(config.xReaderCount, Bool()))
  val xDone = Output(Vec(config.xReaderCount, Bool()))
  val xError = Output(Vec(config.xReaderCount, Bool()))
  val consumerABeats = Output(Vec(config.aReaderCount, UInt(32.W)))
  val consumerXBeats = Output(Vec(config.aReaderCount, UInt(32.W)))
  val consumerAChecksum = Output(Vec(config.aReaderCount, UInt(64.W)))
  val consumerXChecksum = Output(Vec(config.aReaderCount, UInt(64.W)))
  val consumerError = Output(Vec(config.aReaderCount, Bool()))
}

/**
  * SPMV 输入顶层组装。
  *
  * [[SpmvAInput]] 和 [[SpmvXInput]] 负责独立 HBM reader 阵列，
  * [[SpmvInputConsumer]] 负责当前的输入观测；本层只连接 16 路 A、
  * 单路 X 原子广播和顶层状态端口。
  */
final class SpmvInputTop(config: SpmvInputConfig) extends Module {
  require(config.xReaderCount == 1,
    s"当前输入顶层只支持一路 X 广播，实际为 ${config.xReaderCount}")
  val io = IO(new SpmvInputTopIO(config))

  private val aInput = Module(new SpmvAInput(config, config.aReaderCount))
  private val xInput = Module(new SpmvXInput(config, config.xReaderCount))
  private val consumers = Seq.tabulate(config.aReaderCount) { _ =>
    Module(new SpmvInputConsumer(config.axiDataWidth))
  }

  // 每路 A 保持独立的请求、HBM 端口和消费路径。
  consumers.zipWithIndex.foreach { case (consumer, index) =>
    io.aRequest(index) <> aInput.io.request(index)
    io.aHbm(index) <> aInput.io.axi(index)
    consumer.io.a <> aInput.io.output(index)
    io.aIdle(index) := aInput.io.idle(index)
    io.aBusy(index) := aInput.io.busy(index)
    io.aDone(index) := aInput.io.done(index)
    io.aError(index) := aInput.io.error(index)
  }

  io.xRequest(0) <> xInput.io.request(0)
  io.xHbm(0) <> xInput.io.axi(0)

  // 广播只在所有消费端都 ready 时同时拉高 valid，避免某一路重复接受同一个 X beat。
  private val allConsumersReady = consumers.map(_.io.x.ready).reduce(_ && _)
  consumers.foreach { consumer =>
    consumer.io.x.valid := xInput.io.output(0).valid && allConsumersReady
    consumer.io.x.bits := xInput.io.output(0).bits
  }
  xInput.io.output(0).ready := allConsumersReady
  io.xIdle(0) := xInput.io.idle(0)
  io.xBusy(0) := xInput.io.busy(0)
  io.xDone(0) := xInput.io.done(0)
  io.xError(0) := xInput.io.error(0)

  // 顶层只导出消费观测，不在这里实现 SpMV 计算。
  consumers.zipWithIndex.foreach { case (consumer, index) =>
    io.consumerABeats(index) := consumer.io.aBeats
    io.consumerXBeats(index) := consumer.io.xBeats
    io.consumerAChecksum(index) := consumer.io.aChecksum
    io.consumerXChecksum(index) := consumer.io.xChecksum
    io.consumerError(index) := consumer.io.error
  }
}
