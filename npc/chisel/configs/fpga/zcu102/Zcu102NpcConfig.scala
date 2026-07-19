package scpu.fpga.zcu102

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.scpu.fpga.NpcFpgaCdeConfig
import _root_.scpu.MakeConstructionConfig

/** ZCU102 的裸 NPC 终端构造：板卡策略加 FPGA 默认核心。 */
class Zcu102NpcFpgaConfig extends CDEConfig(
  new Zcu102BoardConfig ++
    new NpcFpgaCdeConfig
) with MakeConstructionConfig {
  override val capability: String = "fpga-npc"
}
