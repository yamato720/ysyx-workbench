package scpu.fpga

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scpu._

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

/** 板卡唯一的算子能力表。FPO 数值路径刻意没有 VendorIp 路由。 */
object FpgaOperatorRoutes {
  import ArithmeticRouteOperation._

  private def route(
    target: OperatorRouteTarget,
    module: String,
    width: Int,
    timing: ArithmeticIpTiming,
    reason: OperatorFallbackReason = OperatorFallbackReason.None
  ): OperatorRoute = OperatorRoute(target, module, width, timing.latency, timing.initiationInterval, reason)

  /** Xilinx 整数 IP 和可综合 binary32 直接逻辑的严格 RVF 路由。 */
  def xilinx(width: Int, timing: OperatorIpTimingConfig): OperatorRouteConfig = {
    val multiply = timing.timing(timing.multiplyCycles, timing.multiplyInitiationInterval)
    val divide = timing.timing(timing.divCycles, timing.divInitiationInterval)
    val addSub = timing.timing(timing.floatingAddSubCycles, timing.floatingAddSubInitiationInterval)
    val floatingMultiply = timing.timing(timing.floatingMultiplyCycles, timing.floatingMultiplyInitiationInterval)
    val floatingDivide = timing.timing(timing.floatingDivideCycles, timing.floatingDivideInitiationInterval)
    val fma = timing.timing(timing.floatingFmaCycles, timing.floatingFmaInitiationInterval)
    val sqrt = timing.timing(timing.floatingSqrtCycles, timing.floatingSqrtInitiationInterval)
    val convert = timing.timing(timing.floatingConvertCycles, timing.floatingConvertInitiationInterval)
    val compare = timing.timing(timing.floatingCompareCycles, timing.floatingCompareInitiationInterval)
    val integer = ArithmeticRouteOperation.mOperations.map { operation =>
      val selectedTiming = if (operation.isMultiply) multiply else divide
      val module = if (operation.isMultiply) "npc_int_multiplier_adapter" else "npc_int_divider_adapter"
      operation -> route(OperatorRouteTarget.VendorIp, module, width, selectedTiming)
    }
    val floating = ArithmeticRouteOperation.fOperations.map { operation =>
      val selectedTiming = operation match {
        case Fadd | Fsub => addSub
        case Fmul => floatingMultiply
        case Fdiv => floatingDivide
        case Fsqrt => sqrt
        case Fmadd | Fmsub | Fnmsub | Fnmadd => fma
        case FcvtW | FcvtWu | FcvtL | FcvtLu | FcvtSW | FcvtSWu | FcvtSL | FcvtSLu => convert
        case _ => compare
      }
      if (operation.isDirectFloating)
        operation -> route(OperatorRouteTarget.DirectLogic, "fpga_f32_direct_logic", width, selectedTiming)
      else
        operation -> route(OperatorRouteTarget.HostFallback, "host-mailbox", width, selectedTiming,
          OperatorFallbackReason.FpoRiscvIncompatible)
    }
    OperatorRouteConfig((integer ++ floating).toMap)
  }
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
    toolchain: FpgaToolchainConfig,
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
      "FPGA_FLOATING_FALLBACK" -> toolchain.runtime.floatingFallback,
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
      "NPC_FCMP_II" -> npcConfig.operators.floating.compareTiming.initiationInterval.toString
    ) ++ npcConfig.operators.routes.profileValues(npcConfig.isa) ++ platform.manifestValues(npcConfig)

    val directory = outputDirectory(args)
    Files.createDirectories(directory)
    val content = values.sortBy(_._1).map { case (key, value) => s"$key=$value" }.mkString("\n") + "\n"
    Files.writeString(directory.resolve("fpga-parameters.env"), content, StandardCharsets.US_ASCII)
  }
}
