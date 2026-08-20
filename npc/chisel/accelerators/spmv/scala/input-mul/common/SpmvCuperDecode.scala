package accelerators.spmv.inputmul.common

import chisel3._

/** Cuperflow A slot v5 解码。
  *
  * slot = localCol[63:51] | segmentId[50:48] | localRow[47:32] | fp32[31:0]。
  * localRow 是 PE-local 行标，乘法响应结合静态 PE 身份还原全局 CSR 行标。segmentId 在
  * Cuperflow map v3 中解释为 X segmentId：由顶层选择段起点和 payload 前缀；FMUL
  * 只原样随产品响应透传。全零填充由顶层掩码跳过 X 读取和 FMUL。
  */
object SpmvCuperDecode {
  val columnBits: Int = 13
  val tagBits: Int = 3
  val rowBits: Int = 16
  val globalRowBits: Int = 32
  val lanesPerBeat: Int = 8
  /** X page 是 64 个连续 FP64，与 Cuper encoder 的列 slice 保持一致。 */
  val xPageElements: Int = 64

  final class DecodedSlot extends Bundle {
    val localColumn = UInt(columnBits.W)
    val segmentId = UInt(tagBits.W)
    val localRow = UInt(rowBits.W)
    val fp32 = UInt(32.W)
  }

  def decodeSlot(slot: UInt): DecodedSlot = {
    val decoded = Wire(new DecodedSlot)
    decoded.localColumn := slot(63, 51)
    decoded.segmentId := slot(50, 48)
    decoded.localRow := slot(47, 32)
    decoded.fp32 := slot(31, 0)
    decoded
  }
}
