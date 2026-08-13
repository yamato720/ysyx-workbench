package accelerators.spmv

import org.chipsalliance.cde.config.{Config => CDEConfig}
/** 驱动 Cuper A/X 正式输入并生成流水报告的本地 Verilator 构造。 */
class SpmvInputSimulationConfig extends CDEConfig(
  new Cuper16HbmInputSimulationCoreConfig
) with LocalSpmvInputTerminal
