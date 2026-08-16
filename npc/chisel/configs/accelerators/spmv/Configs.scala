package accelerators.spmv

import org.chipsalliance.cde.config.{Config => CDEConfig}

/** 驱动 Cuper A/X 正式输入并生成流水报告的本地 Verilator 构造。 */
class SpmvInputSimulationConfig extends CDEConfig(
  new WithSpmvInputReportConfig(SpmvInputReportConfig.PerformancePipeline) ++
    new WithSpmvInputConfig(SpmvInputConfig.Cuper16Hbm)
) with LocalSpmvInputTerminal
