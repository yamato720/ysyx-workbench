package accelerator.spmv

import _root_.circt.stage.ChiselStage
import org.chipsalliance.cde.config.{Config => CDEConfig, Parameters}
import npc.{ConfigCatalog, SpmvInputConfigKey}

/** 按当前 NPC Config 选择 SPMV 输入参数并生成真实的输入层顶层。 */
object ElaborateSpmvInputTop extends App {
  private val requested = ConfigCatalog.selectedName("")
  private val entry = ConfigCatalog.resolve(requested, Set("spmv"))
  private val construction = try {
    Class.forName(entry.className).getDeclaredConstructor().newInstance().asInstanceOf[CDEConfig]
  } catch {
    case error: ReflectiveOperationException =>
      throw new IllegalArgumentException(
        s"无法构造 SPMV 配置 ${entry.className}：${error.getMessage}", error)
  }

  implicit private val parameters: Parameters = construction
  private val input = parameters(SpmvInputConfigKey).getOrElse(
    throw new IllegalArgumentException(s"${entry.className} 缺少 SpmvInputConfigKey")
  )
  private val output = sys.env.get("SPMV_ELABORATE_OUTPUT_DIR")
    .map(_.trim)
    .filter(_.nonEmpty)
    .getOrElse("generated-spmv-input")

  println(
    s"正在生成 SPMV 输入顶层... Config=${entry.className}, " +
      s"A readers=${input.aReaderCount}, X readers=${input.xReaderCount}, " +
      s"HBM channels=${input.hbmChannelCount}"
  )
  ChiselStage.emitSystemVerilogFile(
    new SpmvInputTop(input),
    Array("--target-dir", output, "--split-verilog"),
    Array("--disable-annotation-unknown")
  )
  println(s"SPMV 输入顶层生成完成：$output/SpmvInputTop.sv")
}
