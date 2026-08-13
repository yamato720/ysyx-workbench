package accelerators.spmv

import _root_.circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec

/** 检查输入层顶层展开多 HBM 输入封装、消费端和单路 X 广播状态。 */
class SpmvInputTopTest extends AnyFlatSpec {
  "SpmvInputTop" should "通过 A/X 输入封装展开当前 16+1 路 HBM" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new SpmvInputTop(SpmvInputConfig.Cuper16Hbm))

    assert(chirrtl.contains("module SpmvInputTop"))
    assert(chirrtl.contains("module SpmvAInput"))
    assert(chirrtl.contains("module SpmvXInput"))
    assert(chirrtl.contains("aInput"))
    assert(chirrtl.contains("xInput"))
    assert(chirrtl.contains("consumers_0"))
    assert(chirrtl.contains("consumers_15"))
    assert(chirrtl.contains("aHbm :"))
    assert(chirrtl.contains("xHbm :"))
    assert(chirrtl.contains("io.aHbm[0].ar"))
    assert(chirrtl.contains("io.xHbm[0].ar"))
    assert(chirrtl.contains("consumerABeats"))
    assert(chirrtl.contains("consumerXChecksum"))
  }

}
