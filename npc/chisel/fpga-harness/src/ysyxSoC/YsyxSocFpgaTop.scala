package ysyx

import org.chipsalliance.cde.config.Parameters
import scpu.fpga.FpgaBoard
import ysyx.fpga.YsyxSocFpgaShell

/** Compatibility entry point for older direct Scala callers; it selects ZCU102. */
class ysyxSoCFpgaTop(implicit parameters: Parameters)
    extends YsyxSocFpgaShell(FpgaBoard.Zcu102)
