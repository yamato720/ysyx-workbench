package accelerator.spmv.io

import chisel3._

/** A 输入 reader 的接口壳，暂不实现地址发射和数据接收状态机。 */
final class SpmvAReader(
  addrWidth: Int = 64,
  dataWidth: Int = 512,
  idWidth: Int = 4
) extends Module {
  val io = IO(new SpmvReaderIO(addrWidth, dataWidth, idWidth))

  // 保留标准 clock/reset 端口，供 smoke host 验证复位后的静态接口状态。
  private val resetSeen = RegInit(false.B)
  resetSeen := true.B

  io.request.ready := false.B
  io.hbm.ar.valid := false.B
  io.hbm.ar.bits := 0.U.asTypeOf(io.hbm.ar.bits)
  io.hbm.r.ready := false.B
  io.output.valid := false.B
  io.output.bits := 0.U.asTypeOf(io.output.bits)
  io.idle := true.B
  io.busy := false.B
  io.done := false.B
  io.error := false.B
}
