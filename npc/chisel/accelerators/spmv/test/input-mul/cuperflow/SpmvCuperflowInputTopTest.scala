package accelerators.spmv.inputmul.cuperflow

import _root_.circt.stage.ChiselStage
import accelerators.spmv.SpmvCuperflowConfig
import org.scalatest.flatspec.AnyFlatSpec

class SpmvCuperflowInputTopTest extends AnyFlatSpec {
  private val config = SpmvCuperflowConfig(
    hbmPcCount = 8,
    hbmBase = 0,
    hbmBytes = 8192,
    xRegionBytes = 4096
  )

  "Cuperflow input top" should "展开每 PC 单 HBM 端口、X marker decoder 和 strict preload barrier" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new SpmvCuperflowInputTop(config))

    assert(chirrtl.contains("module SpmvCuperflowInputTop"))
    assert(chirrtl.contains("module SpmvCuperflowLane"))
    assert(chirrtl.contains("module SpmvCuperflowXDecoder8"))
    assert(chirrtl.contains("module SpmvCuperflowLocalX"))
    assert(chirrtl.contains("globalXReady"))
    assert(chirrtl.contains("aReadStarted"))
    assert(chirrtl.contains("roundDone"))
    assert(chirrtl.contains("hbm :"))
    assert(chirrtl.contains("SpmvMulEngine"))
    assert(!chirrtl.contains("SpmvCtrlInput"))
  }

  it should "固定单 PC 的 X/A 分区与灵活 X 的最大 token 容量" in {
    assert(config.xRegionBytes == 4096)
    assert(config.aRegionBase == 4096)
    assert(config.aRegionBytes == 4096)
    assert(config.xMaxEncodedWords == 16384)
    assert(config.mulConfig.aReaderCount == config.hbmPcCount)
  }
}
