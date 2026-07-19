package scpu
import chisel3._
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

private[scpu] object ElaborateOutput {
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

object Elaborate extends App {
  val (entry, construction) = NpcConfigResolver.resolve("NpcStandaloneConfig")
  val config = construction.config
  println(s"正在生成 Verilog 文件... Config=${entry.className}, XLEN=${config.isa.xlen}, pipeline=${config.pipeline.enablePipeline}")
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new NpcCore(config = config),
    Array(
      "--target-dir", "./generated"
    ),
    Array("--disable-annotation-unknown")
  )
  ElaborateOutput.stripBlackBoxFileList("./generated/CPU.sv")
  println("生成完成！")
}

// 使用外部内存的 Verilator 仿真 DPI-C 模式。
object ElaborateDPI extends App {
  val (entry, construction) = NpcConfigResolver.resolve("NpcDpiConfig")
  val config = construction.config
  println(s"正在生成 NEMU 模式的 Verilog 文件... Config=${entry.className}, XLEN=${config.isa.xlen}, M 扩展后端=Chisel")
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new NpcCore(config = config),
    Array(
      "--target-dir", "./generated-dpi"
    ),
    Array("--disable-annotation-unknown")
  )
  ElaborateOutput.stripBlackBoxFileList("./generated-dpi/CPU.sv")
  println("NEMU 模式 Verilog 生成完成！")
}

/** 仅供测试使用的 NEMU 生成入口，显式启用流水线。
  *
  * 正式的 Elaborate/ElaborateDPI 仍使用 PipelineConfig 默认值；保留此入口，
  * 使验证能构造可选配置而无需新增环境变量或 Make 开关。
  */
object ElaboratePipelineDPI extends App {
  val (entry, construction) = NpcConfigResolver.resolve("NpcPipelineDpiConfig")
  val config = construction.config
  println(s"正在生成流水线 NEMU Verilog 文件... Config=${entry.className}, XLEN=${config.isa.xlen}")
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new NpcCore(config = config),
    Array("--target-dir", "./generated-pipeline-dpi"),
    Array("--disable-annotation-unknown")
  )
  ElaborateOutput.stripBlackBoxFileList("./generated-pipeline-dpi/CPU.sv")
  println("流水线 NEMU Verilog 生成完成！")
}

/** 可选弹性流水线的轻量结构检查。
  *
  * 此入口刻意不选择 Make/环境开关：产品生成仍使用 PipelineConfig 的保守默认值。
  * 它是供 CI 和本地验证两个 XLEN 的直接 Scala 入口。
  */
object ElaboratePipelineChecks extends App {
  private def check(xlen: Int): Unit = {
    val config = (
      new WithNpcXlenConfig(xlen) ++
        new NpcPipelineCheckConfig
    ).build
    println(s"正在检查流水线 elaboration... XLEN=$xlen")
    _root_.circt.stage.ChiselStage.emitSystemVerilog(new NpcCore(config = config))
  }

  check(32)
  check(64)
  println("流水线 elaboration 检查完成！")
}

/** 单精度 DPI 时序路径的结构检查。
  *
  * 对两个 XLEN 变体启用弹性流水线，确保 FPR、RAW 冒险检测、RV64 NaN-boxing
  * 写回和仅供 Verilator 的 SoftFloat 外壳都被生成。
  */
object ElaborateFloatingChecks extends App {
  private def check(xlen: Int): Unit = {
    val config = (
      new WithNpcXlenConfig(xlen) ++
        new NpcFloatingCheckConfig
    ).build
    println(s"正在检查浮点 DPI elaboration... XLEN=$xlen")
    _root_.circt.stage.ChiselStage.emitSystemVerilog(new NpcCore(config = config))
  }

  check(32)
  check(64)
  println("浮点 DPI elaboration 检查完成！")
}

/** 可配置内建 DIV/REM 时序约定的结构检查。 */
object ElaborateMulDivAluChecks extends App {
  private def check(xlen: Int, completionCycles: Int): Unit = {
    val config = (
      new WithNpcXlenConfig(xlen) ++
        new WithMulDivCompletionConfig(completionCycles) ++
        new NpcMulDivCheckConfig
    ).build
    println(s"正在检查 MulDivAlu elaboration... XLEN=$xlen, cycles=$completionCycles")
    _root_.circt.stage.ChiselStage.emitCHIRRTL(new NpcCore(config = config))
  }

  check(32, 1)
  check(32, 4)
  check(32, 8)
  check(64, 8)
  println("MulDivAlu latency elaboration 检查完成！")
}
