package accelerator.spmv

import chisel3._
import chisel3.util._
import accelerator.spmv.io.{SpmvAReader, SpmvReaderBeat, SpmvReaderRequest, SpmvXReader}
import npc.SpmvInputConfig
import npc.ip.memory.HbmReadMasterIO

/** SPMV 输入层顶层接口。
  *
  * A reader 和 X reader 各自拥有独立的请求、输出以及 HBM 读端口；顶层不在
  * 这里合并通道，也不预先引入计算或 CSR5 控制逻辑。
  */
final class SpmvInputTopIO(config: SpmvInputConfig) extends Bundle {
  private val requestType = new SpmvReaderRequest(config.axiAddrWidth)
  private val beatType = new SpmvReaderBeat(config.axiDataWidth)
  private val hbmType = new HbmReadMasterIO(
    config.axiAddrWidth,
    config.axiDataWidth,
    config.axiIdWidth
  )

  val aRequest = Vec(config.aReaderCount, Flipped(Decoupled(requestType)))
  val xRequest = Vec(config.xReaderCount, Flipped(Decoupled(requestType)))

  val aHbm = Vec(config.aReaderCount, hbmType)
  val xHbm = Vec(config.xReaderCount, hbmType)

  val aOutput = Vec(config.aReaderCount, Decoupled(beatType))
  val xOutput = Vec(config.xReaderCount, Decoupled(beatType))

  val aIdle = Output(Vec(config.aReaderCount, Bool()))
  val aBusy = Output(Vec(config.aReaderCount, Bool()))
  val aDone = Output(Vec(config.aReaderCount, Bool()))
  val aError = Output(Vec(config.aReaderCount, Bool()))
  val xIdle = Output(Vec(config.xReaderCount, Bool()))
  val xBusy = Output(Vec(config.xReaderCount, Bool()))
  val xDone = Output(Vec(config.xReaderCount, Bool()))
  val xError = Output(Vec(config.xReaderCount, Bool()))
}

/** 当前 SPMV 的输入层顶层：展开所有 A/X reader 并保持 HBM 端口彼此独立。 */
final class SpmvInputTop(config: SpmvInputConfig) extends Module {
  val io = IO(new SpmvInputTopIO(config))

  private val aReaders = Seq.tabulate(config.aReaderCount) { _ =>
    Module(new SpmvAReader(
      config.axiAddrWidth,
      config.axiDataWidth,
      config.axiIdWidth
    ))
  }
  private val xReaders = Seq.tabulate(config.xReaderCount) { _ =>
    Module(new SpmvXReader(
      config.axiAddrWidth,
      config.axiDataWidth,
      config.axiIdWidth
    ))
  }

  aReaders.zipWithIndex.foreach { case (reader, index) =>
    io.aRequest(index) <> reader.io.request
    io.aHbm(index) <> reader.io.hbm
    io.aOutput(index) <> reader.io.output
    io.aIdle(index) := reader.io.idle
    io.aBusy(index) := reader.io.busy
    io.aDone(index) := reader.io.done
    io.aError(index) := reader.io.error
  }

  xReaders.zipWithIndex.foreach { case (reader, index) =>
    io.xRequest(index) <> reader.io.request
    io.xHbm(index) <> reader.io.hbm
    io.xOutput(index) <> reader.io.output
    io.xIdle(index) := reader.io.idle
    io.xBusy(index) := reader.io.busy
    io.xDone(index) := reader.io.done
    io.xError(index) := reader.io.error
  }
}
