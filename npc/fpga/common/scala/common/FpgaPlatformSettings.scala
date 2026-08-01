package npc.fpga

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import npc._

sealed abstract class FpgaBoard(val name: String)

object FpgaBoard {
  case object Zcu102 extends FpgaBoard("zcu102")
  case object U55c extends FpgaBoard("u55c")

  def parse(value: String): FpgaBoard = value.toLowerCase match {
    case "zcu102" => Zcu102
    case "u55c" => U55c
    case other => sys.error(s"Unsupported FPGA board '$other' (expected zcu102 or u55c)")
  }
}

/** 由终端板卡 Config 固定的 FPGA 地址和时钟策略。 */
final case class FpgaPlatformSettings(
  board: FpgaBoard,
  clockMHz: Int,
  memoryHostBase: Long,
  controlBase: Long,
  mailboxBase: Long,
  platformClockMHz: Int
) {
  require(clockMHz > 0, s"FPGA core clock MHz must be positive, got $clockMHz")
  require(platformClockMHz > 0,
    s"FPGA platform clock MHz must be positive, got $platformClockMHz")

  def manifestValues(npcConfig: NpcConfig): Seq[(String, String)] = Seq(
    "FPGA_BOARD" -> board.name,
    "FPGA_CLOCK_MHZ" -> clockMHz.toString,
    "FPGA_PLATFORM_CLOCK_MHZ" -> platformClockMHz.toString,
    "FPGA_MEMORY_BASE" -> FpgaPlatformSettings.hex(npcConfig.memory.mainMemoryBase),
    "FPGA_MEMORY_SIZE" -> FpgaPlatformSettings.hex(npcConfig.memory.mainMemorySize),
    "FPGA_MEMORY_HOST_BASE" -> FpgaPlatformSettings.hex(memoryHostBase),
    "FPGA_CONTROL_BASE" -> FpgaPlatformSettings.hex(controlBase),
    "FPGA_MAILBOX_BASE" -> FpgaPlatformSettings.hex(mailboxBase)
  )
}

object FpgaPlatformSettings {
  private[fpga] def hex(value: Long): String = s"0x${java.lang.Long.toUnsignedString(value, 16)}"
}

object FpgaElaborationManifest {
  private def bit(value: Boolean): String = if (value) "1" else "0"
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

  private def outputDirectory(args: Array[String]): Path = {
    args.sliding(2).collectFirst {
      case Array("--target-dir", directory) => Path.of(directory)
    }.orElse(args.collectFirst {
      case argument if argument.startsWith("--target-dir=") => Path.of(argument.stripPrefix("--target-dir="))
    }).getOrElse(Path.of("."))
  }

