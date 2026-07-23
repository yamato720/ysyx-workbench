package scpu

/** 已完成的 NPC 微架构性能成品。
  *
  * 这里是流水线、互锁、前递、未来分支预测和乱序策略的组合层。与 ISA、总线、
  * 存储窗口和算术后端无关，因而可由所有上层核心形态复用。
  */
abstract class PerformBundle(layers: ConfigFragment) extends ConfigBundle(layers)

/** 无流水线性能基线。 */
class ScalarPerformConfig extends PerformBundle(
  new BasePerformConfig
)

/** 流水线开启，但 ID/EX 前递均关闭。 */
class PipelinePerformConfig extends PerformBundle(
  new WithPipelineConfig ++
    new BasePerformConfig
)

/** 流水线开启，仅启用 ID 前递。 */
class PipelineIdFwdPerformConfig extends PerformBundle(
  new WithNpcIdForwardingConfig ++
    new WithPipelineConfig ++
    new BasePerformConfig
)

/** 流水线开启，仅启用 EX 前递。 */
class PipelineExFwdPerformConfig extends PerformBundle(
  new WithNpcExecuteForwardingConfig ++
    new WithPipelineConfig ++
    new BasePerformConfig
)

/** 流水线开启，并启用 ID/EX 两条前递路径。 */
class PipelineDualFwdPerformConfig extends PerformBundle(
  new WithNpcExecuteForwardingConfig ++
    new WithNpcIdForwardingConfig ++
    new WithPipelineConfig ++
    new BasePerformConfig
)
