package scpu.fpga.zcu102

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.ysyx.YsyxSocFpgaConfig
import _root_.scpu.MakeConstructionConfig

/** ZCU102 的 ysyxSoC FPGA 终端构造。
  *
  * 左侧的 ZCU102 FPGA NPC 覆盖通用 SoC 的默认外部 AXI NPC，SoC 基础和板卡策略均被保留。
  */
class Zcu102YsyxSocFpgaConfig extends CDEConfig(
  new Zcu102NpcFpgaConfig ++
    new YsyxSocFpgaConfig
) with MakeConstructionConfig {
  override val capability: String = "fpga-soc"
}
