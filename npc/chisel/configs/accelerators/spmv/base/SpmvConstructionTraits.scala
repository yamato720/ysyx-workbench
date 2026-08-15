package accelerators.spmv

import npc.AcceleratorHostConfig
import npc.AcceleratorHostConstruction

/** SPMV 自有的软件宿主预设，不向 NPC 公共构造层泄漏加速器策略。 */
object SpmvAcceleratorHostConfig {
  val Golden: AcceleratorHostConfig = AcceleratorHostConfig(
    kind = "spmv",
    abi = "spmv-golden-v1"
  )

  val InputReport: AcceleratorHostConfig = AcceleratorHostConfig(
    kind = "spmv",
    abi = "spmv-input-report-v12"
  )
}

/** 独立于 NEMU 的本地 SPMV 正式输入与流水报告构造。 */
trait SpmvInputSimulationConstruction extends AcceleratorHostConstruction {
  final override protected def configuredCapability: String = "run"
}
