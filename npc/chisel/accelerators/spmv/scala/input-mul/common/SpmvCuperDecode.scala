package accelerators.spmv.inputmul.common

import chisel3._

/** Cuperflow L1 A slot v6 解码。
  *
  * slot = localCol[63:51] | segmentId[50:48] | rowLast[47] | chunkMode[46:45] |
  *        localRow[44:32] | fp32[31:0]。
  * localRow 是 row batch 内的 13-bit 行标；batch 与 localRow 共同恢复物理行。
  * rowLast 和 chunkMode 属于 L1 归约协议，不能再按旧 16-bit PE-local 行标解释。
  */
object SpmvCuperDecode {
  val slotAbi: String = "cuperflow-a-slot-v6"
  val columnBits: Int = 13
  val tagBits: Int = 3
  val rowBits: Int = 13
  val globalRowBits: Int = 32
  val lanesPerBeat: Int = 8
  val rowLastBit: Int = 47
  val chunkModeHighBit: Int = 46
  val chunkModeLowBit: Int = 45
  val localRowHighBit: Int = 44
  val localRowLowBit: Int = 32
  val invalidChunkMode: Int = 0x3
  /** X page 是 64 个连续 FP64，与 Cuper encoder 的列 slice 保持一致。 */
  val xPageElements: Int = 64

  final class DecodedSlot extends Bundle {
    val localColumn = UInt(columnBits.W)
    val segmentId = UInt(tagBits.W)
    val rowLast = Bool()
    val chunkMode = UInt(2.W)
    val chunkModeValid = Bool()
    val localRow = UInt(rowBits.W)
    val fp32 = UInt(32.W)
  }

  def decodeSlot(slot: UInt): DecodedSlot = {
    val decoded = Wire(new DecodedSlot)
    decoded.localColumn := slot(63, 51)
    decoded.segmentId := slot(50, 48)
    decoded.rowLast := slot(rowLastBit)
    decoded.chunkMode := slot(chunkModeHighBit, chunkModeLowBit)
    decoded.chunkModeValid := decoded.chunkMode =/= invalidChunkMode.U(2.W)
    decoded.localRow := slot(localRowHighBit, localRowLowBit)
    decoded.fp32 := slot(31, 0)
    decoded
  }
}

/** 旧输入乘法顶层专用的 slot v4 解码。
  *
  * 该解码器只服务旧 `SpmvInputMulTop`，不能作为 Cuperflow L1 的输入。保留它是为了
  * 让 v4 输入构造继续使用原 ABI；Cuperflow L1 v0 package 由顶层明确拒绝，避免把
  * rowLast/chunkMode 静默拼进旧 16-bit 行号。
  */
private[common] object SpmvCuperLegacyDecode {
  val columnBits: Int = 13
  val tagBits: Int = 3
  val rowBits: Int = 16
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
