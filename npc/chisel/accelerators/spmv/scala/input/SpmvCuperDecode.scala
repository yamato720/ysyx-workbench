package accelerators.spmv.input

import chisel3._

/** Cuper A slot 与行号还原。
  *
  * slot = localCol[63:50] | encodedRow[49:32] | fp32[31:0]。
  * encodedRow[17] 为 padding；有效行号由 PE = channel*8+lane 逆映射回原始 CSR 行。
  */
object SpmvCuperDecode {
  val columnBits: Int = 14
  val rowBits: Int = 18
  val paddingBit: Int = 17
  val checkerCount: Int = 8
  val lanesPerBeat: Int = 8

  final class DecodedSlot extends Bundle {
    val padding = Bool()
    val localColumn = UInt(columnBits.W)
    val encodedRow = UInt(rowBits.W)
    val fp32 = UInt(32.W)
  }

  def originalRow(encodedRow: Int, pe: Int, hbmChannelCount: Int): Int = {
    require(hbmChannelCount > 0 && hbmChannelCount % checkerCount == 0)
    val accumulatorGroupSize = hbmChannelCount / checkerCount
    val peInAccumulator = pe % lanesPerBeat
    val checkerAndOffset = pe / lanesPerBeat
    val checker = checkerAndOffset / accumulatorGroupSize
    val accumulatorOffset = checkerAndOffset % accumulatorGroupSize
    val packetRemainder = checker + checkerCount * accumulatorOffset +
      hbmChannelCount * peInAccumulator
    val rowGroup = encodedRow / 2
    val totalPes = hbmChannelCount * lanesPerBeat
    (rowGroup * totalPes + packetRemainder) * 2 + (encodedRow % 2)
  }

  def decodeSlot(slot: UInt): DecodedSlot = {
    val decoded = Wire(new DecodedSlot)
    decoded.localColumn := slot(63, 50)
    decoded.encodedRow := slot(49, 32)
    decoded.fp32 := slot(31, 0)
    decoded.padding := slot(49)
    decoded
  }

  def decodeOriginalRow(encodedRow: UInt, pe: UInt, hbmChannelCount: Int): UInt = {
    require(hbmChannelCount > 0 && hbmChannelCount % checkerCount == 0)
    val accumulatorGroupSize = hbmChannelCount / checkerCount
    val peInAccumulator = pe(2, 0)
    val checkerAndOffset = pe >> 3
    val checker = checkerAndOffset / accumulatorGroupSize.U
    val accumulatorOffset = checkerAndOffset % accumulatorGroupSize.U
    val packetRemainder = checker +&
      (checkerCount.U * accumulatorOffset) +&
      (hbmChannelCount.U * peInAccumulator)
    val rowGroup = encodedRow(paddingBit - 1, 1)
    val totalPes = (hbmChannelCount * lanesPerBeat).U
    ((rowGroup * totalPes) +& packetRemainder) * 2.U + encodedRow(0)
  }
}
