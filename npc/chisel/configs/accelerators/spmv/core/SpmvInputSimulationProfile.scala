package accelerators.spmv

import npc.ConfigCatalog

/** SPMV 正式输入和流水报告构造的 CPU-free profile。 */
object SpmvInputSimulationProfile {
  private def hex(value: Long): String =
    s"0x${java.lang.Long.toUnsignedString(value, 16)}"

  def values(
    entry: ConfigCatalog.Entry,
    construction: SpmvInputSimulationConstruction,
    input: SpmvInputConfig,
    report: SpmvInputReportConfig
  ): Seq[(String, String)] = {
    require(entry.scope == "spmv" && entry.board.isEmpty && entry.target == "SPMV")
    require(construction.capability == "run")
    require(construction.acceleratorHostConfig == SpmvAcceleratorHostConfig.InputReport)
    Seq(
      "PROFILE_FORMAT" -> "22",
      "CONFIG_SHORT_NAME" -> entry.shortName,
      "CONFIG_FQCN" -> entry.className,
      "SCOPE" -> entry.scope,
      "CAPABILITY" -> construction.capability,
      "HOST_ABI" -> "none",
      "ACCELERATOR_HOST_KIND" -> construction.acceleratorHostConfig.kind,
      "ACCELERATOR_HOST_ABI" -> "spmv-input-report-v3",
      "PROTOCOL_ABI" -> "spmv-input-full-bandwidth-v1",
      "TARGET" -> entry.target,
      "SPMV_INPUT_A_READER_COUNT" -> input.aReaderCount.toString,
      "SPMV_INPUT_X_READER_COUNT" -> input.xReaderCount.toString,
      "SPMV_INPUT_HBM_CHANNEL_COUNT" -> input.hbmChannelCount.toString,
      "SPMV_INPUT_HBM_BASE" -> hex(input.hbmBase),
      "SPMV_INPUT_HBM_BYTES" -> input.hbmBytes.toString,
      "SPMV_INPUT_HBM_CHANNEL_ALIGNMENT_BYTES" -> input.channelBaseAlignmentBytes.toString,
      "SPMV_INPUT_AXI_ADDR_WIDTH" -> input.axiAddrWidth.toString,
      "SPMV_INPUT_AXI_DATA_WIDTH" -> input.axiDataWidth.toString,
      "SPMV_INPUT_AXI_ID_WIDTH" -> input.axiIdWidth.toString,
      "SPMV_INPUT_MAX_OUTSTANDING_BURSTS" -> input.maxOutstandingBursts.toString,
      "SPMV_INPUT_CONSUMER_COUNT" -> input.aReaderCount.toString,
      "SPMV_INPUT_X_BROADCAST" -> "1",
      "SPMV_PERFORMANCE_HTML" -> (if (report.performanceHtml) "1" else "0"),
      "SPMV_PIPELINE_HTML" -> (if (report.pipelineHtml) "1" else "0")
    )
  }
}
