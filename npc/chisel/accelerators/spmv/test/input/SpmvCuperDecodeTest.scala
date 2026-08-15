package accelerators.spmv.input

import org.scalatest.flatspec.AnyFlatSpec

class SpmvCuperDecodeTest extends AnyFlatSpec {
  "SpmvCuperDecode" should "冻结直接行标和不参与乘法控制的 tag 位域" in {
    assert(SpmvCuperDecode.columnBits == 13)
    assert(SpmvCuperDecode.tagBits == 3)
    assert(SpmvCuperDecode.rowBits == 16)
    assert(SpmvCuperDecode.lanesPerBeat == 8)
  }
}
