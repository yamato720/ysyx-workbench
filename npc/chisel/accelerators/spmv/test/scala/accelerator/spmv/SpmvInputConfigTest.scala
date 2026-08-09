package accelerator.spmv

import npc._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec

class SpmvInputConfigTest extends AnyFlatSpec {
  "SpmvInputConfig" should "describe the 16-channel Cuper input layout" in {
    val config = SpmvInputConfig.Cuper16Hbm
    assert(config.aReaderCount == 16)
    assert(config.xReaderCount == 1)
    assert(config.hbmChannelCount == 16)
    assert(config.hbmBase == 0x80000000L)
    assert(config.hbmBytes == 128L * 1024L * 1024L)
    assert(config.channelBaseAlignmentBytes == 4096)
    assert(config.axiAddrWidth == 64)
    assert(config.axiDataWidth == 512)
    assert(config.axiIdWidth == 4)
  }

  it should "reject an A-reader count that does not cover every HBM channel" in {
    assertThrows[IllegalArgumentException](SpmvInputConfig.Cuper16Hbm.copy(aReaderCount = 8))
  }

  "SpmvInputSimulationProfile" should "publish only the input smoke layout" in {
    val entry = ConfigCatalog.resolve("SpmvInputSimulationConfig", Set("spmv"))
    val construction = new npc.spmv.SpmvInputSimulationConfig
    implicit val parameters: Parameters = construction
    val input = parameters(SpmvInputConfigKey).get
    val values = SpmvInputSimulationProfile.values(entry, construction, input).toMap

    assert(values("ACCELERATOR_HOST_ABI") == "spmv-input-smoke-v1")
    assert(values("PROTOCOL_ABI") == "spmv-input-v1")
    assert(values("SPMV_INPUT_A_READER_COUNT") == "16")
    assert(values("SPMV_INPUT_X_READER_COUNT") == "1")
    assert(values("SPMV_INPUT_HBM_CHANNEL_COUNT") == "16")
    assert(values("SPMV_INPUT_HBM_BASE") == "0x80000000")
    assert(values("SPMV_INPUT_HBM_BYTES") == "134217728")
    assert(values("SPMV_INPUT_HBM_CHANNEL_ALIGNMENT_BYTES") == "4096")
    assert(values("SPMV_INPUT_AXI_ADDR_WIDTH") == "64")
    assert(values("SPMV_INPUT_AXI_DATA_WIDTH") == "512")
    assert(values("SPMV_INPUT_AXI_ID_WIDTH") == "4")
    assert(!values.keys.exists(_.contains("CSR5")))
    assert(!values.contains("SPMV_X_MODE"))
    assert(!values.contains("SPMV_PERFORMANCE_HTML"))
  }
}
