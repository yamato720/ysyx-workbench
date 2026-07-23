package scpu

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

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
    extra: Seq[(String, String)] = Seq.empty
  ): Seq[(String, String)] = {
    val capability = host.capability
    val settings = host.nemuConfig
    val mulDiv = config.operators.mulDiv
    val floating = config.operators.floating
    val isaExtensions = Seq(
      Option.when(config.isa.M)("m"),
      Option.when(config.isa.F)("f"),
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
    val protocolAbi = entry.scope match {
      case "npc" => "npc-dpi-v1"
      case "soc" => "ysyx-dpi-v1"
      case "fpga" => "npc-fpga-mailbox-v3"
      case scope => throw new IllegalArgumentException(s"未知终端作用域：$scope")
    }
    val base = Seq(
      "PROFILE_FORMAT" -> "10",
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
      "TARGET" -> entry.target,
      "XLEN" -> config.isa.xlen.toString,
      "ISA_STRING" -> s"rv${config.isa.xlen}i$isaExtensions",
      "M" -> bit(config.isa.M),
      "F" -> bit(config.isa.F),
      "ZICSR" -> bit(config.isa.Zicsr),
      "PIPELINE" -> bit(config.pipeline.enablePipeline),
      "INTERLOCK" -> bit(config.pipeline.enableInterlock),
      "ID_FWD" -> bit(config.pipeline.forwarding.enableIdForwarding),
      "EX_FWD" -> bit(config.pipeline.forwarding.enableExecuteForwarding),
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
    val all = (base ++ config.operators.routes.profileValues(config.isa) ++ extra).map { case (key, value) => safe(key, value) }
    val duplicates = all.groupBy(_._1).collect { case (key, values) if values.size > 1 => key }
    require(duplicates.isEmpty, s"profile 含重复字段：${duplicates.toSeq.sorted.mkString(", ")}")
    all
  }

  def write(path: Path, values: Seq[(String, String)]): Unit = {
    Option(path.getParent).foreach(Files.createDirectories(_))
    val content = values.map { case (key, value) => s"$key=$value" }.mkString("\n") + "\n"
    Files.writeString(path, content, StandardCharsets.US_ASCII)
  }
}

/** 为 L1 NPC Config 生成规范化 profile。 */
object DescribeNpcConfig extends App {
  require(args.length == 1, "用法：scpu.DescribeNpcConfig <profile.env>")
  val (entry, construction) = ConfigResolver.resolve("")
  ConstructionProfile.write(
    Path.of(args(0)),
    ConstructionProfile.values(entry, construction, construction.config)
  )
}
