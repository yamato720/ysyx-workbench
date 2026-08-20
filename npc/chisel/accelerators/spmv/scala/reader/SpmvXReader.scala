package accelerators.spmv.reader

import chisel3._
import chisel3.util.Queue

/** X 输入 reader：与 A reader 共享 AXI 行为，广播策略由输入顶层负责。
  *
  * Cuperflow 在异步 R FIFO 之后启用两拍弹性级。它既切断 FIFO 输出到 map/FSM 的
  * 宽组合路径，也允许后级在 map 译码拍暂时反压而不让 HBM R 通道产生空拍。
  */
final class SpmvXReader(
  addrWidth: Int = 64,
  dataWidth: Int = 512,
  idWidth: Int = 4,
  maxOutstandingBursts: Int = 2,
  bufferOutput: Boolean = false
) extends Module {
  val io = IO(new SpmvReaderIO(addrWidth, dataWidth, idWidth))
  private val reader = Module(new SpmvReader(
    addrWidth,
    dataWidth,
    idWidth,
    maxOutstandingBursts
  ))
  if (bufferOutput) {
    // 禁止 flow bypass 和 ready 旁路；ready 不能从 map/FSM 组合返回 r_cdc。
    val outputBuffer = Module(new Queue(
      new SpmvReaderBeat(dataWidth), entries = 2, pipe = false, flow = false))

    io.request <> reader.io.request
    io.axi <> reader.io.axi
    outputBuffer.io.enq <> reader.io.output
    io.output <> outputBuffer.io.deq
    io.idle := reader.io.idle
    io.busy := reader.io.busy
    io.done := reader.io.done
    io.error := reader.io.error
  } else {
    io <> reader.io
  }
}
