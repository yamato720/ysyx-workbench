package accelerators.spmv.input

import chisel3._
import chisel3.util._
import accelerators.spmv.reader.SpmvReaderBeat

/** 一个 A/X 输入对的临时消费端。
  *
  * 当前不执行乘加，只持续接受数据并记录 beat 数、错误和 64-bit lane XOR。
  * 这些状态让仿真可以证明每路 A 独立到达，同时证明同一路 X 被完整广播到所有消费端。
  */
final class SpmvInputConsumer(dataWidth: Int) extends Module {
  require(dataWidth >= 64 && dataWidth % 64 == 0,
    s"消费端数据位宽必须包含完整 64-bit lane，实际为 $dataWidth")

  val io = IO(new Bundle {
    val a = Flipped(Decoupled(new SpmvReaderBeat(dataWidth)))
    val x = Flipped(Decoupled(new SpmvReaderBeat(dataWidth)))
    val aBeats = Output(UInt(32.W))
    val xBeats = Output(UInt(32.W))
    val aChecksum = Output(UInt(64.W))
    val xChecksum = Output(UInt(64.W))
    val error = Output(Bool())
  })

  private def foldBeat(data: UInt): UInt =
    data.asTypeOf(Vec(dataWidth / 64, UInt(64.W))).reduce(_ ^ _)

  private val aBeats = RegInit(0.U(32.W))
  private val xBeats = RegInit(0.U(32.W))
  private val aChecksum = RegInit(0.U(64.W))
  private val xChecksum = RegInit(0.U(64.W))
  private val error = RegInit(false.B)

  io.a.ready := true.B
  io.x.ready := true.B
  when(io.a.fire) {
    aBeats := aBeats + 1.U
    aChecksum := aChecksum ^ foldBeat(io.a.bits.data)
    error := error || io.a.bits.error
  }
  when(io.x.fire) {
    xBeats := xBeats + 1.U
    xChecksum := xChecksum ^ foldBeat(io.x.bits.data)
    error := error || io.x.bits.error
  }

  io.aBeats := aBeats
  io.xBeats := xBeats
  io.aChecksum := aChecksum
  io.xChecksum := xChecksum
  io.error := error
}
