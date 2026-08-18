package accelerators.spmv.inputmul.common

import org.scalatest.flatspec.AnyFlatSpec

class SpmvCuperDecodeTest extends AnyFlatSpec {
  "SpmvCuperDecode" should "冻结 PE-local 行标和不参与乘法控制的 tag 位域" in {
    assert(SpmvCuperDecode.columnBits == 13)
    assert(SpmvCuperDecode.tagBits == 3)
    assert(SpmvCuperDecode.rowBits == 16)
    assert(SpmvCuperDecode.globalRowBits == 32)
    assert(SpmvCuperDecode.lanesPerBeat == 8)
  }
}
