package spmv

import org.chipsalliance.cde.config.Parameters
import npc.{SpmvConfigResolver, SpmvCsr5MulConfigKey}

/** 从完整终端 Config 生成独立 CSR5 仿真顶层。 */
object ElaborateSpmvOneHbmCsr5MulSimulation extends App {
  val (_, construction) = SpmvConfigResolver.resolve("")
  implicit val parameters: Parameters = construction
  val config = parameters(SpmvCsr5MulConfigKey).getOrElse(
    throw new IllegalArgumentException("SPMV 仿真终端缺少 SpmvCsr5MulConfigKey")
  )
  val output = sys.env.getOrElse("SPMV_ELABORATE_OUTPUT_DIR", "generated-spmv-simulation")
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new SpmvOneHbmCsr5MulSimulationTop(config),
    Array("--target-dir", output, "--split-verilog"),
    Array("--disable-annotation-unknown")
  )
}
