package accelerators.spmv.fpga

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import org.chipsalliance.cde.config.Parameters
import accelerators.spmv.{SpmvAcceleratorConfig, SpmvAcceleratorConfigKey}
import accelerators.spmv.probe.SpmvResourceProbeTop
import npc.CdeConfigResolver

object ElaborateSpmvResourceProbe extends App {
  val output = args.sliding(2).collectFirst {
    case Array("--target-dir", directory) => directory
  }.getOrElse("./fpga/build/manual/spmv-rtl")
  val (entry, construction) = CdeConfigResolver.resolve("", Set("fpga"))
  require(entry.target == "SPMV", s"${entry.className} 不是 SPMV 终端")
  implicit val parameters: Parameters = construction
  val config = parameters(SpmvAcceleratorConfigKey).getOrElse(
    throw new IllegalArgumentException(s"${entry.className} 缺少 SpmvAcceleratorConfigKey")
  )
  println(s"正在生成 SPMV 资源探针：PC=${config.hbmPcCount}, element=${config.elementWidth}, 输出目录=$output")
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new SpmvResourceProbeTop(config),
    Array("--target-dir", output, "--split-verilog"),
    Array("--disable-annotation-unknown")
  )
  writeManifest(Path.of(output), entry.className, config)

  private def writeManifest(directory: Path, fqcn: String, config: SpmvAcceleratorConfig): Unit = {
    val values = Seq(
      "CONFIG_FQCN" -> fqcn,
      "SPMV_HBM_PC_COUNT" -> config.hbmPcCount.toString,
      "SPMV_AXI_ADDR_WIDTH" -> config.axiAddrWidth.toString,
      "SPMV_AXI_DATA_WIDTH" -> config.axiDataWidth.toString,
      "SPMV_AXI_ID_WIDTH" -> config.axiIdWidth.toString,
      "SPMV_ELEMENT_WIDTH" -> config.elementWidth.toString,
      "SPMV_X_ELEMENTS_PER_PC" -> config.elementsPerPc.toString,
      "SPMV_X_READ_ELEMENTS_PER_CYCLE" -> config.readElementsPerCycle.toString,
      "SPMV_X_WRITE_ELEMENTS_PER_CYCLE" -> config.writeElementsPerCycle.toString,
      "SPMV_URAM_BANKS_PER_PC" -> config.uramBanksPerPc.toString,
      "SPMV_URAM_BANK_DEPTH" -> config.uramBankDepth.toString,
      "SPMV_PARALLEL_READ_LANES" -> config.readElementsPerCycle.toString,
      "SPMV_PARALLEL_WRITE_LANES" -> config.writeElementsPerCycle.toString,
      "SPMV_X_STORAGE" -> config.storage.name,
      "SPMV_BURST_BEATS" -> config.burstBeats.toString,
      "SPMV_BASE_ALIGNMENT_BYTES" -> config.baseAlignmentBytes.toString,
      "SPMV_OUTSTANDING_BURSTS_PER_PC" -> config.outstandingBurstsPerPc.toString,
      "SPMV_CLOCK_MHZ" -> config.clockMHz.toString
    )
    Files.createDirectories(directory)
    Files.writeString(
      directory.resolve("spmv-parameters.env"),
      values.sortBy(_._1).map { case (key, value) => s"$key=$value" }.mkString("\n") + "\n",
      StandardCharsets.US_ASCII
    )
  }
}
