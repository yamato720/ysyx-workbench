package accelerators.spmv

import org.chipsalliance.cde.config.{Config => CDEConfig}

/** Cuper 16-HBM 输入和完整 HTML 报告的本地仿真核心配置。 */
class Cuper16HbmInputSimulationCoreConfig extends CDEConfig(
  new WithSpmvInputReportConfig(SpmvInputReportConfig.PerformancePipeline) ++
  new WithSpmvInputConfig(SpmvInputConfig.Cuper16Hbm)
)
