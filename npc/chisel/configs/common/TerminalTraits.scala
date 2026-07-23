package scpu

/** 可由本地 NPC 终端直接挂载的完整运行 trait。 */
trait NpcTerminal extends NemuSimulationConstruction with MakeTerminal {
  final override val constructionScope: String = "npc"
  final override val constructionTarget: String = "NPC"
}

/** 可由本地 ysyxSoC 终端直接挂载的完整运行 trait。 */
trait SocTerminal extends NemuSimulationConstruction with MakeTerminal {
  final override val constructionScope: String = "soc"
  final override val constructionTarget: String = "SOC"
}

/** 可由 FPGA 裸 NPC 终端直接挂载的完整运行 trait。 */
trait FpgaNpcTerminal extends FpgaConstruction with MakeTerminal {
  final override val constructionScope: String = "fpga"
  final override val constructionTarget: String = "NPC"
}

/** 可由 FPGA ysyxSoC 终端直接挂载的完整运行 trait。 */
trait FpgaSocTerminal extends FpgaConstruction with MakeTerminal {
  final override val constructionScope: String = "fpga"
  final override val constructionTarget: String = "SOC"
}
