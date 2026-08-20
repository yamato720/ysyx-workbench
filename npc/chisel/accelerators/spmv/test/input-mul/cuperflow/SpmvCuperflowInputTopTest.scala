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

  "Cuperflow input top" should "展开每 PC 单 HBM 端口、map 控制和连续 X 装填器" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new SpmvCuperflowInputTop(config))

    assert(chirrtl.contains("module SpmvCuperflowInputTop"))
    assert(chirrtl.contains("module SpmvCuperflowLane"))
    assert(chirrtl.contains("module SpmvCuperflowLocalX"))
    assert(chirrtl.contains("module SpmvCuperflowIssuedXWriteStage"))
    assert(!chirrtl.contains("module SpmvCuperflowXDecoder8"))
    assert(chirrtl.contains("start"))
    assert(chirrtl.contains("done"))
    assert(chirrtl.contains("hbm :"))
    assert(chirrtl.contains("SpmvMulEngine"))
    assert(!chirrtl.contains("SpmvCtrlInput"))
    assert(!chirrtl.contains("globalXReady"))
    assert(!chirrtl.contains("roundDone"))
  }

  it should "固定单 PC 的 X/A 分区与连续 X 的最大 payload 容量" in {
    assert(config.xRegionBytes == 4096)
    assert(config.aRegionBase == 4096)
    assert(config.aRegionBytes == 4096)
    assert(config.xMaxEncodedWords == 8192)
    assert(config.mulConfig.aReaderCount == config.hbmPcCount)
    assert(!config.xPingPong)
    assert(config.xBankCount == 1)
  }

  it should "在 xPingPong 下仍展开独立 PC 且实例化双窗口 local-X" in {
    val pingPong = config.copy(xPingPong = true)
    val chirrtl = ChiselStage.emitCHIRRTL(new SpmvCuperflowInputTop(pingPong))
    assert(pingPong.xBankCount == 2)
    assert(chirrtl.contains("module SpmvCuperflowLocalX"))
    assert(chirrtl.contains("issuedSequentialWrite_b0_r0"))
    assert(chirrtl.contains("activate"))
  }
}
