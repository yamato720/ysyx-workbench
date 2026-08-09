package accelerator.spmv.io

import _root_.circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec

/** 只检查 reader 壳是否各自展开公共 HBM 读端口，不验证尚未实现的事务行为。 */
class SpmvReaderIoTest extends AnyFlatSpec {
  private def assertHbmReadPort(chirrtl: String, moduleName: String): Unit = {
    assert(chirrtl.contains(s"module $moduleName"))
    assert(chirrtl.contains("io.hbm.ar.bits.len"))
    assert(chirrtl.contains("flip r :"))
    assert(chirrtl.contains("data : UInt<512>"))
  }

  "A reader" should "展开公共 HBM 读接口" in {
    assertHbmReadPort(ChiselStage.emitCHIRRTL(new SpmvAReader()), "SpmvAReader")
  }

  "X reader" should "展开独立的公共 HBM 读接口" in {
    assertHbmReadPort(ChiselStage.emitCHIRRTL(new SpmvXReader()), "SpmvXReader")
  }
}
