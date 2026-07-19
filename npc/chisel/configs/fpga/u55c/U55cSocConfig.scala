package scpu.fpga.u55c

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.ysyx.YsyxSocFpgaConfig
import _root_.scpu.MakeConstructionConfig

/** U55C 的 ysyxSoC FPGA 终端构造。
  *
  * 左侧的 U55C FPGA NPC 覆盖通用 SoC 的默认外部 AXI NPC，SoC 基础和板卡策略均被保留。
  */
class U55cYsyxSocFpgaConfig extends CDEConfig(
  new U55cNpcFpgaConfig ++
    new YsyxSocFpgaConfig
) with MakeConstructionConfig {
  override val capability: String = "fpga-soc"
}
