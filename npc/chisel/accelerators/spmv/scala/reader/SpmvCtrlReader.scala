package accelerators.spmv.reader

import chisel3._

/** 控制面 reader：与 A/X 共享 AXI 读行为，广播和后续写回由顶层决定。 */
final class SpmvCtrlReader(
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
