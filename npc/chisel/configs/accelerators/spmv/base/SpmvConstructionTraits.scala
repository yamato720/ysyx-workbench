package accelerators.spmv

import npc.{AcceleratorHostConfig, AcceleratorHostConstruction, Construction, FpgaToolchainConstruction}

/** SPMV 自有的软件宿主预设，不向 NPC 公共构造层泄漏加速器策略。 */
object SpmvAcceleratorHostConfig {
  val Golden: AcceleratorHostConfig = AcceleratorHostConfig(
    kind = "spmv",
    abi = "spmv-golden-v1"
  )

  val InputReport: AcceleratorHostConfig = AcceleratorHostConfig(
    kind = "spmv",
    abi = "spmv-input-report-v13"
  )

  val InputU55cRuntime: AcceleratorHostConfig = AcceleratorHostConfig(
    kind = "spmv",
    abi = "spmv-input-u55c-runtime-v1"
  )

  val CuperflowRtl: AcceleratorHostConfig = AcceleratorHostConfig(
    kind = "spmv",
    abi = "spmv-cuperflow-rtl-v3"
  )

  val CuperflowFpga: AcceleratorHostConfig = AcceleratorHostConfig(
    kind = "spmv",
    abi = "spmv-cuperflow-u55c-v3"
  )
}

/** 独立于 NEMU 的本地 SPMV 正式输入与流水报告构造。 */
trait SpmvInputSimulationConstruction extends AcceleratorHostConstruction {
  final override protected def configuredCapability: String = "run"
  protected def configuredSpmvInputReport: SpmvInputReportConfig

  final def spmvInputReportConfig: SpmvInputReportConfig = configuredSpmvInputReport
}

/** Cuperflow 独立 RTL/Verilator 仿真构造。 */
trait SpmvCuperflowSimulationConstruction extends AcceleratorHostConstruction {
  final override protected def configuredCapability: String = "run"
  protected def configuredSpmvInputReport: SpmvInputReportConfig

  final def spmvInputReportConfig: SpmvInputReportConfig = configuredSpmvInputReport
}

/** 独立 U55C 输入/乘法运行构造：生成 Vitis 资产与 XRT host，不进入 NEMU。 */
trait SpmvInputFpgaRuntimeConstruction
    extends Construction with FpgaToolchainConstruction with AcceleratorHostConstruction {
  final override protected def configuredCapability: String = "run"
}
