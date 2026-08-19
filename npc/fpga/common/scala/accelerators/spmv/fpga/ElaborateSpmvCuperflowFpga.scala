package accelerators.spmv.fpga

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import org.chipsalliance.cde.config.Parameters
import accelerators.spmv.{SpmvCuperflowConfigKey, SpmvCuperflowConfig}
import accelerators.spmv.inputmul.cuperflow.SpmvCuperflowInputTop
import npc.CdeConfigResolver

/** 为 U55C 生成当前 Cuperflow map -> X -> A 顶层，不生成 Verilator 仿真模型。 */
object ElaborateSpmvCuperflowFpga extends App {
  val output = args.sliding(2).collectFirst {
    case Array("--target-dir", directory) => directory
  }.getOrElse("./fpga/build/manual/spmv-cuperflow-rtl")
  val (entry, construction) = CdeConfigResolver.resolve("", Set("fpga"))
  require(entry.target == "SPMV", s"${entry.className} 不是 SPMV FPGA 终端")
  implicit val parameters: Parameters = construction
  val config = parameters(SpmvCuperflowConfigKey).getOrElse(
    throw new IllegalArgumentException(s"${entry.className} 缺少 SpmvCuperflowConfigKey")
  )
  require(config.fp64MulProvider.profileName == "xilinx-floating-point-v7.1",
    "U55C Cuperflow 必须选择 Xilinx FP64 multiply")

  println(
    s"正在生成 U55C Cuperflow 顶层：Config=${entry.className}, " +
      s"PC=${config.hbmPcCount}, X/A=${config.xRegionBytes}/${config.aRegionBytes} bytes, " +
      s"输出目录=$output"
  )
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new SpmvCuperflowInputTop(config),
    Array("--target-dir", output, "--split-verilog"),
    Array("--disable-annotation-unknown")
  )
  writeManifest(Path.of(output), entry.className, config)

  private def writeManifest(directory: Path, fqcn: String, config: SpmvCuperflowConfig): Unit = {
    val values = Seq(
      "CONFIG_FQCN" -> fqcn,
      "SPMV_CUPERFLOW_HBM_PC_COUNT" -> config.hbmPcCount.toString,
      "SPMV_CUPERFLOW_HBM_BASE" -> s"0x${java.lang.Long.toUnsignedString(config.hbmBase, 16)}",
      "SPMV_CUPERFLOW_HBM_BYTES" -> config.hbmBytes.toString,
      "SPMV_CUPERFLOW_X_REGION_BYTES" -> config.xRegionBytes.toString,
      "SPMV_CUPERFLOW_AXI_ADDR_WIDTH" -> config.axiAddrWidth.toString,
      "SPMV_CUPERFLOW_AXI_DATA_WIDTH" -> config.axiDataWidth.toString,
      "SPMV_CUPERFLOW_AXI_ID_WIDTH" -> config.axiIdWidth.toString,
      "SPMV_CUPERFLOW_MAX_OUTSTANDING_BURSTS" -> config.maxOutstandingBursts.toString,
      "SPMV_CUPERFLOW_X_WINDOW_SIZE" -> config.xWindowSize.toString,
      "SPMV_CUPERFLOW_X_REPLICA_COUNT" -> config.xReplicaCount.toString,
      "SPMV_CUPERFLOW_X_PINGPONG" -> (if (config.xPingPong) "1" else "0"),
      "SPMV_CUPERFLOW_X_BANK_COUNT" -> config.xBankCount.toString,
      "SPMV_CUPERFLOW_X_ELEMENT_WIDTH" -> config.xElementWidth.toString,
      "SPMV_CUPERFLOW_X_STORAGE" -> config.xMemoryPrimitive.profileName,
      "SPMV_CUPERFLOW_X_MEMORY_DATA_WIDTH" -> config.xMemoryDataWidth.toString,
      "SPMV_CUPERFLOW_X_DECODER_LANES" -> config.xWordsPerBeat.toString,
      "SPMV_FP64_MUL_PROVIDER" -> config.fp64MulProvider.profileName,
      "SPMV_FP64_MUL_LATENCY" -> config.fp64MultiplyLatency.toString,
      "SPMV_FP64_MUL_II" -> config.fp64MultiplyInitiationInterval.toString
    )
    Files.createDirectories(directory)
    Files.writeString(
      directory.resolve("spmv-cuperflow-parameters.env"),
      values.sortBy(_._1).map { case (key, value) => s"$key=$value" }.mkString("\n") + "\n",
      StandardCharsets.US_ASCII
    )
  }
}