  def write(
    args: Array[String],
    npcConfig: NpcConfig,
    platform: FpgaPlatformSettings,
    performanceMonitor: FpgaPerformanceMonitorConfig,
    runtimeSdb: FpgaRuntimeSdbConfig,
    ipAttachment: FpgaIpAttachment,
    toolchain: FpgaToolchainConfig,
    scalaConfig: String,
    target: String
  ): Unit = {
    val values = Seq(
      "CONFIG_FQCN" -> scalaConfig,
      "NPC_TARGET" -> target,
      "NPC_XLEN" -> npcConfig.isa.xlen.toString,
      "NPC_AXI_MEMORY_DATA_WIDTH" -> npcConfig.memoryDataWidth.toString,
      "NPC_PIPELINE" -> bit(npcConfig.pipeline.enablePipeline),
      "NPC_INTERLOCK" -> bit(npcConfig.pipeline.enableInterlock),
      "NPC_ID_FWD" -> bit(npcConfig.pipeline.forwarding.enableIdForwarding),
      "NPC_EX_FWD" -> bit(npcConfig.pipeline.forwarding.enableExecuteForwarding),
      "NPC_M" -> bit(npcConfig.isa.M),
      "NPC_F" -> bit(npcConfig.isa.F),
      "NPC_D" -> bit(npcConfig.isa.D),
      "NPC_ZICSR" -> bit(npcConfig.isa.Zicsr),
      "NPC_ZIFENCEI" -> bit(npcConfig.isa.Zifencei),
      "FPGA_RUNTIME_SDB" -> bit(runtimeSdb.enabled),
      "FPGA_RUNTIME_TRACE" -> bit(performanceMonitor.enabled),
      "FPGA_TRACE_HBM_BANK" -> performanceMonitor.hbmBank.toString,
      "FPGA_TRACE_BUFFER_BYTES" -> performanceMonitor.bufferBytes.toString,
      "FPGA_TRACE_MAX_RECORDS" -> performanceMonitor.maxRecords.toString,
      "FPGA_TRACE_CACHE_RECORDS" -> performanceMonitor.cacheRecords.toString,
      "FPGA_TRACE_FORMAT" -> performanceMonitor.profile.formatVersion.toString,
      "FPGA_TRACE_RECORD_BYTES" -> performanceMonitor.profile.recordBytes.toString,
      "FPGA_TRACE_DATA_WIDTH" -> performanceMonitor.traceDataWidth.toString,
      "FPGA_TRACE_BURST_RECORDS" -> performanceMonitor.burstRecords.toString,
      "FPGA_NOTIFICATION_MODE" -> toolchain.runtime.notificationMode,
      "NPC_ARITH_BACKEND" -> npcConfig.operators.mulDiv.implementation.backend.name,
      "NPC_ARITH_OUTPUT_FIFO" -> npcConfig.operators.mulDiv.implementation.ip.outputFifoDepth.toString,
      "NPC_MUL_CYCLES" -> npcConfig.operators.mulDiv.multiplyTiming.latency.toString,
      "NPC_MUL_II" -> npcConfig.operators.mulDiv.multiplyTiming.initiationInterval.toString,
      "NPC_DIV_CYCLES" -> npcConfig.operators.mulDiv.divideTiming.latency.toString,
      "NPC_DIV_II" -> npcConfig.operators.mulDiv.divideTiming.initiationInterval.toString,
      "NPC_FADD_CYCLES" -> npcConfig.operators.floating.addSubTiming.latency.toString,
      "NPC_FADD_II" -> npcConfig.operators.floating.addSubTiming.initiationInterval.toString,
      "NPC_FMUL_CYCLES" -> npcConfig.operators.floating.multiplyTiming.latency.toString,
      "NPC_FMUL_II" -> npcConfig.operators.floating.multiplyTiming.initiationInterval.toString,
      "NPC_FDIV_CYCLES" -> npcConfig.operators.floating.divideTiming.latency.toString,
      "NPC_FDIV_II" -> npcConfig.operators.floating.divideTiming.initiationInterval.toString,
      "NPC_FFMA_CYCLES" -> npcConfig.operators.floating.fmaTiming.latency.toString,
      "NPC_FFMA_II" -> npcConfig.operators.floating.fmaTiming.initiationInterval.toString,
      "NPC_FSQRT_CYCLES" -> npcConfig.operators.floating.sqrtTiming.latency.toString,
      "NPC_FSQRT_II" -> npcConfig.operators.floating.sqrtTiming.initiationInterval.toString,
      "NPC_FCVT_CYCLES" -> npcConfig.operators.floating.convertTiming.latency.toString,
      "NPC_FCVT_II" -> npcConfig.operators.floating.convertTiming.initiationInterval.toString,
      "NPC_FCMP_CYCLES" -> npcConfig.operators.floating.compareTiming.latency.toString,
      "NPC_FCMP_II" -> npcConfig.operators.floating.compareTiming.initiationInterval.toString,
      "INSTRUCTION_BUFFER_ENABLED" -> bit(npcConfig.cache.instructionBuffer.enabled),
      "INSTRUCTION_BUFFER_ENTRIES" -> npcConfig.cache.instructionBuffer.entries.toString
    ) ++ cacheValues("ICACHE", npcConfig.cache.icache) ++
      cacheValues("DCACHE", npcConfig.cache.dcache) ++
      cacheValues("L2CACHE", npcConfig.cache.l2cache) ++
      npcConfig.operators.routes.profileValues(npcConfig.isa) ++
      ipAttachment.manifestValues ++
      platform.manifestValues(npcConfig)

    val directory = outputDirectory(args)
    Files.createDirectories(directory)
    val content = values.sortBy(_._1).map { case (key, value) => s"$key=$value" }.mkString("\n") + "\n"
    Files.writeString(directory.resolve("fpga-parameters.env"), content, StandardCharsets.US_ASCII)
  }
}
