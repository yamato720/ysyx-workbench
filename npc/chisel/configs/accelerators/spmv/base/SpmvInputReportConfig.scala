package accelerators.spmv

import org.chipsalliance.cde.config.{Config => CDEConfig, Field}

/** SPMV 输入仿真的 HTML 报告开关。
  *
  * 性能页是一次运行的报告主页；流水页是性能页下的逐周期子报告，因此不能单独启用。
  */
final case class SpmvInputReportConfig(
  performanceHtml: Boolean,
  pipelineHtml: Boolean
) {
  require(!pipelineHtml || performanceHtml,
    "SPMV 流水 HTML 必须在性能 HTML 主页之上启用")
}

object SpmvInputReportConfig {
  val Disabled: SpmvInputReportConfig = SpmvInputReportConfig(
    performanceHtml = false,
    pipelineHtml = false
  )

  val Performance: SpmvInputReportConfig = Disabled.copy(
    performanceHtml = true
  )

  val PerformancePipeline: SpmvInputReportConfig = Performance.copy(
    pipelineHtml = true
  )
}

case object SpmvInputReportConfigKey
    extends Field[SpmvInputReportConfig](SpmvInputReportConfig.Disabled)

class WithSpmvInputReportConfig(config: SpmvInputReportConfig)
    extends CDEConfig((_, _, _) => {
  case SpmvInputReportConfigKey => config
})
