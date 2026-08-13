package accelerators.spmv.reader

import chisel3._

/** X 输入 reader：与 A reader 共享 AXI 行为，广播策略由输入顶层负责。 */
final class SpmvXReader(
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
