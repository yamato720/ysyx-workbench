package accelerators.spmv.input

import org.scalatest.flatspec.AnyFlatSpec

class SpmvCuperDecodeTest extends AnyFlatSpec {
  "SpmvCuperDecode" should "按默认 16-HBM 映射还原 Cuper 原始行号" in {
    val hbmChannelCount = 16
    val totalPes = hbmChannelCount * SpmvCuperDecode.lanesPerBeat
    for (row <- 0 until 4096) {
      val packet = row / 2
      val pe = ((packet % 8) * 2 + (packet / 8) % 2) * 8 + (packet / 16) % 8
      val encodedRow = (row / (2 * totalPes)) * 2 + row % 2
      assert(
        SpmvCuperDecode.originalRow(encodedRow, pe, hbmChannelCount) == row,
        s"row=$row pe=$pe encodedRow=$encodedRow"
      )
    }
  }
}
