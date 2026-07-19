package scpu.fpga.u55c

import org.chipsalliance.cde.config.Parameters
import scpu.fpga.{FpgaBoard, NpcFpgaShell}

/** Alveo RTL-kernel shell for a bare NPC core. */
class U55cNpcFpgaShell(implicit parameters: Parameters)
    extends NpcFpgaShell(FpgaBoard.U55c)
