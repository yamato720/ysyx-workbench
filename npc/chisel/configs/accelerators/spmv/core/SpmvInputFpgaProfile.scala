package accelerators.spmv

import npc.ConfigCatalog

/** U55C SPMV 输入/乘法 runtime 的 FPGA-only profile。 */
object SpmvInputFpgaProfile {
  private def hex(value: Long): String =
    s"0x${java.lang.Long.toUnsignedString(value, 16)}"

  private def safe(key: String, value: String): (String, String) = {
    require(key.matches("[A-Z][A-Z0-9_]*"), s"非法 profile 字段名：$key")
    require(!value.exists(character => character == '\n' || character == '\r' || character == '\u0000'),
      s"profile 字段 $key 含非法字符")
    key -> value
  }

  def values(
    entry: ConfigCatalog.Entry,
    construction: SpmvInputFpgaRuntimeConstruction,
    input: SpmvInputConfig,
    extra: Seq[(String, String)]
  ): Seq[(String, String)] = {
    require(entry.scope == "fpga" && entry.board.contains("u55c") && entry.target == "SPMV",
      s"U55C 输入 runtime 只支持 U55C SPMV terminal：${entry.className}")
    require(construction.capability == "run")
    require(construction.acceleratorHostConfig == SpmvAcceleratorHostConfig.InputU55cRuntime)
    require(input.fp64MulProvider == SpmvFp64MulProvider.XilinxFloatingPointV71,
      "U55C 输入 runtime 必须使用 Xilinx floating_point Binary64 multiply")
    val base = Seq(
      "PROFILE_FORMAT" -> "25",
      "CONFIG_SHORT_NAME" -> entry.shortName,
      "CONFIG_FQCN" -> entry.className,
      "SCOPE" -> entry.scope,
      "CAPABILITY" -> construction.capability,
      "HOST_ABI" -> "none",
      "ACCELERATOR_HOST_KIND" -> construction.acceleratorHostConfig.kind,
      "ACCELERATOR_HOST_ABI" -> construction.acceleratorHostConfig.abi,
      "PROTOCOL_ABI" -> "spmv-input-u55c-windowed-v1",
      "TARGET" -> entry.target,
      "SPMV_XRT_KERNEL" -> "SpmvInputKernel",
      "SPMV_INPUT_HBM_MASTER_COUNT" -> input.totalHbmPortCount.toString,
      "SPMV_INPUT_A_READER_COUNT" -> input.aReaderCount.toString,
      "SPMV_INPUT_X_READER_COUNT" -> input.xReaderCount.toString,
      "SPMV_INPUT_CTRL_READER_COUNT" -> input.ctrlReaderCount.toString,
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
      "SPMV_INPUT_CTRL_BROADCAST" -> "1",
      "SPMV_INPUT_X_WINDOW_SIZE" -> input.xWindowSize.toString,
      "SPMV_INPUT_X_REPLICA_COUNT" -> input.xReplicaCount.toString,
      "SPMV_INPUT_X_BANK_COUNT" -> input.xBankCount.toString,
      "SPMV_INPUT_X_ELEMENT_WIDTH" -> input.xElementWidth.toString,
      "SPMV_INPUT_X_PORT_SCHEDULE" -> input.xPortSchedule.profileName,
      "SPMV_INPUT_X_WRITE_LANES" -> input.xWriteLanes.toString,
      "SPMV_INPUT_X_OVERLAP_LANES" -> input.xOverlapLanes.toString,
      "SPMV_CUPER_SLOT_ABI" -> "cuper-a-slot-v4",
      "SPMV_CUPER_SLOT_COLUMN_BITS" -> input.cuperSlotColumnBits.toString,
      "SPMV_CUPER_SLOT_TAG_BITS" -> input.cuperSlotTagBits.toString,
      "SPMV_CUPER_SLOT_ROW_BITS" -> input.cuperSlotRowBits.toString,
      "SPMV_FP64_MUL_INTERFACE" -> "arithmetic-req-resp-v1",
      "SPMV_FP64_MUL_PROVIDER" -> input.fp64MulProvider.profileName,
      "SPMV_FP64_MUL_LATENCY" -> input.fp64MultiplyLatency.toString,
      "SPMV_FP64_MUL_II" -> input.fp64MultiplyInitiationInterval.toString,
      "SPMV_FP64_MUL_RESPONSE_FIFO_DEPTH" -> input.fp64MultiplyResponseFifoDepth.toString,
      "SPMV_FP64_MUL_LANES" -> input.fp64MultiplyLaneCount.toString,
      "SPMV_FP64_MUL_CORE_COUNT" -> input.fp64MultiplyCoreCount.toString,
      "SPMV_FP64_MUL_TOTAL_LANES" -> input.fp64MultiplyTotalLaneCount.toString
    )
    val all = (base ++ extra).map { case (key, value) => safe(key, value) }
    val duplicates = all.groupBy(_._1).collect { case (key, entries) if entries.size > 1 => key }
    require(duplicates.isEmpty, s"U55C SPMV 输入 profile 含重复字段：${duplicates.toSeq.sorted.mkString(", ")}")
    all
  }
}
