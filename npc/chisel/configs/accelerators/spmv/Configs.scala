package accelerators.spmv

import org.chipsalliance.cde.config.{Config => CDEConfig}

/** 驱动 Cuper A/X 正式输入并生成流水报告的本地 Verilator 构造。 */
class SpmvInputSimulationConfig extends CDEConfig(
  new WithSpmvInputConfig(SpmvInputConfig.Cuper16Hbm)
) with LocalSpmvInputTerminal

/** 端口安全的 X/A pingpong 提前发射构造。 */
class SpmvInputPingPongSimulationConfig extends CDEConfig(
  new WithSpmvInputConfig(SpmvInputConfig.Cuper16HbmPingPong)
) with LocalSpmvInputTerminal
