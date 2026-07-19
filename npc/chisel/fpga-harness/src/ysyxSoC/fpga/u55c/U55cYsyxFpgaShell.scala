package ysyx.fpga.u55c

import org.chipsalliance.cde.config.Parameters
import _root_.scpu.fpga.FpgaBoard
import ysyx.fpga.YsyxSocFpgaShell

/** Alveo RTL-kernel shell. XRT/Vitis AXI naming remains in boards/u55c RTL. */
class U55cYsyxFpgaShell(implicit parameters: Parameters)
    extends YsyxSocFpgaShell(FpgaBoard.U55c)
