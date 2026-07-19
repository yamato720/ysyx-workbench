package ysyx

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.scpu.fpga.NpcExternalAxiCdeConfig
import _root_.scpu.MakeConstructionConfig

/** 通用 ysyxSoC：默认外部 AXI NPC 加 Rocket 基础配置。
  * 板卡 SoC 构造可在左侧覆盖其默认 NPC。
  */
class YsyxSocConfig extends CDEConfig(
  new NpcExternalAxiCdeConfig ++
    new BaseYsyxConfig
)

/** 独立 ysyxSoC 构造，选择非 FPGA、非 DPI 平台。 */
class YsyxStandaloneConfig extends CDEConfig(
  new WithYsyxPlatformConfig(YsyxPlatform.Standalone) ++
    new YsyxSocConfig
) with MakeConstructionConfig {
  override val capability: String = "elaborate-only"
}

/** ysyxSoC 仿真构造，启用仿真平台行为。 */
class YsyxSimulationConfig extends CDEConfig(
  new WithYsyxPlatformConfig(YsyxPlatform.Simulation) ++
    new YsyxSocConfig
) with MakeConstructionConfig {
  override val capability: String = "verilator-soc"
}

/** 通用 ysyxSoC FPGA 构造；板卡 Config 可在左侧覆盖其默认 NPC。 */
class YsyxSocFpgaConfig extends CDEConfig(
  new WithYsyxPlatformConfig(YsyxPlatform.Fpga) ++
    new YsyxSocConfig
)
