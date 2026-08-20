package accelerators.spmv.inputmul.cuperflow

import _root_.circt.stage.ChiselStage
import accelerators.spmv.SpmvCuperflowConfigKey
import org.chipsalliance.cde.config.{Config => CDEConfig, Parameters}
import npc.ConfigCatalog

/** 独立生成 Cuperflow 严格预取输入模型的 SystemVerilog。
  *
  * 该入口故意不挂入旧 Cuper transaction host：每条 HBM 自己解析 1-beat map，
  * 再顺序装 X、连读 A。host 只负责装填 HBM 并拉 start。
  */
object ElaborateSpmvCuperflowInputTop extends App {
  private val entry = ConfigCatalog.resolve(ConfigCatalog.selectedName("SpmvCuperflowSimulationConfig"),
    Set("spmv"))
  private val construction = try {
    Class.forName(entry.className).getDeclaredConstructor().newInstance().asInstanceOf[CDEConfig]
  } catch {
    case error: ReflectiveOperationException =>
      throw new IllegalArgumentException(s"无法构造 Cuperflow 配置 ${entry.className}：${error.getMessage}",
        error)
  }
  private implicit val parameters: Parameters = construction
  private val config = parameters(SpmvCuperflowConfigKey).getOrElse(
    throw new IllegalArgumentException(s"${entry.className} 缺少 SpmvCuperflowConfigKey")
  )
  private val output = sys.env.get("SPMV_CUPERFLOW_ELABORATE_OUTPUT_DIR")
    .orElse(sys.env.get("SPMV_ELABORATE_OUTPUT_DIR"))
    .map(_.trim)
    .filter(_.nonEmpty)
    .getOrElse("generated-spmv-cuperflow-input")

  println(
    s"正在生成 Cuperflow 输入顶层：Config=${entry.className}, PC=${config.hbmPcCount}, " +
      s"X/A 分区=${config.xRegionBytes}/${config.aRegionBytes} bytes, " +
      s"local_X=${config.xBankCount}x${config.xReplicaCount}"
  )
  ChiselStage.emitSystemVerilogFile(
    new SpmvCuperflowInputTop(config),
    Array("--target-dir", output, "--split-verilog"),
    Array("--disable-annotation-unknown")
  )
  println(s"Cuperflow 输入顶层生成完成：$output/SpmvCuperflowInputTop.sv")
}
