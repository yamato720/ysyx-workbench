package npc.fpga.zcu102

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.fpga.zcu102.Zcu102BoardConfig
import _root_.npc.{BaseConfig, ConstructionConfig, FpgaIpTerminal, FpgaNpcIntegrationConfig,
  PipelineDualFwdPerformConfig, Rv32IMZicsrConfig, Zcu102NpcTerminal}

/** ZCU102 裸 NPC 的可运行终端构造。 */

/** ZCU102 的裸 NPC 终端构造：板卡策略加 FPGA 默认核心。 */
class Zcu102NpcFpgaConfig extends CDEConfig(
  new Zcu102BoardConfig ++
    new ConstructionConfig(
      new Rv32IMZicsrConfig ++
        new PipelineDualFwdPerformConfig ++
        new FpgaNpcIntegrationConfig ++
        new BaseConfig
    )
) with Zcu102NpcTerminal with FpgaIpTerminal
