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

  "Cuperflow input top" should "接受 L1 v6 slot，并将 rowLast/chunkMode 交给乘法响应路径" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new SpmvCuperflowInputTop(config))

    assert(config.slotAbi == "cuperflow-a-slot-v6")
    assert(config.mulConfig.cuperSlotAbi == "cuperflow-a-slot-v6")
    assert(config.mulConfig.cuperSlotRowBits == 13)
    assert(chirrtl.contains("rowLast"))
    assert(chirrtl.contains("chunkMode"))
    assert(chirrtl.contains("SpmvCuperflowProductBeatJoin"))
    assert(chirrtl.contains("beatSeq"))
    assert(chirrtl.contains("laneValid"))
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

  it should "在 xPingPong 下保持同一 L1 v6 slot ABI" in {
    val pingPong = config.copy(xPingPong = true)
    assert(pingPong.xBankCount == 2)
    assert(pingPong.mulConfig.cuperSlotAbi == "cuperflow-a-slot-v6")
    assert(ChiselStage.emitCHIRRTL(new SpmvCuperflowInputTop(pingPong)).contains("SpmvMulEngine"))
  }

  it should "用同一 ProductBeat ABI 展开全部 1..16 PC 几何" in {
    for (pcCount <- 1 to 16) {
      val parameterized = config.copy(hbmPcCount = pcCount)
      val chirrtl = ChiselStage.emitCHIRRTL(new SpmvCuperflowInputTop(parameterized))
      assert(parameterized.mulConfig.aReaderCount == pcCount)
      assert(chirrtl.contains("product"))
      assert(chirrtl.contains("SpmvCuperflowProductBeatJoin"))
    }
    assertThrows[IllegalArgumentException](config.copy(hbmPcCount = 0))
    assertThrows[IllegalArgumentException](config.copy(hbmPcCount = 17))
  }
}
