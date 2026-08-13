package accelerators.spmv

/** 不含 CPU/NEMU 的本地 SPMV 正式输入和流水报告终端。 */
trait LocalSpmvInputTerminal extends LocalSpmvInputTerminalCore {
  final override val constructionScope: String = "spmv"
  final override val constructionTarget: String = "SPMV"
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
