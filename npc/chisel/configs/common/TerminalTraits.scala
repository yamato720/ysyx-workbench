package npc

// 终端预设一步提供完整默认值；派生终端可重载配方，scope 与 target 保持固定。

/** 本地 NPC 的完整终端预设。 */
trait LocalNpcTerminal extends LocalNemuTerminalCore {
  final override val constructionScope: String = "npc"
  final override val constructionTarget: String = "NPC"
}

/** 本地 ysyxSoC 的完整终端预设。 */
trait LocalSocTerminal extends LocalNemuTerminalCore {
  final override val constructionScope: String = "soc"
  final override val constructionTarget: String = "SOC"
}

/** U55C 裸 NPC 的完整终端预设。 */
trait U55cNpcTerminal extends U55cFpgaTerminalCore {
  final override val constructionScope: String = "fpga"
  final override val constructionTarget: String = "NPC"
}

/** U55C 裸 NPC 的 v12 trace 报告终端预设。 */
trait U55cDebugNpcTerminal extends U55cRuntimeTraceFpgaTerminalCore {
  final override val constructionScope: String = "fpga"
  final override val constructionTarget: String = "NPC"
}

/** U55C ysyxSoC 的完整终端预设。 */
trait U55cSocTerminal extends U55cFpgaTerminalCore {
  final override val constructionScope: String = "fpga"
  final override val constructionTarget: String = "SOC"
}

/** ZCU102 裸 NPC 的完整终端预设。 */
trait Zcu102NpcTerminal extends Zcu102FpgaTerminalCore {
  final override val constructionScope: String = "fpga"
  final override val constructionTarget: String = "NPC"
}

/** ZCU102 ysyxSoC 的完整终端预设。 */
trait Zcu102SocTerminal extends Zcu102FpgaTerminalCore {
  final override val constructionScope: String = "fpga"
  final override val constructionTarget: String = "SOC"
}
