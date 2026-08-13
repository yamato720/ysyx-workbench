package accelerators.spmv.reader

import chisel3._
import accelerators.common.IndependentAxiReadPortIO

/** 一次连续输入读取的最小描述。 */
final class SpmvReaderRequest(val addrWidth: Int) extends Bundle {
  val address = UInt(addrWidth.W)
  val beats = UInt(32.W)
}

/** reader 向后级交付的一个 HBM beat。 */
final class SpmvReaderBeat(val dataWidth: Int) extends Bundle {
  val data = UInt(dataWidth.W)
  val last = Bool()
  val error = Bool()
}

/** A/X reader 共用的外部控制、HBM 和输出接口。 */
final class SpmvReaderIO(
  val addrWidth: Int = 64,
  val dataWidth: Int = 512,
  val idWidth: Int = 4
) extends IndependentAxiReadPortIO(
  () => new SpmvReaderRequest(addrWidth),
  () => new SpmvReaderBeat(dataWidth),
  addrWidth,
  dataWidth,
  idWidth
)
