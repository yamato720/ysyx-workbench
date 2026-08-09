package npc

/** 本地 NPC/SoC 终端直接包含的 NEMU 运行集群。 */
trait LocalNemuTerminalCore extends NemuSimulationConstruction with MakeTerminal {
  override protected def configuredNemu: NemuHostConfig = NemuHostConfig.LocalPipelineTrace
}

/** U55C 终端直接包含的 FPGA 运行集群。 */
trait U55cFpgaTerminalCore extends FpgaConstruction with MakeTerminal {
  override protected def configuredNemu: NemuHostConfig = NemuHostConfig.U55cBase
  override protected def configuredFpga: FpgaToolchainConfig = FpgaToolchainConfig.U55cBase
}

/** Batch-only U55C hardware performance-monitor construction. */
trait U55cPerformanceMonitorFpgaTerminalCore extends FpgaConstruction with MakeTerminal {
  override protected def configuredNemu: NemuHostConfig = NemuHostConfig.U55cPerformanceMonitor
  override protected def configuredFpga: FpgaToolchainConfig = FpgaToolchainConfig.U55cBase
  override protected def configuredCapability: String = "batch"
}

/** U55C SPMV 资源探针的只综合终端集群，并挂载独立软件 golden host。 */
trait U55cSpmvSynthesisTerminalCore
    extends FpgaSynthesisConstruction with AcceleratorHostConstruction with MakeTerminal {
  override protected def configuredFpga: FpgaToolchainConfig = FpgaToolchainConfig.U55cBase
  override protected def configuredAcceleratorHost: AcceleratorHostConfig = AcceleratorHostConfig.SpmvGolden
}

/** U55C SPMV bitstream 构造集群，FPGA 资产与软件 golden host 保持正交。 */
trait U55cSpmvBitstreamTerminalCore
    extends FpgaBitstreamConstruction with AcceleratorHostConstruction with MakeTerminal {
  override protected def configuredFpga: FpgaToolchainConfig = FpgaToolchainConfig.U55cBase
  override protected def configuredAcceleratorHost: AcceleratorHostConfig = AcceleratorHostConfig.SpmvGolden
}

/** Cuper 输入顶层 smoke 的本地 Verilator 终端集群。 */
trait LocalSpmvInputTerminalCore
    extends SpmvInputSimulationConstruction with MakeTerminal {
  override protected def configuredAcceleratorHost: AcceleratorHostConfig =
    AcceleratorHostConfig.SpmvInputSmoke
}

/** ZCU102 终端直接包含的 FPGA 运行集群。 */
trait Zcu102FpgaTerminalCore extends FpgaConstruction with MakeTerminal {
  override protected def configuredNemu: NemuHostConfig = NemuHostConfig.Zcu102Base
  override protected def configuredFpga: FpgaToolchainConfig = FpgaToolchainConfig.Zcu102Base
}
