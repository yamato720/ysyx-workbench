package accelerators.spmv

import npc.{
  AcceleratorHostConstruction,
  FpgaBitstreamConstruction,
  FpgaSynthesisConstruction,
  FpgaToolchainConfig,
  MakeTerminal
}

/** U55C SPMV 资源探针的只综合终端集群，并挂载独立软件 golden host。 */
trait U55cSpmvSynthesisTerminalCore
    extends FpgaSynthesisConstruction with AcceleratorHostConstruction with MakeTerminal {
  override protected def configuredFpga: FpgaToolchainConfig = FpgaToolchainConfig.U55cBase
  override protected def configuredAcceleratorHost: npc.AcceleratorHostConfig =
    SpmvAcceleratorHostConfig.Golden
}

/** U55C SPMV bitstream 构造集群，FPGA 资产与软件 golden host 保持正交。 */
trait U55cSpmvBitstreamTerminalCore
    extends FpgaBitstreamConstruction with AcceleratorHostConstruction with MakeTerminal {
  override protected def configuredFpga: FpgaToolchainConfig = FpgaToolchainConfig.U55cBase
  override protected def configuredAcceleratorHost: npc.AcceleratorHostConfig =
    SpmvAcceleratorHostConfig.Golden
}

/** Cuper 正式输入和流水报告的本地 Verilator 终端集群。 */
trait LocalSpmvInputTerminalCore
    extends SpmvInputSimulationConstruction with MakeTerminal {
  override protected def configuredAcceleratorHost: npc.AcceleratorHostConfig =
    SpmvAcceleratorHostConfig.InputReport
}
