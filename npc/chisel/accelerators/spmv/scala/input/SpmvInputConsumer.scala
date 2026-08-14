package accelerators.spmv.input

import chisel3._
import chisel3.util._
import accelerators.spmv.reader.SpmvReaderBeat

/** 一个 A/X/Ctrl 输入组的观测消费端。
  *
  * 持续记录 beat 数、错误和 64-bit lane XOR。A 在乘法引擎真正接受的同一拍
  * 原子旁路进来，X 和控制面则验证广播是否完整。
  */
final class SpmvInputConsumer(dataWidth: Int, xInputCount: Int) extends Module {
  require(dataWidth >= 64 && dataWidth % 64 == 0,
    s"消费端数据位宽必须包含完整 64-bit lane，实际为 $dataWidth")
  require(xInputCount > 0, s"X 输入数量必须为正数，实际为 $xInputCount")

  val io = IO(new Bundle {
    val a = Flipped(Decoupled(new SpmvReaderBeat(dataWidth)))
    val x = Vec(xInputCount, Flipped(Decoupled(new SpmvReaderBeat(dataWidth))))
    val ctrl = Flipped(Decoupled(new SpmvReaderBeat(dataWidth)))
    val aBeats = Output(UInt(32.W))
    val xBeats = Output(UInt(32.W))
    val ctrlBeats = Output(UInt(32.W))
    val aChecksum = Output(UInt(64.W))
    val xChecksum = Output(UInt(64.W))
    val ctrlChecksum = Output(UInt(64.W))
    val error = Output(Bool())
  })

  private def foldBeat(data: UInt): UInt =
    data.asTypeOf(Vec(dataWidth / 64, UInt(64.W))).reduce(_ ^ _)

  private val aBeats = RegInit(0.U(32.W))
  private val xBeats = RegInit(0.U(32.W))
  private val ctrlBeats = RegInit(0.U(32.W))
  private val aChecksum = RegInit(0.U(64.W))
  private val xChecksum = RegInit(0.U(64.W))
  private val ctrlChecksum = RegInit(0.U(64.W))
  private val error = RegInit(false.B)

  io.a.ready := true.B
  io.x.foreach(_.ready := true.B)
  io.ctrl.ready := true.B
  private val xFires = VecInit(io.x.map(_.fire))
  private val xFireCount = PopCount(xFires)
  private val xChecksumDelta = io.x.zip(xFires).map { case (input, fire) =>
    Mux(fire, foldBeat(input.bits.data), 0.U(64.W))
  }.reduce(_ ^ _)
  private val xInputError = io.x.zip(xFires).map { case (input, fire) =>
    fire && input.bits.error
  }.reduce(_ || _)

  when(io.a.fire) {
    aBeats := aBeats + 1.U
    aChecksum := aChecksum ^ foldBeat(io.a.bits.data)
  }
  when(xFires.asUInt.orR) {
    xBeats := xBeats + xFireCount
    xChecksum := xChecksum ^ xChecksumDelta
  }
  when(io.ctrl.fire) {
    ctrlBeats := ctrlBeats + 1.U
    ctrlChecksum := ctrlChecksum ^ foldBeat(io.ctrl.bits.data)
  }
  when(io.a.fire || xFires.asUInt.orR || io.ctrl.fire) {
    error := error || (io.a.fire && io.a.bits.error) || xInputError ||
      (io.ctrl.fire && io.ctrl.bits.error)
  }

  io.aBeats := aBeats
  io.xBeats := xBeats
  io.ctrlBeats := ctrlBeats
  io.aChecksum := aChecksum
  io.xChecksum := xChecksum
  io.ctrlChecksum := ctrlChecksum
  io.error := error
}
