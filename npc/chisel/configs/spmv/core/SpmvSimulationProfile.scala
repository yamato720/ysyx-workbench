package npc

/** 独立 SPMV Verilator 构造的 CPU-free profile。 */
object SpmvSimulationProfile {
  private def hex(value: Long): String =
    s"0x${java.lang.Long.toUnsignedString(value, 16)}"

  def values(
    entry: ConfigCatalog.Entry,
    construction: SpmvSimulationConstruction,
    config: SpmvCsr5MulConfig
  ): Seq[(String, String)] = {
    require(entry.scope == "spmv" && entry.board.isEmpty && entry.target == "SPMV")
    require(construction.capability == "run")
    Seq(
      "PROFILE_FORMAT" -> "22",
      "CONFIG_SHORT_NAME" -> entry.shortName,
      "CONFIG_FQCN" -> entry.className,
      "SCOPE" -> entry.scope,
      "CAPABILITY" -> construction.capability,
      "HOST_ABI" -> "none",
      "ACCELERATOR_HOST_KIND" -> construction.acceleratorHostConfig.kind,
      "ACCELERATOR_HOST_ABI" -> construction.acceleratorHostConfig.abi,
      "PROTOCOL_ABI" -> "spmv-one-hbm-csr5-mul-v3",
      "TARGET" -> entry.target,
      "SPMV_PERFORMANCE_HTML" -> bit(construction.performanceHtml),
      "SPMV_PIPELINE_HTML" -> bit(construction.pipelineHtml),
      "SPMV_PERFORMANCE_FIRST_SUBCONFIG" -> construction.spmvPerformanceMonitorConfig.firstSubConfig,
      "SPMV_HBM_PC_COUNT" -> "1",
      "SPMV_HBM_BASE" -> hex(config.hbmBase),
      "SPMV_HBM_BYTES" -> config.hbmBytes.toString,
      "SPMV_AXI_ADDR_WIDTH" -> config.axiAddrWidth.toString,
      "SPMV_AXI_DATA_WIDTH" -> config.axiDataWidth.toString,
      "SPMV_AXI_ID_WIDTH" -> config.axiIdWidth.toString,
      "SPMV_A_WIDTH" -> config.valueWidth.toString,
      "SPMV_X_WIDTH" -> config.valueWidth.toString,
      "SPMV_PRODUCT_WIDTH" -> config.valueWidth.toString,
      "SPMV_COORD_WIDTH" -> config.coordWidth.toString,
      "SPMV_OMEGA" -> config.omega.toString,
      "SPMV_SIGMA" -> config.sigma.toString,
      "SPMV_TILE_NNZ" -> config.tileNnz.toString,
      "SPMV_RECORDS_PER_BEAT" -> config.recordsPerBeat.toString,
      "SPMV_FULL_TILE_BEATS" -> config.fullTileBeats.toString,
      "SPMV_MAX_BLOCK_ROWS" -> config.maxBlockRows.toString,
      "SPMV_MAX_BLOCK_COLS" -> config.maxBlockCols.toString,
      "SPMV_X_MODE" -> config.xMode.name,
      "SPMV_X_REPLICAS" -> config.xReplicas.toString,
      "SPMV_X_READ_LANES" -> config.xReadLanes.toString,
      "SPMV_MULTIPLIER_COUNT" -> config.multiplierCount.toString,
      "SPMV_MULTIPLIER_LATENCY" -> config.multiplierLatency.toString,
      "SPMV_MULTIPLIER_II" -> config.multiplierInitiationInterval.toString,
      "SPMV_BURST_BEATS" -> config.maxBurstBeats.toString,
      "SPMV_OUTSTANDING_BURSTS" -> config.outstandingBursts.toString,
      "SPMV_HBM_FIRST_BEAT_LATENCY_MIN" -> config.hbmFirstBeatLatencyMin.toString,
      "SPMV_HBM_FIRST_BEAT_LATENCY_MAX" -> config.hbmFirstBeatLatencyMax.toString,
      "SPMV_HBM_TIMING_SEED" -> hex(config.hbmTimingSeed),
      "SPMV_INPUT_FIFO_DEPTH" -> config.inputFifoDepth.toString,
      "SPMV_PRODUCT_FIFO_DEPTH" -> config.productFifoDepth.toString,
      "SPMV_UNIT_ID" -> config.unitId.toString
    )
  }

  private def bit(value: Boolean): String = if (value) "1" else "0"
}
