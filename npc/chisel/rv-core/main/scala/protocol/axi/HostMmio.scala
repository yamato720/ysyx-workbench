package scpu.protocol

import chisel3._
import chisel3.util._

/** 外部内存平台中供 AM 串口使用的最小可综合 MMIO，从属实现不绑定任何具体板卡。 */
class AxiLiteHostMmioSlave(addrWidth: Int, dataWidth: Int) extends Module {
  require(dataWidth == 32 || dataWidth == 64)
  private val byteLanes = dataWidth / 8
  private val laneBits = log2Ceil(byteLanes)

  val io = IO(new Bundle {
    val axi = Flipped(new AxiLiteMasterIO(addrWidth, dataWidth))
    val putch = Decoupled(UInt(8.W))
  })

  val awValid = RegInit(false.B)
  val awAddress = RegInit(0.U(addrWidth.W))
  val wValid = RegInit(false.B)
  val wData = RegInit(0.U(dataWidth.W))
  val wStrb = RegInit(0.U(byteLanes.W))
  val bValid = RegInit(false.B)
  val rValid = RegInit(false.B)

  io.axi.aw.ready := !awValid && !bValid
  io.axi.w.ready := !wValid && !bValid
  io.axi.b.valid := bValid
  io.axi.b.bits.resp := AxiLiteResp.OKAY
  io.axi.ar.ready := !rValid
  io.axi.r.valid := rValid
  io.axi.r.bits.data := 0.U
  io.axi.r.bits.resp := AxiLiteResp.OKAY

  when(io.axi.aw.fire) {
    awValid := true.B
    awAddress := io.axi.aw.bits.addr
  }
  when(io.axi.w.fire) {
    wValid := true.B
    wData := io.axi.w.bits.data
    wStrb := io.axi.w.bits.strb
  }
  when(io.axi.b.fire) { bValid := false.B }
  when(io.axi.ar.fire) { rValid := true.B }
    .elsewhen(io.axi.r.fire) { rValid := false.B }

  val writeCommit = awValid && wValid && !bValid
  val serialWrite = awAddress === "ha00003f8".U
  val lane = awAddress(laneBits - 1, 0)
  val selectedByte = (wData >> (lane << 3))(7, 0)
  val selectedStrobe = wStrb(lane)
  io.putch.valid := writeCommit && serialWrite && selectedStrobe
  io.putch.bits := selectedByte

  val canCommit = !serialWrite || !selectedStrobe || io.putch.ready
  when(writeCommit && canCommit) {
    awValid := false.B
    wValid := false.B
    bValid := true.B
  }
}
