package accelerators.spmv.inputmul.pingpong

import _root_.circt.stage.ChiselStage
import accelerators.spmv.SpmvInputConfig
import org.scalatest.flatspec.AnyFlatSpec

class SpmvAxPingPongInputMulTopTest extends AnyFlatSpec {
  "SpmvAxPingPongInputMulTop" should "生成兼容既有 wrapper 的 PingPong 输入乘法顶层" in {
    val chirrtl = ChiselStage.emitCHIRRTL(
      new SpmvAxPingPongInputMulTop(SpmvInputConfig.Cuper16HbmPingPong))

    assert(chirrtl.contains("module SpmvInputTop"))
    assert(chirrtl.contains("portSafeOverlap"))
    assert(chirrtl.contains("halfPending"))
  }
}
