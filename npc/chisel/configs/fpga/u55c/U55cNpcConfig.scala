package scpu.fpga.u55c

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.scpu.fpga.{NpcFpgaCdeConfig, WithNpcCoreConfig}
import _root_.scpu.MakeConstructionConfig

/** U55C 的裸 NPC 终端构造：板卡策略加 FPGA 默认核心。 */
class U55cNpcFpgaConfig extends CDEConfig(
  new U55cBoardConfig ++
    new NpcFpgaCdeConfig
) with MakeConstructionConfig {
  override val capability: String = "fpga-npc"
}

/** U55C 的 RV64IMF_Zicsr 裸 NPC 终端构造。
  *
  * ID/EX 前递保留在核心；PL 只实现无舍入浮点操作，其余 F 操作经 mailbox 外部
  * 中断通知 NEMU，由宿主 SoftFloat 服务后以同一序号响应完成。
  */
class U55cFullIsa64NpcFpgaConfig extends CDEConfig(
  new U55cBoardConfig ++
    new WithNpcCoreConfig(new _root_.scpu.NpcFullIsa64PipelineDualForwardingFpgaConfig().config) ++
    new NpcFpgaCdeConfig
) with MakeConstructionConfig {
  override val capability: String = "fpga-npc"
}
