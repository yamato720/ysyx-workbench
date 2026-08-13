package ysyx.fpga.u55c

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.fpga.u55c.U55cBoardConfig
import _root_.npc.{CacheFpgaConfig, FpgaConfig, FpgaIpTerminal, U55cSocTerminal}
import _root_.ysyx.YsyxElaborateConfig

/** U55C 的 ysyxSoC FPGA 终端构造。 */
class U55cYsyxSocFpgaConfig extends CDEConfig(
  new U55cBoardConfig ++
    new FpgaConfig ++
    new YsyxElaborateConfig
) with U55cSocTerminal with FpgaIpTerminal

/** U55C ysyxSoC 的显式缓存终端。 */
class U55cCacheYsyxSocFpgaConfig extends CDEConfig(
  new U55cBoardConfig ++
    new CacheFpgaConfig ++
    new YsyxElaborateConfig
) with U55cSocTerminal with FpgaIpTerminal
