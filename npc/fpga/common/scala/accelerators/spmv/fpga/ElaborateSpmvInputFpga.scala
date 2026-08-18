package accelerators.spmv.fpga

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import org.chipsalliance.cde.config.Parameters
import accelerators.spmv.{SpmvInputConfig, SpmvInputConfigKey, SpmvXPortSchedule}
import accelerators.spmv.inputmul.pingpong.SpmvAxPingPongInputMulTop
import accelerators.spmv.inputmul.preload.SpmvPreloadInputMulTop
import npc.CdeConfigResolver

/** 为 U55C 输入/乘法 runtime 生成不含仿真模型的 SPMV 顶层。 */
object ElaborateSpmvInputFpga extends App {
  val output = args.sliding(2).collectFirst {
    case Array("--target-dir", directory) => directory
  }.getOrElse("./fpga/build/manual/spmv-input-rtl")
  val (entry, construction) = CdeConfigResolver.resolve("", Set("fpga"))
  require(entry.target == "SPMV", s"${entry.className} 不是 SPMV FPGA 终端")
  implicit val parameters: Parameters = construction
  val input = parameters(SpmvInputConfigKey).getOrElse(
    throw new IllegalArgumentException(s"${entry.className} 缺少 SpmvInputConfigKey")
  )
  require(input.fp64MulProvider.profileName == "xilinx-floating-point-v7.1",
    "U55C 输入 runtime 必须选择 Xilinx FP64 multiply")

  println(
    s"正在生成 U55C SPMV 输入顶层：Config=${entry.className}, " +
      s"A=${input.aReaderCount}, X=${input.xReaderCount}, Ctrl=${input.ctrlReaderCount}, 输出目录=$output"
  )
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    input.xPortSchedule match {
      case SpmvXPortSchedule.Preload => new SpmvPreloadInputMulTop(input)
      case SpmvXPortSchedule.PingPong => new SpmvAxPingPongInputMulTop(input)
    },
    Array("--target-dir", output, "--split-verilog"),
    Array("--disable-annotation-unknown")
  )
  writeManifest(Path.of(output), entry.className, input)

  private def writeManifest(directory: Path, fqcn: String, input: SpmvInputConfig): Unit = {
    val values = Seq(
      "CONFIG_FQCN" -> fqcn,
      "SPMV_INPUT_HBM_MASTER_COUNT" -> input.totalHbmPortCount.toString,
      "SPMV_INPUT_A_READER_COUNT" -> input.aReaderCount.toString,
      "SPMV_INPUT_X_READER_COUNT" -> input.xReaderCount.toString,
      "SPMV_INPUT_CTRL_READER_COUNT" -> input.ctrlReaderCount.toString,
      "SPMV_INPUT_AXI_ADDR_WIDTH" -> input.axiAddrWidth.toString,
      "SPMV_INPUT_AXI_DATA_WIDTH" -> input.axiDataWidth.toString,
      "SPMV_INPUT_AXI_ID_WIDTH" -> input.axiIdWidth.toString,
      "SPMV_INPUT_X_PORT_SCHEDULE" -> input.xPortSchedule.profileName,
      "SPMV_FP64_MUL_PROVIDER" -> input.fp64MulProvider.profileName,
      "SPMV_FP64_MUL_LATENCY" -> input.fp64MultiplyLatency.toString,
      "SPMV_FP64_MUL_II" -> input.fp64MultiplyInitiationInterval.toString
    )
    Files.createDirectories(directory)
    Files.writeString(
      directory.resolve("spmv-input-parameters.env"),
      values.sortBy(_._1).map { case (key, value) => s"$key=$value" }.mkString("\n") + "\n",
      StandardCharsets.US_ASCII
    )
  }
}
