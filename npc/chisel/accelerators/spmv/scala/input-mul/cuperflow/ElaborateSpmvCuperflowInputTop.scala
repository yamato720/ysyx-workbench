package accelerators.spmv.inputmul.cuperflow

import _root_.circt.stage.ChiselStage
import accelerators.spmv.SpmvCuperflowConfig

/** 独立生成 Cuperflow 严格预取输入模型的 SystemVerilog。
  *
  * 该入口故意不挂入旧 Cuper transaction host：每条 HBM 自己解析 1-beat map，
  * 再顺序装 X、连读 A。host 只负责装填 HBM 并拉 start。
  */
object ElaborateSpmvCuperflowInputTop extends App {
  private val config = SpmvCuperflowConfig.Simulation
  private val output = sys.env.get("SPMV_CUPERFLOW_ELABORATE_OUTPUT_DIR")
    .orElse(sys.env.get("SPMV_ELABORATE_OUTPUT_DIR"))
    .map(_.trim)
    .filter(_.nonEmpty)
    .getOrElse("generated-spmv-cuperflow-input")

  println(
    s"正在生成 Cuperflow 输入顶层... PC=${config.hbmPcCount}, " +
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
