package npc

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** FPGA performance monitor 写入 `profile.env` 的投影。
  *
  * 它不选择或启用硬件；唯一的硬件来源是板级 CDE
  * `FpgaPerformanceMonitorConfigKey`。该投影只让通用 profile 代码保持对 FPGA
  * 实现类型无依赖。
  */
final case class PerformanceMonitorProfile(
  enabled: Boolean,
  hbmBank: Int,
  bufferBytes: Int,
  maxRecords: Int,
  cacheRecords: Int,
  formatVersion: Int,
  recordBytes: Int,
  traceDataWidth: Int,
  burstRecords: Int
)

object PerformanceMonitorProfile {
  val Disabled: PerformanceMonitorProfile =
    PerformanceMonitorProfile(false, 0, 0, 0, 0, 0, 0, 0, 0)
}

/** Make、NEMU 和 FPGA 工具共同消费的规范化构造描述。 */
object ConstructionProfile {
  private def bit(value: Boolean): String = if (value) "1" else "0"
  private def hex(value: Long): String = s"0x${java.lang.Long.toUnsignedString(value, 16)}"

  private def safe(key: String, value: String): (String, String) = {
    require(key.matches("[A-Z][A-Z0-9_]*"), s"非法 profile 字段名：$key")
    require(!value.exists(character => character == '\n' || character == '\r' || character == '\u0000'),
      s"profile 字段 $key 含非法字符")
    key -> value
  }

  private def cacheValues(prefix: String, cache: CacheConfig): Seq[(String, String)] = Seq(
    s"${prefix}_ENABLED" -> bit(cache.enabled),
    s"${prefix}_CAPACITY_BYTES" -> cache.geometry.capacityBytes.toString,
    s"${prefix}_LINE_BYTES" -> cache.geometry.lineBytes.toString,
    s"${prefix}_MAPPING" -> cache.geometry.mapping.name,
    s"${prefix}_WAYS" -> cache.geometry.ways.toString,
    s"${prefix}_SETS" -> cache.geometry.sets.toString,
    s"${prefix}_REPLACEMENT" -> cache.replacement.name,
    s"${prefix}_READ_MISS" -> cache.policy.readMiss.name,
    s"${prefix}_WRITE_POLICY" -> cache.policy.write.name,
    s"${prefix}_WRITE_MISS" -> cache.policy.writeMiss.name,
    s"${prefix}_STORAGE" -> cache.storage.name
  )

