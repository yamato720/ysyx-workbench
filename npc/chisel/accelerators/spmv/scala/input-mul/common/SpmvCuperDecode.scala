package accelerators.spmv.inputmul.common

import chisel3._

/** Cuper A slot v4 解码。
  *
  * slot = localCol[63:51] | tag[50:48] | localRow[47:32] | fp32[31:0]。
  * localRow 是 PE-local 行标，乘法响应结合静态 PE 身份还原全局 CSR 行标。tag 当前没有乘法控制语义，所有值都
  * 原样随产品响应透传；全零填充 slot 也照常读 X、进入 FMUL。
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
    val tag = UInt(tagBits.W)
    val localRow = UInt(rowBits.W)
    val fp32 = UInt(32.W)
  }

  def decodeSlot(slot: UInt): DecodedSlot = {
    val decoded = Wire(new DecodedSlot)
    decoded.localColumn := slot(63, 51)
    decoded.tag := slot(50, 48)
    decoded.localRow := slot(47, 32)
    decoded.fp32 := slot(31, 0)
    decoded
  }
}
