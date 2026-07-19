package scpu.fpga.zcu102

import org.chipsalliance.cde.config.Parameters
import scpu.fpga.{FpgaBoard, NpcFpgaShell}

/** ZynqMP shell for a bare NPC core. Board-level PS/PL wiring stays in FPGA RTL. */
class Zcu102NpcFpgaShell(implicit parameters: Parameters)
    extends NpcFpgaShell(FpgaBoard.Zcu102)
