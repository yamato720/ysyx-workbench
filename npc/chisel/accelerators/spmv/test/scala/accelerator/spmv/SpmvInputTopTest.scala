package accelerator.spmv

import _root_.circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec

/** 检查输入层顶层确实展开了配置要求的 reader 实例和独立 HBM 端口。 */
class SpmvInputTopTest extends AnyFlatSpec {
  "SpmvInputTop" should "展开 16 个 A reader 和 1 个 X reader" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new SpmvInputTop(npc.SpmvInputConfig.Cuper16Hbm))

    assert(chirrtl.contains("module SpmvInputTop"))
    assert(chirrtl.contains("aReaders_0"))
    assert(chirrtl.contains("aReaders_15"))
    assert(chirrtl.contains("xReaders_0"))
    assert(chirrtl.contains("aHbm :"))
    assert(chirrtl.contains("xHbm :"))
    assert(chirrtl.contains("io.aHbm[0].ar"))
    assert(chirrtl.contains("io.xHbm[0].ar"))
  }
}
