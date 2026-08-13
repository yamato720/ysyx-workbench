package accelerators.spmv.reader

import chisel3._
import chisel3.util._

/** 把一段连续 beat 请求转换为不跨 4 KiB 边界的 AXI4 INCR burst。
  *
  * AR 发射与 R 接收独立推进，burst 长度队列允许在当前 burst 返回期间提前发出
  * 下一笔 AR。HBM 连续供数时，4 KiB 边界不会在 R 通道形成空拍。R beat 只有在
  * 后级接受时才握手，因此下游反压仍会直接传回 HBM。`done` 在整个请求的最后一个
  * 输出 beat 握手后脉冲一拍，`error` 保持到下一次请求。
  */
private[spmv] final class SpmvReader(
  addrWidth: Int,
  dataWidth: Int,
  idWidth: Int,
  maxOutstandingBursts: Int
) extends Module {
  require(dataWidth >= 8 && (dataWidth & (dataWidth - 1)) == 0,
    s"reader 数据位宽必须是按字节对齐的二次幂，实际为 $dataWidth")
  private val beatBytes = dataWidth / 8
  require(beatBytes <= 4096,
    s"reader 单 beat 不能超过 AXI 4 KiB 边界，实际为 $beatBytes 字节")
  require(maxOutstandingBursts >= 2,
    s"满带宽 reader 至少需要两笔 outstanding burst，实际为 $maxOutstandingBursts")

  val io = IO(new SpmvReaderIO(addrWidth, dataWidth, idWidth))

  private val active = RegInit(false.B)
  private val issueAddress = Reg(UInt(addrWidth.W))
  private val issueRemaining = Reg(UInt(32.W))
  private val receiveRemaining = Reg(UInt(32.W))
  private val burstBeat = Reg(UInt(9.W))
  private val error = RegInit(false.B)
  private val done = RegInit(false.B)
  private val burstLengths = Module(new Queue(UInt(9.W), maxOutstandingBursts))

  private val addressOffset = issueAddress(11, 0)
  private val bytesToBoundary = 4096.U(13.W) - addressOffset
  private val beatsToBoundary = bytesToBoundary >> log2Ceil(beatBytes)
  private val addressBurstBeats = Mux(issueRemaining > 256.U, 256.U, issueRemaining)
  private val selectedBurstBeats = Mux(
    addressBurstBeats > beatsToBoundary,
    beatsToBoundary,
    addressBurstBeats
  )
  private val expectedBurstLast = burstBeat === burstLengths.io.deq.bits - 1.U
  private val responseError = io.axi.r.bits.resp =/= 0.U ||
    io.axi.r.bits.id =/= 0.U || io.axi.r.bits.last =/= expectedBurstLast

  io.request.ready := !active
  io.axi.ar.valid := active && issueRemaining =/= 0.U && burstLengths.io.enq.ready
  io.axi.ar.bits.id := 0.U
  io.axi.ar.bits.addr := issueAddress
  io.axi.ar.bits.len := (selectedBurstBeats - 1.U)(7, 0)
  io.axi.ar.bits.size := log2Ceil(beatBytes).U
  io.axi.ar.bits.burst := 1.U
  io.axi.ar.bits.lock := 0.U
  io.axi.ar.bits.cache := 0.U
  io.axi.ar.bits.prot := 0.U
  io.axi.ar.bits.qos := 0.U

  burstLengths.io.enq.valid := io.axi.ar.fire
  burstLengths.io.enq.bits := selectedBurstBeats

  io.output.valid := active && burstLengths.io.deq.valid && io.axi.r.valid
  io.output.bits.data := io.axi.r.bits.data
  io.output.bits.last := receiveRemaining === 1.U
  io.output.bits.error := error || responseError
  io.axi.r.ready := active && burstLengths.io.deq.valid && io.output.ready
  burstLengths.io.deq.ready := io.output.fire && expectedBurstLast

  io.idle := !active
  io.busy := active
  io.done := done
  io.error := error

  done := false.B
  when(io.request.fire) {
    burstBeat := 0.U
    error := false.B
    when(io.request.bits.beats === 0.U ||
      io.request.bits.address(log2Ceil(beatBytes) - 1, 0) =/= 0.U) {
      // 空请求或未按 beat 对齐的请求不进入 AXI，总线外直接完成并留下错误状态。
      error := true.B
      done := true.B
    }.otherwise {
      active := true.B
      issueAddress := io.request.bits.address
      issueRemaining := io.request.bits.beats
      receiveRemaining := io.request.bits.beats
    }
  }

  when(io.axi.ar.fire) {
    issueAddress := issueAddress + (selectedBurstBeats << log2Ceil(beatBytes))
    issueRemaining := issueRemaining - selectedBurstBeats
  }

  when(io.output.fire) {
    when(responseError) {
      error := true.B
    }
    receiveRemaining := receiveRemaining - 1.U
    when(receiveRemaining === 1.U) {
      done := true.B
      active := false.B
      burstBeat := 0.U
    }.elsewhen(expectedBurstLast) {
      // 下一笔 burst 已经在队列中，下一拍可直接接受其第一个 R beat。
      burstBeat := 0.U
    }.otherwise {
      burstBeat := burstBeat + 1.U
    }
  }
}