  def values(
    entry: ConfigCatalog.Entry,
    host: HostConstruction,
    config: NpcConfig,
    extra: Seq[(String, String)] = Seq.empty,
    performanceMonitor: PerformanceMonitorProfile = PerformanceMonitorProfile.Disabled,
    runtimeSdbEnabled: Boolean = true
  ): Seq[(String, String)] = {
    val capability = host.capability
    val settings = host.nemuConfig
    val mulDiv = config.operators.mulDiv
    val floating = config.operators.floating
    val isaExtensions = Seq(
      Option.when(config.isa.M)("m"),
      Option.when(config.isa.F)("f"),
      Option.when(config.isa.D)("d"),
      Option.when(config.isa.Zicsr)("_zicsr"),
      Option.when(config.isa.Zifencei)("_zifencei")
    ).flatten.mkString
    require(Set("run", "batch").contains(capability),
      s"终端 Config ${entry.className} 必须是可运行的 NEMU Config")
    val hostAbi = "nemu-construction-v1"
    val expectedBackend = (entry.scope, entry.board) match {
      case ("npc" | "soc", _) => Some("local")
      case ("fpga", Some("u55c")) => Some("u55c")
      case ("fpga", Some("zcu102")) => Some("zcu102")
      case ("fpga", _) => throw new IllegalArgumentException(
        s"FPGA Config ${entry.className} 未声明受支持的板卡"
      )
      case _ => None
    }
    for {
      expected <- expectedBackend
      actual = settings.backend.id
    } require(actual == expected,
      s"Config ${entry.className} 的 NEMU host=$actual 与 $entry 作用域/板卡要求的 $expected 不兼容")
    val protocolAbi = protocolAbiFor(entry, performanceMonitor)
    val requestsHardwareReports = settings.performanceHtml || settings.cacheHtml || settings.pipelineHtml
    if (entry.scope == "fpga" && entry.board.contains("u55c")) {
      require(requestsHardwareReports == performanceMonitor.enabled,
        s"U55C HTML reports must match the performance-monitor hardware ABI: ${entry.className}")
    }
    require(!(performanceMonitor.enabled && runtimeSdbEnabled),
      s"FPGA performance monitoring and interactive SDB are mutually exclusive: ${entry.className}")
    require(entry.scope == "fpga" || !config.cache.usesUram,
      s"CacheStorage.Uram is only valid for an FPGA construction: ${entry.className}")
    val base = Seq(
      "PROFILE_FORMAT" -> "22",
      "CONFIG_SHORT_NAME" -> entry.shortName,
      "CONFIG_FQCN" -> entry.className,
      "SCOPE" -> entry.scope,
      "CAPABILITY" -> capability,
      "HOST_ABI" -> hostAbi,
      "NEMU_PRESET" -> host.nemuPreset,
      "NEMU_BACKEND" -> settings.backend.id,
      "NEMU_TRACE" -> bit(settings.trace),
      "NEMU_WATCHPOINT" -> bit(settings.watchpoint),
      "NEMU_VCD" -> bit(settings.vcd),
      "NEMU_PERFORMANCE_HTML" -> bit(settings.performanceHtml),
      "NEMU_CACHE_HTML" -> bit(settings.cacheHtml),
      "NEMU_PIPELINE_HTML" -> bit(settings.pipelineHtml),
      "NEMU_NPC_DIFFTEST" -> bit(settings.softwareDifftest),
      "NEMU_DEVICES" -> bit(settings.devices),
      "NEMU_OPTIMIZATION" -> settings.optimization,
      "NEMU_DEBUG" -> bit(settings.debug),
      "NEMU_LTO" -> bit(settings.lto),
      "NEMU_ASAN" -> bit(settings.asan),
      "NEMU_MEMORY_STATISTICS_MODE" -> settings.memoryStatisticsMode.name,
      "PROTOCOL_ABI" -> protocolAbi,
      "FPGA_RUNTIME_SDB" -> bit(runtimeSdbEnabled),
      "FPGA_RUNTIME_TRACE" -> bit(performanceMonitor.enabled),
      "FPGA_TRACE_HBM_BANK" -> performanceMonitor.hbmBank.toString,
      "FPGA_TRACE_BUFFER_BYTES" -> performanceMonitor.bufferBytes.toString,
      "FPGA_TRACE_MAX_RECORDS" -> performanceMonitor.maxRecords.toString,
      "FPGA_TRACE_CACHE_RECORDS" -> performanceMonitor.cacheRecords.toString,
      "FPGA_TRACE_FORMAT" -> performanceMonitor.formatVersion.toString,
      "FPGA_TRACE_RECORD_BYTES" -> performanceMonitor.recordBytes.toString,
      "FPGA_TRACE_DATA_WIDTH" -> performanceMonitor.traceDataWidth.toString,
      "FPGA_TRACE_BURST_RECORDS" -> performanceMonitor.burstRecords.toString,
      "TARGET" -> entry.target,
      "XLEN" -> config.isa.xlen.toString,
      "ISA_STRING" -> s"rv${config.isa.xlen}i$isaExtensions",
      "M" -> bit(config.isa.M),
      "F" -> bit(config.isa.F),
      "D" -> bit(config.isa.D),
      "ZICSR" -> bit(config.isa.Zicsr),
      "ZIFENCEI" -> bit(config.isa.Zifencei),
      "PIPELINE" -> bit(config.pipeline.enablePipeline),
      "INTERLOCK" -> bit(config.pipeline.enableInterlock),
      "ID_FWD" -> bit(config.pipeline.forwarding.enableIdForwarding),
      "EX_FWD" -> bit(config.pipeline.forwarding.enableExecuteForwarding),
      "INTEGER_EXECUTE_STAGES" -> config.pipeline.integerExecuteStages.toString,
      "SERIAL_EXECUTE_STAGES" -> config.pipeline.serialExecuteStages.toString,
      "REGISTER_INITIAL_FETCH_REQUEST" -> bit(config.pipeline.registerInitialFetchRequest),
      "SEPARATE_SERIAL_INTEGER_ALU" -> bit(config.pipeline.separateSerialIntegerAlu),
      "SERIAL_EXECUTE_RESULT_FORWARDING" -> bit(config.pipeline.serialExecuteResultForwarding),
      "ARITH_BACKEND" -> mulDiv.implementation.backend.name,
      "ARITH_OUTPUT_FIFO" -> mulDiv.implementation.ip.outputFifoDepth.toString,
      "MUL_CYCLES" -> mulDiv.multiplyTiming.latency.toString,
      "MUL_II" -> mulDiv.multiplyTiming.initiationInterval.toString,
      "DIV_CYCLES" -> mulDiv.divideTiming.latency.toString,
      "DIV_II" -> mulDiv.divideTiming.initiationInterval.toString,
      "FADD_CYCLES" -> floating.addSubTiming.latency.toString,
      "FADD_II" -> floating.addSubTiming.initiationInterval.toString,
      "FMUL_CYCLES" -> floating.multiplyTiming.latency.toString,
      "FMUL_II" -> floating.multiplyTiming.initiationInterval.toString,
      "FDIV_CYCLES" -> floating.divideTiming.latency.toString,
      "FDIV_II" -> floating.divideTiming.initiationInterval.toString,
      "FFMA_CYCLES" -> floating.fmaTiming.latency.toString,
      "FFMA_II" -> floating.fmaTiming.initiationInterval.toString,
      "FSQRT_CYCLES" -> floating.sqrtTiming.latency.toString,
      "FSQRT_II" -> floating.sqrtTiming.initiationInterval.toString,
      "FCVT_CYCLES" -> floating.convertTiming.latency.toString,
      "FCVT_II" -> floating.convertTiming.initiationInterval.toString,
      "FCMP_CYCLES" -> floating.compareTiming.latency.toString,
      "FCMP_II" -> floating.compareTiming.initiationInterval.toString,
      "MEMORY_BASE" -> hex(config.memory.mainMemoryBase),
      "MEMORY_SIZE" -> hex(config.memory.mainMemorySize),
      "RESET_VECTOR" -> s"0x${config.memory.resetVector.toString(16)}",
      "DPI_MEMORY_TIMING_ENABLED" -> bit(config.memory.dpiTiming.enabled),
      "DPI_MEMORY_READ_RESPONSE_MIN_CYCLES" -> config.memory.dpiTiming.minReadResponseCycles.toString,
      "DPI_MEMORY_READ_RESPONSE_MAX_CYCLES" -> config.memory.dpiTiming.maxReadResponseCycles.toString,
      "DPI_MEMORY_WRITE_RESPONSE_MIN_CYCLES" -> config.memory.dpiTiming.minWriteResponseCycles.toString,
      "DPI_MEMORY_WRITE_RESPONSE_MAX_CYCLES" -> config.memory.dpiTiming.maxWriteResponseCycles.toString,
      "DPI_MEMORY_TIMING_SEED" -> config.memory.dpiTiming.randomSeed.toString,
      "AXI_ADDR_WIDTH" -> config.axi.addrWidth.toString,
      "AXI_DATA_WIDTH" -> config.axi.dataWidth.toString,
      "AXI_EXTERNAL_DATA_WIDTH" -> config.axi.resolvedExternalDataWidth.toString,
      "AXI_MEMORY_DATA_WIDTH" -> config.memoryDataWidth.toString,
      "AXI_ID_WIDTH" -> config.axi.idWidth.toString,
      "AXI_EXTERNAL" -> bit(config.axi.useExternalMaster)
    )
    val cache = cacheValues("ICACHE", config.cache.icache) ++
      cacheValues("DCACHE", config.cache.dcache) ++
      cacheValues("L2CACHE", config.cache.l2cache) ++ Seq(
        "CACHE_ACCESS_MODE" -> config.cache.accessMode.name,
        "CACHE_REQUEST_QUEUE_DEPTH" -> config.cache.pipelinedQueues.requestDepth.toString,
        "CACHE_RESPONSE_QUEUE_DEPTH" -> config.cache.pipelinedQueues.responseDepth.toString,
        "CACHE_FETCH_QUEUE_DEPTH" -> config.cache.pipelinedQueues.fetchDepth.toString,
        "CACHE_MEMORY_QUEUE_DEPTH" -> config.cache.pipelinedQueues.memoryDepth.toString,
        "INSTRUCTION_BUFFER_ENABLED" -> bit(config.cache.instructionBuffer.enabled),
        "INSTRUCTION_BUFFER_ENTRIES" -> config.cache.instructionBuffer.entries.toString
      )
    val all = (base ++ cache ++ config.operators.routes.profileValues(config.isa) ++ extra)
      .map { case (key, value) => safe(key, value) }
    val duplicates = all.groupBy(_._1).collect { case (key, values) if values.size > 1 => key }
    require(duplicates.isEmpty, s"profile 含重复字段：${duplicates.toSeq.sorted.mkString(", ")}")
    all
  }

