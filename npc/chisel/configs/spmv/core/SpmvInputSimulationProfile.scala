package npc

/** SPMV 输入顶层 smoke 构造的 CPU-free profile。 */
object SpmvInputSimulationProfile {
  private def hex(value: Long): String =
    s"0x${java.lang.Long.toUnsignedString(value, 16)}"

  def values(
    entry: ConfigCatalog.Entry,
    construction: SpmvInputSimulationConstruction,
    input: SpmvInputConfig
  ): Seq[(String, String)] = {
    require(entry.scope == "spmv" && entry.board.isEmpty && entry.target == "SPMV")
    require(construction.capability == "run")
    require(construction.acceleratorHostConfig == AcceleratorHostConfig.SpmvInputSmoke)
    Seq(
      "PROFILE_FORMAT" -> "22",
      "CONFIG_SHORT_NAME" -> entry.shortName,
      "CONFIG_FQCN" -> entry.className,
      "SCOPE" -> entry.scope,
      "CAPABILITY" -> construction.capability,
      "HOST_ABI" -> "none",
      "ACCELERATOR_HOST_KIND" -> construction.acceleratorHostConfig.kind,
      "ACCELERATOR_HOST_ABI" -> "spmv-input-smoke-v1",
      "PROTOCOL_ABI" -> "spmv-input-v1",
      "TARGET" -> entry.target,
      "SPMV_INPUT_A_READER_COUNT" -> input.aReaderCount.toString,
      "SPMV_INPUT_X_READER_COUNT" -> input.xReaderCount.toString,
      "SPMV_INPUT_HBM_CHANNEL_COUNT" -> input.hbmChannelCount.toString,
      "SPMV_INPUT_HBM_BASE" -> hex(input.hbmBase),
      "SPMV_INPUT_HBM_BYTES" -> input.hbmBytes.toString,
      "SPMV_INPUT_HBM_CHANNEL_ALIGNMENT_BYTES" -> input.channelBaseAlignmentBytes.toString,
      "SPMV_INPUT_AXI_ADDR_WIDTH" -> input.axiAddrWidth.toString,
      "SPMV_INPUT_AXI_DATA_WIDTH" -> input.axiDataWidth.toString,
      "SPMV_INPUT_AXI_ID_WIDTH" -> input.axiIdWidth.toString
    )
  }
}
