package npc

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** FPGA runtime trace 写入 `profile.env` 的投影。
  *
  * 它不选择或启用硬件；唯一的硬件来源是板级 CDE
  * `FpgaRuntimeTraceConfigKey`。该投影只让通用 profile 代码保持对 FPGA
  * 实现类型无依赖。
  */
final case class RuntimeTraceProfile(
  enabled: Boolean,
  hbmBank: Int,
  bufferBytes: Int,
  maxRecords: Int,
  cacheRecords: Int
)

object RuntimeTraceProfile {
  val Disabled: RuntimeTraceProfile = RuntimeTraceProfile(false, 0, 0, 0, 0)
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

  def values(
    entry: ConfigCatalog.Entry,
    host: HostConstruction,
    config: NpcConfig,
    extra: Seq[(String, String)] = Seq.empty,
    runtimeTrace: RuntimeTraceProfile = RuntimeTraceProfile.Disabled
  ): Seq[(String, String)] = {
    val capability = host.capability
    val settings = host.nemuConfig
    val mulDiv = config.operators.mulDiv
    val floating = config.operators.floating
    val isaExtensions = Seq(
      Option.when(config.isa.M)("m"),
      Option.when(config.isa.F)("f"),
      Option.when(config.isa.D)("d"),
      Option.when(config.isa.Zicsr)("_zicsr")
    ).flatten.mkString
    require(capability == "run", s"终端 Config ${entry.className} 必须是可运行的 NEMU Config")
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
    val protocolAbi = protocolAbiFor(entry, runtimeTrace)
    val base = Seq(
      "PROFILE_FORMAT" -> "18",
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
      "NEMU_PIPELINE_HTML" -> bit(settings.pipelineHtml),
      "NEMU_NPC_DIFFTEST" -> bit(settings.softwareDifftest),
      "NEMU_DEVICES" -> bit(settings.devices),
      "NEMU_OPTIMIZATION" -> settings.optimization,
      "NEMU_DEBUG" -> bit(settings.debug),
      "NEMU_LTO" -> bit(settings.lto),
      "NEMU_ASAN" -> bit(settings.asan),
      "PROTOCOL_ABI" -> protocolAbi,
      "FPGA_RUNTIME_TRACE" -> bit(runtimeTrace.enabled),
      "FPGA_TRACE_HBM_BANK" -> runtimeTrace.hbmBank.toString,
      "FPGA_TRACE_BUFFER_BYTES" -> runtimeTrace.bufferBytes.toString,
      "FPGA_TRACE_MAX_RECORDS" -> runtimeTrace.maxRecords.toString,
      "FPGA_TRACE_CACHE_RECORDS" -> runtimeTrace.cacheRecords.toString,
      "TARGET" -> entry.target,
      "XLEN" -> config.isa.xlen.toString,
      "ISA_STRING" -> s"rv${config.isa.xlen}i$isaExtensions",
      "M" -> bit(config.isa.M),
      "F" -> bit(config.isa.F),
      "D" -> bit(config.isa.D),
      "ZICSR" -> bit(config.isa.Zicsr),
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
      "AXI_ADDR_WIDTH" -> config.axi.addrWidth.toString,
      "AXI_DATA_WIDTH" -> config.axi.dataWidth.toString,
      "AXI_ID_WIDTH" -> config.axi.idWidth.toString,
      "AXI_EXTERNAL" -> bit(config.axi.useExternalMaster)
    )
    val all = (base ++ config.operators.routes.profileValues(config.isa) ++ extra)
      .map { case (key, value) => safe(key, value) }
    val duplicates = all.groupBy(_._1).collect { case (key, values) if values.size > 1 => key }
    require(duplicates.isEmpty, s"profile 含重复字段：${duplicates.toSeq.sorted.mkString(", ")}")
    all
  }

  /** 从终端范围与板级 trace 投影推导硬件协议 ABI。 */
  def protocolAbiFor(entry: ConfigCatalog.Entry, runtimeTrace: RuntimeTraceProfile): String = {
    require(!runtimeTrace.enabled || (entry.scope == "fpga" && entry.board.contains("u55c") &&
      entry.target == "NPC"),
      s"runtime trace is only supported by the U55C bare-NPC terminal: ${entry.className}")
    (entry.scope, entry.board, runtimeTrace.enabled) match {
      case ("npc", _, _) => "npc-dpi-v1"
      case ("soc", _, _) => "ysyx-dpi-v1"
      case ("fpga", Some("u55c"), true) => "npc-fpga-runtime-v12"
      case ("fpga", Some("u55c"), false) => "npc-fpga-runtime-v11"
      case ("fpga", _, _) => "npc-fpga-runtime-v7"
      case (scope, _, _) => throw new IllegalArgumentException(s"未知终端作用域：$scope")
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
