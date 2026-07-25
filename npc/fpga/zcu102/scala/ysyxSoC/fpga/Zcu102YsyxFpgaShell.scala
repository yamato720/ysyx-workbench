package ysyx.fpga.zcu102

import org.chipsalliance.cde.config.Parameters
import _root_.npc.fpga.FpgaBoard
import ysyx.fpga.YsyxSocFpgaShell

/** ZynqMP-facing shell. PS/PL wiring is supplied by the ZCU102 board RTL and Tcl. */
class Zcu102YsyxFpgaShell(implicit parameters: Parameters)
    extends YsyxSocFpgaShell(FpgaBoard.Zcu102)
