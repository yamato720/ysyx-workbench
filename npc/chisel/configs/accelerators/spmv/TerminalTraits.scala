package accelerators.spmv

import npc.{
  AcceleratorHostConstruction,
  FpgaBitstreamConstruction,
  FpgaSynthesisConstruction,
  FpgaToolchainConfig,
  MakeTerminal
}

/** 不含 CPU/NEMU 的本地 SPMV 正式输入和流水报告终端。 */
trait LocalSpmvInputTerminal extends SpmvInputSimulationConstruction with MakeTerminal {
  override protected def configuredAcceleratorHost: npc.AcceleratorHostConfig =
    SpmvAcceleratorHostConfig.InputReport
  override protected def configuredSpmvInputReport: SpmvInputReportConfig =
    SpmvInputReportConfig.PerformancePipeline
  final override val constructionScope: String = "spmv"
  final override val constructionTarget: String = "SPMV"
}

/** U55C SPMV 加速器的只综合终端，并提供独立软件 golden host。 */
trait U55cSpmvSynthesisTerminal
    extends FpgaSynthesisConstruction with AcceleratorHostConstruction with MakeTerminal {
  override protected def configuredFpga: FpgaToolchainConfig = FpgaToolchainConfig.U55cBase
  override protected def configuredAcceleratorHost: npc.AcceleratorHostConfig =
    SpmvAcceleratorHostConfig.Golden
  final override val constructionScope: String = "fpga"
  final override val constructionTarget: String = "SPMV"
}

/** U55C SPMV bitstream-only 终端；发布 XO/DCP/xclbin，并挂载独立软件 golden host。 */
trait U55cSpmvBitstreamTerminal
    extends FpgaBitstreamConstruction with AcceleratorHostConstruction with MakeTerminal {
  override protected def configuredFpga: FpgaToolchainConfig = FpgaToolchainConfig.U55cBase
  override protected def configuredAcceleratorHost: npc.AcceleratorHostConfig =
    SpmvAcceleratorHostConfig.Golden
  final override val constructionScope: String = "fpga"
  final override val constructionTarget: String = "SPMV"
}

/** U55C 上板输入/乘法终端；由独立 XRT host 驱动，不挂载 CPU/NEMU。 */
trait U55cSpmvInputRuntimeTerminal
    extends SpmvInputFpgaRuntimeConstruction with MakeTerminal {
  override protected def configuredFpga: FpgaToolchainConfig = FpgaToolchainConfig.U55cBase
  override protected def configuredAcceleratorHost: npc.AcceleratorHostConfig =
    SpmvAcceleratorHostConfig.InputU55cRuntime
  final override val constructionScope: String = "fpga"
  final override val constructionTarget: String = "SPMV"
}
