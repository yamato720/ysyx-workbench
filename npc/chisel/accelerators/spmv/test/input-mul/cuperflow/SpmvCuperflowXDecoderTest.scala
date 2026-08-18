package accelerators.spmv.inputmul.cuperflow

import _root_.circt.stage.ChiselStage
import accelerators.spmv.SpmvCuperflowConfig
import org.scalatest.flatspec.AnyFlatSpec

class SpmvCuperflowXDecoderTest extends AnyFlatSpec {
  private val config = SpmvCuperflowConfig.Simulation

  "Cuperflow X address marker" should "与 host 编码器冻结同一 quiet-NaN 前缀和 13-bit 地址" in {
    val marker = SpmvCuperflowXAddressMarker.marker(6)

    assert(marker == BigInt("7ff9000034b4a006", 16))
    assert(SpmvCuperflowXAddressMarker.isMarkerBits(marker))
    assert(!SpmvCuperflowXAddressMarker.isMarkerBits(BigInt("3ff0000000000000", 16)))
    assert(!SpmvCuperflowXAddressMarker.isMarkerBits(marker | BigInt(0x4000)))
    assert(SpmvCuperflowMapMarker.marker(true) != marker)
  }

  it should "展开 8 word/cycle 的 beat 输入、地址 prefix scan 和 packed 写请求" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new SpmvCuperflowXDecoder8(config))

    assert(chirrtl.contains("module SpmvCuperflowXDecoder8"))
    assert(chirrtl.contains("input :"))
    assert(chirrtl.contains("write :"))
    assert(chirrtl.contains("validWords"))
    assert(chirrtl.contains("nextAddress"))
    assert(chirrtl.contains("rangeElements"))
    assert(chirrtl.contains("error"))
  }
}
