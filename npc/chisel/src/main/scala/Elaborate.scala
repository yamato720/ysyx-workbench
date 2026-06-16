package scpu
import chisel3._
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

private object ElaborateOutput {
  private val blackBoxFileListMarker = """// ----- 8< ----- FILE "firrtl_black_box_resource_files.f" ----- 8< -----"""

  def stripBlackBoxFileList(path: String): Unit = {
    val file = Path.of(path)
    if (Files.exists(file)) {
      val content = Files.readString(file, StandardCharsets.UTF_8)
      val markerIndex = content.indexOf(blackBoxFileListMarker)
      if (markerIndex >= 0) {
        Files.writeString(file, content.substring(0, markerIndex).stripTrailing + "\n", StandardCharsets.UTF_8)
      }
    }
  }
}

private object ElaborateConfig {
  private def parseXlen(raw: String): Int =
    try {
      raw.toInt
    } catch {
      case _: NumberFormatException =>
        sys.error(s"Invalid npc.xlen/NPC_XLEN value: $raw")
    }

  val xlen: Int = {
    val value = sys.props.get("npc.xlen").orElse(sys.env.get("NPC_XLEN")).map(parseXlen).getOrElse(64)
    require(value == 32 || value == 64, s"NPC XLEN must be 32 or 64, got $value")
    value
  }

  def config(m: Boolean = false): ISAConfig = ISAConfig(xlen = xlen, M = m)
}


object Elaborate extends App {
  val cfg = ElaborateConfig.config()
  println(s"正在生成Verilog 文件... XLEN=${cfg.xlen}")
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new CPU(Debug = true, cfg = cfg),
    Array(
      "--target-dir", "./generated"
    ),
    Array("--disable-annotation-unknown")
  )
  ElaborateOutput.stripBlackBoxFileList("./generated/CPU.sv")
  println("生成完成！")
}

// DPI-C mode for Verilator simulation with external memory
object ElaborateDPI extends App {
  val cfg = ElaborateConfig.config(m = true)
  println(s"正在生成 DPI-C 模式的 Verilog 文件... XLEN=${cfg.xlen}")
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new CPU(Debug = true, useDPI = true, cfg = cfg),
    Array(
      "--target-dir", "./generated-dpi"
    ),
    Array("--disable-annotation-unknown")
  )
  ElaborateOutput.stripBlackBoxFileList("./generated-dpi/CPU.sv")
  println("DPI-C 模式 Verilog 生成完成！")
}
