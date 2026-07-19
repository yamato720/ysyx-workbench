package scpu.fpga

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scpu.NpcConfig

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

/** 由终端板卡 Config 固定的 FPGA 地址、时钟和 IP 适配参数。 */
final case class FpgaPlatformSettings(
  board: FpgaBoard,
  clockMHz: Int,
  memoryHostBase: Long,
  controlBase: Long,
  mailboxBase: Long,
  dividerIpCycles: Int,
  dividerAdapterCycles: Int
) {
  require(clockMHz >= 0, s"FPGA clock MHz must be nonnegative, got $clockMHz")

  def manifestValues(npcConfig: NpcConfig): Seq[(String, String)] = Seq(
    "FPGA_BOARD" -> board.name,
    "FPGA_CLOCK_MHZ" -> clockMHz.toString,
    "FPGA_MEMORY_BASE" -> FpgaPlatformSettings.hex(npcConfig.memory.mainMemoryBase),
    "FPGA_MEMORY_SIZE" -> FpgaPlatformSettings.hex(npcConfig.memory.mainMemorySize),
    "FPGA_MEMORY_HOST_BASE" -> FpgaPlatformSettings.hex(memoryHostBase),
    "FPGA_CONTROL_BASE" -> FpgaPlatformSettings.hex(controlBase),
    "FPGA_MAILBOX_BASE" -> FpgaPlatformSettings.hex(mailboxBase),
    "FPGA_DIV_IP_CYCLES" -> dividerIpCycles.toString,
    "FPGA_DIV_ADAPTER_CYCLES" -> dividerAdapterCycles.toString
  )
}

object FpgaPlatformSettings {
  private[fpga] def hex(value: Long): String = s"0x${java.lang.Long.toUnsignedString(value, 16)}"
}

/** Vivado/Vitis 消费的器件、平台和实现策略。 */
final case class FpgaToolSettings(
  fpgaType: String,
  part: String,
  platform: String,
  boardPart: String,
  vivadoVersion: String,
  vitisVersion: String,
  vitisTarget: String,
  timingWnsMinNs: String,
  implementationStrategy: String,
  memoryKind: String,
  plGicSpi: Int = 0,
  floatingFallback: String = "host-mailbox"
) {
  require(Set("zynqmp", "alveo").contains(fpgaType), s"不支持的 FPGA 类型：$fpgaType")
  require(implementationStrategy.matches("[A-Za-z0-9_]+"),
    s"FPGA 实现策略含非法字符：$implementationStrategy")
  require(floatingFallback == "host-mailbox",
    s"不支持的 FPGA 浮点回退策略：$floatingFallback（当前仅支持 host-mailbox）")
}

/** 板卡实现流程的宿主并行度与策略搜索开关。
  *
  * `synthesisParallelJobs` 与 `implementationParallelJobs` 分别映射到 Vivado/Vitis 的
  * `synth.jobs` 和 `impl.jobs`，表示宿主工具可并发执行的 worker jobs，不表示 FPGA 内 CPU 核数。
  * 开启策略搜索时，Vitis 除默认 implementation run 外，还会为板卡的实现策略额外启动 run。
  */
final case class FpgaBuildSettings(
  synthesisParallelJobs: Int,
  implementationParallelJobs: Int,
  implementationStrategySearch: Boolean = false
) {
  require(synthesisParallelJobs > 0 && implementationParallelJobs > 0,
    "FPGA 构造并行任务数必须为正数")
}

object FpgaElaborationManifest {
  private def bit(value: Boolean): String = if (value) "1" else "0"

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
    tools: FpgaToolSettings,
    scalaConfig: String,
    target: String
  ): Unit = {
    val values = Seq(
      "CONFIG_FQCN" -> scalaConfig,
      "NPC_TARGET" -> target,
      "NPC_XLEN" -> npcConfig.isa.xlen.toString,
      "NPC_PIPELINE" -> bit(npcConfig.pipeline.enablePipeline),
      "NPC_INTERLOCK" -> bit(npcConfig.pipeline.enableInterlock),
      "NPC_ID_FWD" -> bit(npcConfig.pipeline.forwarding.enableIdForwarding),
      "NPC_EX_FWD" -> bit(npcConfig.pipeline.forwarding.enableExecuteForwarding),
      "NPC_F" -> bit(npcConfig.isa.F),
      "NPC_ZICSR" -> bit(npcConfig.isa.Zicsr),
      "FPGA_FLOATING_FALLBACK" -> tools.floatingFallback,
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
      "NPC_FCMP_II" -> npcConfig.operators.floating.compareTiming.initiationInterval.toString
    ) ++ platform.manifestValues(npcConfig)

    val directory = outputDirectory(args)
    Files.createDirectories(directory)
    val content = values.sortBy(_._1).map { case (key, value) => s"$key=$value" }.mkString("\n") + "\n"
    Files.writeString(directory.resolve("fpga-parameters.env"), content, StandardCharsets.US_ASCII)
  }
}
