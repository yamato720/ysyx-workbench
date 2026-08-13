package npc.fpga.zcu102

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.fpga.zcu102.Zcu102BoardConfig
import _root_.npc.{FpgaConfig, FpgaIpTerminal, Zcu102NpcTerminal}

/** ZCU102 裸 NPC 的可运行终端构造。 */

/** ZCU102 的裸 NPC 终端构造：板卡策略加 FPGA 默认核心。 */
class Zcu102NpcFpgaConfig extends CDEConfig(
  new Zcu102BoardConfig ++
    new FpgaConfig
) with Zcu102NpcTerminal with FpgaIpTerminal
