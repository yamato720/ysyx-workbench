package accelerators.spmv.config

import accelerators.spmv._
import npc.ConfigCatalog
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec

class SpmvInputConfigTest extends AnyFlatSpec {
  "SpmvInputConfig" should "describe the 16-channel Cuper input layout" in {
    val config = SpmvInputConfig.Cuper16Hbm
    assert(config.aReaderCount == 16)
    assert(config.xReaderCount == 2)
    assert(config.ctrlReaderCount == 1)
    assert(config.hbmChannelCount == 16)
    assert(config.hbmBase == 0x80000000L)
    assert(config.hbmBytes == 128L * 1024L * 1024L)
    assert(config.channelBaseAlignmentBytes == 4096)
    assert(config.axiAddrWidth == 64)
    assert(config.axiDataWidth == 512)
    assert(config.axiIdWidth == 4)
    assert(config.maxOutstandingBursts == 2)
    assert(config.totalHbmPortCount == 19)
    assert(config.xWindowSize == 8192)
    assert(config.xReplicaCount == 4)
    assert(config.xElementWidth == 64)
    assert(config.xElementsPerBeat == 8)
    assert(config.xWriteLanes == 8)
    assert(config.xOverlapLanes == 4)
    assert(config.xBankCount == 8)
    assert(config.xBankDepth == 1024)
    assert(config.xPortSchedule == SpmvXPortSchedule.Preload)
    assert(SpmvInputConfig.Cuper16HbmPingPong.xPortSchedule == SpmvXPortSchedule.PingPong)
    assert(config.fp64MultiplyLaneCount == 8)
    assert(config.fp64MultiplyCoreCount == 16)
    assert(config.fp64MultiplyTotalLaneCount == 128)
    assert(config.cuperSlotColumnBits == 13)
    assert(config.cuperSlotTagBits == 3)
    assert(config.cuperSlotRowBits == 16)
  }

  it should "reject an A-reader count that does not cover every HBM channel" in {
    assertThrows[IllegalArgumentException](SpmvInputConfig.Cuper16Hbm.copy(aReaderCount = 8))
  }

  it should "reject a non-power-of-two X window or a non-FP64 element" in {
    assertThrows[IllegalArgumentException](SpmvInputConfig.Cuper16Hbm.copy(xWindowSize = 3000))
    assertThrows[IllegalArgumentException](SpmvInputConfig.Cuper16Hbm.copy(xElementWidth = 32))
    assertThrows[IllegalArgumentException](SpmvInputConfig.Cuper16Hbm.copy(xReplicaCount = 0))
    assertThrows[IllegalArgumentException](SpmvInputConfig.Cuper16Hbm.copy(cuperSlotRowBits = 17))
  }

  "SpmvInputReportConfig" should "要求流水页依赖性能主页" in {
    assert(SpmvInputReportConfig.PerformancePipeline.performanceHtml)
    assert(SpmvInputReportConfig.PerformancePipeline.pipelineHtml)
    assertThrows[IllegalArgumentException](SpmvInputReportConfig(
      performanceHtml = false,
      pipelineHtml = true
    ))
  }

  "SpmvInputSimulationProfile" should "发布正式输入、广播和流水报告合同" in {
    val entry = ConfigCatalog.resolve("SpmvInputSimulationConfig", Set("spmv"))
    val construction = new SpmvInputSimulationConfig
    implicit val parameters: Parameters = construction
    val input = parameters(SpmvInputConfigKey).get
    val report = construction.spmvInputReportConfig
    val values = SpmvInputSimulationProfile.values(entry, construction, input, report).toMap

    assert(values("ACCELERATOR_HOST_ABI") == "spmv-input-report-v13")
    assert(values("PROTOCOL_ABI") == "spmv-input-windowed-v12")
    assert(values("SPMV_INPUT_A_READER_COUNT") == "16")
    assert(values("SPMV_INPUT_X_READER_COUNT") == "2")
    assert(values("SPMV_INPUT_CTRL_READER_COUNT") == "1")
    assert(values("SPMV_INPUT_HBM_CHANNEL_COUNT") == "16")
    assert(values("SPMV_INPUT_HBM_BASE") == "0x80000000")
    assert(values("SPMV_INPUT_HBM_BYTES") == "134217728")
    assert(values("SPMV_INPUT_HBM_CHANNEL_ALIGNMENT_BYTES") == "4096")
    assert(values("SPMV_INPUT_AXI_ADDR_WIDTH") == "64")
    assert(values("SPMV_INPUT_AXI_DATA_WIDTH") == "512")
    assert(values("SPMV_INPUT_AXI_ID_WIDTH") == "4")
    assert(values("SPMV_INPUT_MAX_OUTSTANDING_BURSTS") == "2")
    assert(values("SPMV_INPUT_CONSUMER_COUNT") == "16")
    assert(values("SPMV_INPUT_X_BROADCAST") == "1")
    assert(values("SPMV_INPUT_CTRL_BROADCAST") == "1")
    assert(values("SPMV_INPUT_X_WINDOW_SIZE") == "8192")
    assert(values("SPMV_INPUT_X_REPLICA_COUNT") == "4")
    assert(values("SPMV_INPUT_X_BANK_COUNT") == "8")
    assert(values("SPMV_INPUT_X_ELEMENT_WIDTH") == "64")
    assert(values("SPMV_INPUT_X_PORT_SCHEDULE") == "preload")
    assert(values("SPMV_INPUT_X_WRITE_LANES") == "8")
    assert(values("SPMV_INPUT_X_OVERLAP_LANES") == "4")
    assert(values("SPMV_CUPER_SLOT_ABI") == "cuper-a-slot-v4")
    assert(values("SPMV_CUPER_SLOT_COLUMN_BITS") == "13")
    assert(values("SPMV_CUPER_SLOT_TAG_BITS") == "3")
    assert(values("SPMV_CUPER_SLOT_ROW_BITS") == "16")
    assert(values("SPMV_FP64_MUL_INTERFACE") == "arithmetic-req-resp-v1")
    assert(values("SPMV_FP64_MUL_LATENCY") == "4")
    assert(values("SPMV_FP64_MUL_II") == "1")
    assert(values("SPMV_FP64_MUL_RESPONSE_FIFO_DEPTH") == "4")
    assert(values("SPMV_FP64_MUL_LANES") == "8")
    assert(values("SPMV_FP64_MUL_CORE_COUNT") == "16")
    assert(values("SPMV_FP64_MUL_TOTAL_LANES") == "128")
    assert(values("SPMV_PERFORMANCE_HTML") == "1")
    assert(values("SPMV_PIPELINE_HTML") == "1")
    val performanceOnly = SpmvInputSimulationProfile.values(
      entry,
      construction,
      input,
      SpmvInputReportConfig.Performance
    ).toMap
    assert(performanceOnly("SPMV_PERFORMANCE_HTML") == "1")
    assert(performanceOnly("SPMV_PIPELINE_HTML") == "0")
    assert(!values.keys.exists(_.contains("CSR5")))
    assert(!values.contains("SPMV_X_MODE"))
  }
}
