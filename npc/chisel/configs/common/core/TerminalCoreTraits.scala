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

/** ZCU102 终端直接包含的 FPGA 运行集群。 */
trait Zcu102FpgaTerminalCore extends FpgaConstruction with MakeTerminal {
  override protected def configuredNemu: NemuHostConfig = NemuHostConfig.Zcu102Base
  override protected def configuredFpga: FpgaToolchainConfig = FpgaToolchainConfig.Zcu102Base
}
