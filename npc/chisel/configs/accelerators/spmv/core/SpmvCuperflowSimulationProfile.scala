package accelerators.spmv

import npc.ConfigCatalog

/** Cuperflow RTL/Verilator 构造的冻结 profile。 */
object SpmvCuperflowSimulationProfile {
  private def hex(value: Long): String =
    s"0x${java.lang.Long.toUnsignedString(value, 16)}"

  def values(
    entry: ConfigCatalog.Entry,
    construction: SpmvCuperflowSimulationConstruction,
    config: SpmvCuperflowConfig,
    report: SpmvInputReportConfig
  ): Seq[(String, String)] = {
    require(entry.scope == "spmv" && entry.board.isEmpty && entry.target == "SPMV")
    require(construction.capability == "run")
    require(construction.acceleratorHostConfig == SpmvAcceleratorHostConfig.CuperflowRtl)
    Seq(
      "PROFILE_FORMAT" -> "25",
      "CONFIG_SHORT_NAME" -> entry.shortName,
      "CONFIG_FQCN" -> entry.className,
      "SCOPE" -> entry.scope,
      "CAPABILITY" -> construction.capability,
      "HOST_ABI" -> "none",
      "ACCELERATOR_HOST_KIND" -> construction.acceleratorHostConfig.kind,
      "ACCELERATOR_HOST_ABI" -> construction.acceleratorHostConfig.abi,
      "PROTOCOL_ABI" -> "spmv-cuperflow-rtl-v1",
      "TARGET" -> entry.target,
      "SPMV_CUPERFLOW_HBM_PC_COUNT" -> config.hbmPcCount.toString,
      "SPMV_CUPERFLOW_HBM_BASE" -> hex(config.hbmBase),
      "SPMV_CUPERFLOW_HBM_BYTES" -> config.hbmBytes.toString,
      "SPMV_CUPERFLOW_X_REGION_BYTES" -> config.xRegionBytes.toString,
      "SPMV_CUPERFLOW_AXI_ADDR_WIDTH" -> config.axiAddrWidth.toString,
      "SPMV_CUPERFLOW_AXI_DATA_WIDTH" -> config.axiDataWidth.toString,
      "SPMV_CUPERFLOW_AXI_ID_WIDTH" -> config.axiIdWidth.toString,
      "SPMV_CUPERFLOW_MAX_OUTSTANDING_BURSTS" -> config.maxOutstandingBursts.toString,
      "SPMV_CUPERFLOW_X_WINDOW_SIZE" -> config.xWindowSize.toString,
      "SPMV_CUPERFLOW_X_REPLICA_COUNT" -> config.xReplicaCount.toString,
      "SPMV_CUPERFLOW_X_PINGPONG" -> (if (config.xPingPong) "1" else "0"),
      "SPMV_CUPERFLOW_X_BANK_COUNT" -> config.xBankCount.toString,
      "SPMV_CUPERFLOW_X_ELEMENT_WIDTH" -> config.xElementWidth.toString,
      "SPMV_CUPERFLOW_X_STORAGE" -> config.xMemoryPrimitive.profileName,
      "SPMV_CUPERFLOW_X_MEMORY_DATA_WIDTH" -> config.xMemoryDataWidth.toString,
      "SPMV_CUPERFLOW_X_DECODER_LANES" -> config.xWordsPerBeat.toString,
      "SPMV_FP64_MUL_INTERFACE" -> "arithmetic-req-resp-v1",
      "SPMV_FP64_MUL_PROVIDER" -> config.fp64MulProvider.profileName,
      "SPMV_FP64_MUL_LATENCY" -> config.fp64MultiplyLatency.toString,
      "SPMV_FP64_MUL_II" -> config.fp64MultiplyInitiationInterval.toString,
      "SPMV_FP64_MUL_RESPONSE_FIFO_DEPTH" -> config.fp64MultiplyResponseFifoDepth.toString,
      "SPMV_FP64_MUL_LANES" -> "8",
      "SPMV_FP64_MUL_CORE_COUNT" -> config.hbmPcCount.toString,
      "SPMV_FP64_MUL_TOTAL_LANES" -> (config.hbmPcCount * 8).toString,
      "SPMV_PERFORMANCE_HTML" -> (if (report.performanceHtml) "1" else "0"),
      "SPMV_PIPELINE_HTML" -> (if (report.pipelineHtml) "1" else "0")
    )
  }
}
