package accelerators.spmv.input

import _root_.circt.stage.ChiselStage
import accelerators.spmv.SpmvInputConfig
import org.scalatest.flatspec.AnyFlatSpec

class SpmvCuperMapTest extends AnyFlatSpec {
  "SpmvCuperMap" should "展开 Ctrl map 的相邻 batch pointer 计数" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new SpmvCuperMap(SpmvInputConfig.Cuper16Hbm))

    assert(chirrtl.contains("module SpmvCuperMap"))
    assert(chirrtl.contains("batchIndex"))
    assert(chirrtl.contains("batchBeatCount"))
    assert(chirrtl.contains("batchActive"))
    assert(chirrtl.contains("declaredPointerCount"))
    assert(chirrtl.contains("pointers"))
  }
}
