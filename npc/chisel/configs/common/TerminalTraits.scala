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

/** 不含 CPU/NEMU 的本地 SPMV 输入 smoke 终端。 */
trait LocalSpmvInputTerminal extends LocalSpmvInputTerminalCore {
  final override val constructionScope: String = "spmv"
  final override val constructionTarget: String = "SPMV"
}

/** U55C 裸 NPC 的完整终端预设。 */
trait U55cNpcTerminal extends U55cFpgaTerminalCore {
  final override val constructionScope: String = "fpga"
  final override val constructionTarget: String = "NPC"
}

/** U55C 裸 NPC 的批处理性能监测终端。 */
trait U55cNpcPerformanceMonitorTerminal extends U55cPerformanceMonitorFpgaTerminalCore {
  final override val constructionScope: String = "fpga"
  final override val constructionTarget: String = "NPC"
}

/** U55C SPMV 加速器的只综合终端，并提供独立软件 golden host。 */
trait U55cSpmvSynthesisTerminal extends U55cSpmvSynthesisTerminalCore {
  final override val constructionScope: String = "fpga"
  final override val constructionTarget: String = "SPMV"
}

/** U55C SPMV bitstream-only 终端；发布 XO/DCP/xclbin，并挂载独立软件 golden host。 */
trait U55cSpmvBitstreamTerminal extends U55cSpmvBitstreamTerminalCore {
  final override val constructionScope: String = "fpga"
  final override val constructionTarget: String = "SPMV"
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
