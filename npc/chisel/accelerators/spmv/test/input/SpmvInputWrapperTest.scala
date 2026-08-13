package accelerators.spmv.input

import _root_.circt.stage.ChiselStage
import accelerators.spmv.SpmvInputConfig
import org.scalatest.flatspec.AnyFlatSpec

/** 检查 A/X 输入封装可以独立参数化其 HBM reader 数量。 */
class SpmvInputWrapperTest extends AnyFlatSpec {
  "SpmvAInput" should "允许一个封装展开多个独立 HBM reader" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new SpmvAInput(SpmvInputConfig.Cuper16Hbm, 3))

    assert(chirrtl.contains("module SpmvAInput"))
    assert(chirrtl.contains("ports_0"))
    assert(chirrtl.contains("ports_1"))
    assert(chirrtl.contains("ports_2"))
    assert(chirrtl.contains("io.axi[2].ar"))
    assert(chirrtl.contains("io.output[2]"))
  }

  "SpmvXInput" should "允许一个封装展开多个独立 HBM reader" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new SpmvXInput(SpmvInputConfig.Cuper16Hbm, 2))

    assert(chirrtl.contains("module SpmvXInput"))
    assert(chirrtl.contains("ports_0"))
    assert(chirrtl.contains("ports_1"))
    assert(chirrtl.contains("io.axi[1].ar"))
    assert(chirrtl.contains("io.output[1]"))
  }
}