  /** 从终端范围和监测 ABI 推导硬件协议 ABI。 */
  def protocolAbiFor(entry: ConfigCatalog.Entry, performanceMonitor: PerformanceMonitorProfile): String = {
    require(!performanceMonitor.enabled ||
      (entry.scope == "fpga" && entry.board.contains("u55c") && entry.target == "NPC"),
      s"FPGA performance monitoring only supports the U55C bare-NPC terminal: ${entry.className}")
    (entry.scope, entry.board) match {
      case ("npc", _) => "npc-dpi-v1"
      case ("soc", _) => "ysyx-dpi-v1"
      case ("fpga", Some("u55c")) if performanceMonitor.enabled => "npc-fpga-runtime-v13-performance-monitor"
      case ("fpga", Some("u55c")) => "npc-fpga-runtime-v11"
      case ("fpga", _) => "npc-fpga-runtime-v7"
      case (scope, _) => throw new IllegalArgumentException(s"未知终端作用域：$scope")
    }
  }

  def write(path: Path, values: Seq[(String, String)]): Unit = {
    Option(path.getParent).foreach(Files.createDirectories(_))
    val content = values.map { case (key, value) => s"$key=$value" }.mkString("\n") + "\n"
    Files.writeString(path, content, StandardCharsets.US_ASCII)
  }
}

/** 为 L1 NPC Config 生成规范化 profile。 */
object DescribeNpcConfig extends App {
  require(args.length == 1, "用法：npc.DescribeNpcConfig <profile.env>")
  val (entry, construction) = ConfigResolver.resolve("")
  ConstructionProfile.write(
    Path.of(args(0)),
    ConstructionProfile.values(entry, construction, construction.config)
  )
}
