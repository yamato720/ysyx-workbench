package accelerators.spmv.input

import chisel3._

/** Cuper A slot v3 解码。
  *
  * slot = localCol[63:51] | tag[50:48] | row[47:32] | fp32[31:0]。
  * row 是直接 CSR 行标，不再通过 PE 身份还原。tag 当前没有乘法控制语义，所有值都
  * 原样随产品响应透传；全零填充 slot 也照常读 X、进入 FMUL。
  */
object SpmvCuperDecode {
  val columnBits: Int = 13
  val tagBits: Int = 3
  val rowBits: Int = 16
  val lanesPerBeat: Int = 8

  final class DecodedSlot extends Bundle {
    val localColumn = UInt(columnBits.W)
    val tag = UInt(tagBits.W)
    val row = UInt(rowBits.W)
    val fp32 = UInt(32.W)
  }

  def decodeSlot(slot: UInt): DecodedSlot = {
    val decoded = Wire(new DecodedSlot)
    decoded.localColumn := slot(63, 51)
    decoded.tag := slot(50, 48)
    decoded.row := slot(47, 32)
    decoded.fp32 := slot(31, 0)
    decoded
  }
}
