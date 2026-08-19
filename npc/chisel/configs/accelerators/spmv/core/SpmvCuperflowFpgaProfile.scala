package accelerators.spmv

import npc.{AcceleratorHostConstruction, ConfigCatalog, FpgaToolchainConstruction}

/** 当前 Cuperflow FPGA 资产的独立 profile；不复用旧 19 路输入 ABI。 */
object SpmvCuperflowFpgaProfile {
  private def safe(key: String, value: String): (String, String) = {
    require(key.matches("[A-Z][A-Z0-9_]*"), s"非法 profile 字段名：$key")
    require(!value.exists(character => character == '\n' || character == '\r' || character == '\u0000'),
      s"profile 字段 $key 含非法字符")
    key -> value
  }

  def values(
    entry: ConfigCatalog.Entry,
    construction: FpgaToolchainConstruction with AcceleratorHostConstruction,
    config: SpmvCuperflowConfig,
    extra: Seq[(String, String)]
  ): Seq[(String, String)] = {
    require(entry.scope == "fpga" && entry.board.contains("u55c") && entry.target == "SPMV",
      s"Cuperflow FPGA profile 只支持 U55C SPMV terminal：${entry.className}")
    require(Set("synthesize-only", "bitstream-only").contains(construction.capability),
      s"Cuperflow FPGA terminal 必须是 synthesize-only 或 bitstream-only：${entry.className}")
    require(construction.acceleratorHostConfig == SpmvAcceleratorHostConfig.CuperflowFpga)
    val base = Seq(
      "PROFILE_FORMAT" -> "25",
      "CONFIG_SHORT_NAME" -> entry.shortName,
      "CONFIG_FQCN" -> entry.className,
      "SCOPE" -> entry.scope,
      "CAPABILITY" -> construction.capability,
      "HOST_ABI" -> "none",
      "ACCELERATOR_HOST_KIND" -> construction.acceleratorHostConfig.kind,
      "ACCELERATOR_HOST_ABI" -> construction.acceleratorHostConfig.abi,
      "PROTOCOL_ABI" -> "spmv-cuperflow-u55c-v1",
      "TARGET" -> entry.target,
      "SPMV_CUPERFLOW_XRT_KERNEL" -> "SpmvCuperflowKernel",
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
      "SPMV_FP64_MUL_TOTAL_LANES" -> (config.hbmPcCount * 8).toString
    )
    val all = (base ++ extra).map { case (key, value) => safe(key, value) }
    val duplicates = all.groupBy(_._1).collect { case (key, entries) if entries.size > 1 => key }
    require(duplicates.isEmpty, s"Cuperflow FPGA profile 含重复字段：${duplicates.toSeq.sorted.mkString(", ")}")
    all
  }

  private def hex(value: Long): String =
    s"0x${java.lang.Long.toUnsignedString(value, 16)}"
}
