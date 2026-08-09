package npc

/** SPMV FPGA 资产与软件 host 的独立 profile，不投影 CPU ISA、流水线或 cache 字段。 */
object SpmvConstructionProfile {
  private def safe(key: String, value: String): (String, String) = {
    require(key.matches("[A-Z][A-Z0-9_]*"), s"非法 profile 字段名：$key")
    require(!value.exists(character => character == '\n' || character == '\r' || character == '\u0000'),
      s"profile 字段 $key 含非法字符")
    key -> value
  }

  def values(
    entry: ConfigCatalog.Entry,
    construction: FpgaToolchainConstruction with AcceleratorHostConstruction,
    config: SpmvAcceleratorConfig,
    extra: Seq[(String, String)]
  ): Seq[(String, String)] = {
    require(entry.scope == "fpga" && entry.board.contains("u55c") && entry.target == "SPMV",
      s"SPMV resource probe only supports the U55C SPMV terminal: ${entry.className}")
    require(Set("synthesize-only", "bitstream-only").contains(construction.capability),
      s"SPMV terminal must be synthesize-only or bitstream-only: ${entry.className}")
    val base = Seq(
      "PROFILE_FORMAT" -> "22",
      "CONFIG_SHORT_NAME" -> entry.shortName,
      "CONFIG_FQCN" -> entry.className,
      "SCOPE" -> entry.scope,
      "CAPABILITY" -> construction.capability,
      "HOST_ABI" -> "none",
      "ACCELERATOR_HOST_KIND" -> construction.acceleratorHostConfig.kind,
      "ACCELERATOR_HOST_ABI" -> construction.acceleratorHostConfig.abi,
      "PROTOCOL_ABI" -> (if (construction.capability == "bitstream-only")
        "spmv-resource-probe-v2" else "spmv-resource-probe-v1"),
      "TARGET" -> entry.target,
      "SPMV_HBM_PC_COUNT" -> config.hbmPcCount.toString,
      "SPMV_AXI_ADDR_WIDTH" -> config.axiAddrWidth.toString,
      "SPMV_AXI_DATA_WIDTH" -> config.axiDataWidth.toString,
      "SPMV_AXI_ID_WIDTH" -> config.axiIdWidth.toString,
      "SPMV_ELEMENT_WIDTH" -> config.elementWidth.toString,
      "SPMV_ELEMENT_FORMAT" -> (if (config.elementWidth == 32) "fp32-bit-pattern" else "fp64-bit-pattern"),
      "SPMV_X_ELEMENTS_PER_PC" -> config.elementsPerPc.toString,
      "SPMV_X_READ_ELEMENTS_PER_CYCLE" -> config.readElementsPerCycle.toString,
      "SPMV_X_WRITE_ELEMENTS_PER_CYCLE" -> config.writeElementsPerCycle.toString,
      "SPMV_X_STORAGE" -> config.storage.name,
      "SPMV_URAM_BANKS_PER_PC" -> config.uramBanksPerPc.toString,
      "SPMV_URAM_BANK_DEPTH" -> config.uramBankDepth.toString,
      "SPMV_PARALLEL_READ_LANES" -> config.readElementsPerCycle.toString,
      "SPMV_PARALLEL_WRITE_LANES" -> config.writeElementsPerCycle.toString,
      "SPMV_BURST_BEATS" -> config.burstBeats.toString,
      "SPMV_BASE_ALIGNMENT_BYTES" -> config.baseAlignmentBytes.toString,
      "SPMV_OUTSTANDING_BURSTS_PER_PC" -> config.outstandingBurstsPerPc.toString,
      "SPMV_BEATS_PER_PC" -> config.beatsPerPc.toString,
      "SPMV_BURSTS_PER_PC" -> config.burstsPerPc.toString,
      "SPMV_X_BYTES_PER_PC" -> config.bytesPerPc.toString,
      "SPMV_TOTAL_CACHE_BYTES" -> config.totalCacheBytes.toString,
      "SPMV_CLOCK_MHZ" -> config.clockMHz.toString
    )
    val all = (base ++ extra).map { case (key, value) => safe(key, value) }
    val duplicates = all.groupBy(_._1).collect { case (key, entries) if entries.size > 1 => key }
    require(duplicates.isEmpty, s"SPMV profile 含重复字段：${duplicates.toSeq.sorted.mkString(", ")}")
    all
  }
}
