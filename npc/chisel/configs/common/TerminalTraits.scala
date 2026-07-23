package scpu

/** 本地 NPC 的完整终端预设。 */
trait LocalNpcTerminal extends NemuSimulationConstruction with MakeTerminal {
  final override protected def configuredNemu: NemuHostConfig = NemuHostConfig.LocalPipelineTrace
  final override val constructionScope: String = "npc"
  final override val constructionTarget: String = "NPC"
}

/** 本地 ysyxSoC 的完整终端预设。 */
trait LocalSocTerminal extends NemuSimulationConstruction with MakeTerminal {
  final override protected def configuredNemu: NemuHostConfig = NemuHostConfig.LocalPipelineTrace
  final override val constructionScope: String = "soc"
  final override val constructionTarget: String = "SOC"
}

/** U55C 裸 NPC 的完整终端预设。 */
trait U55cNpcTerminal extends FpgaConstruction with MakeTerminal {
  final override protected def configuredNemu: NemuHostConfig = NemuHostConfig.U55cBase
  final override protected def configuredFpga: FpgaToolchainConfig = FpgaToolchainConfig.U55cBase
  final override val constructionScope: String = "fpga"
  final override val constructionTarget: String = "NPC"
}

/** U55C ysyxSoC 的完整终端预设。 */
trait U55cSocTerminal extends FpgaConstruction with MakeTerminal {
  final override protected def configuredNemu: NemuHostConfig = NemuHostConfig.U55cBase
  final override protected def configuredFpga: FpgaToolchainConfig = FpgaToolchainConfig.U55cBase
  final override val constructionScope: String = "fpga"
  final override val constructionTarget: String = "SOC"
}

/** ZCU102 裸 NPC 的完整终端预设。 */
trait Zcu102NpcTerminal extends FpgaConstruction with MakeTerminal {
  final override protected def configuredNemu: NemuHostConfig = NemuHostConfig.Zcu102Base
  final override protected def configuredFpga: FpgaToolchainConfig = FpgaToolchainConfig.Zcu102Base
  final override val constructionScope: String = "fpga"
  final override val constructionTarget: String = "NPC"
}

/** ZCU102 ysyxSoC 的完整终端预设。 */
trait Zcu102SocTerminal extends FpgaConstruction with MakeTerminal {
  final override protected def configuredNemu: NemuHostConfig = NemuHostConfig.Zcu102Base
  final override protected def configuredFpga: FpgaToolchainConfig = FpgaToolchainConfig.Zcu102Base
  final override val constructionScope: String = "fpga"
  final override val constructionTarget: String = "SOC"
}
