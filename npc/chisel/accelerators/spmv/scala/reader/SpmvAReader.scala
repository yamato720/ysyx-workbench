package accelerators.spmv.reader

import chisel3._

/** A 输入 reader：使用公共连续读实现，同时保留独立模块名供每路实例追踪。 */
final class SpmvAReader(
  addrWidth: Int = 64,
  dataWidth: Int = 512,
  idWidth: Int = 4,
  maxOutstandingBursts: Int = 2
) extends Module {
  val io = IO(new SpmvReaderIO(addrWidth, dataWidth, idWidth))
  private val reader = Module(new SpmvReader(
    addrWidth,
    dataWidth,
    idWidth,
    maxOutstandingBursts
  ))
  io <> reader.io
}
