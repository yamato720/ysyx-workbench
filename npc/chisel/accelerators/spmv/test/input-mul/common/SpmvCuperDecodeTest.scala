package accelerators.spmv.inputmul.common

import org.scalatest.flatspec.AnyFlatSpec

class SpmvCuperDecodeTest extends AnyFlatSpec {
  "SpmvCuperDecode" should "冻结 L1 v0 的 rowLast、chunkMode 和 batch-local 行位域" in {
    assert(SpmvCuperDecode.slotAbi == "cuperflow-a-slot-v6")
    assert(SpmvCuperDecode.columnBits == 13)
    assert(SpmvCuperDecode.tagBits == 3)
    assert(SpmvCuperDecode.rowBits == 13)
    assert(SpmvCuperDecode.globalRowBits == 32)
    assert(SpmvCuperDecode.lanesPerBeat == 8)
    assert(SpmvCuperDecode.rowLastBit == 47)
    assert(SpmvCuperDecode.chunkModeHighBit == 46)
    assert(SpmvCuperDecode.chunkModeLowBit == 45)
    assert(SpmvCuperDecode.localRowHighBit == 44)
    assert(SpmvCuperDecode.localRowLowBit == 32)
    assert(SpmvCuperDecode.invalidChunkMode == 3)
  }
}
