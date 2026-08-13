package ysyx.fpga.zcu102

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.fpga.zcu102.Zcu102BoardConfig
import _root_.npc.{FpgaConfig, FpgaIpTerminal, Zcu102SocTerminal}
import _root_.ysyx.YsyxElaborateConfig

/** ZCU102 的 ysyxSoC FPGA 终端构造。 */
class Zcu102YsyxSocFpgaConfig extends CDEConfig(
  new Zcu102BoardConfig ++
    new FpgaConfig ++
    new YsyxElaborateConfig
) with Zcu102SocTerminal with FpgaIpTerminal
