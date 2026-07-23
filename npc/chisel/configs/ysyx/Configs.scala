package ysyx

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.scpu.{NemuHostConfig, NemuSimulationConstructionConfig, SocTerminalConfig}

/** ysyxSoC 仿真构造，使用默认的 Simulation 平台行为。 */
class YsyxSimulationConfig extends CDEConfig(
  new YsyxSocConfig
) with NemuSimulationConstructionConfig with SocTerminalConfig {
  override protected val configuredNemu: NemuHostConfig = NemuHostConfig.LocalPipelineTrace
}
