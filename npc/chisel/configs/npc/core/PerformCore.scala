package npc

/** 已完成的 NPC 微架构性能成品。
  *
  * 这里是流水线、互锁、前递和执行级策略的组合层。与 ISA、总线、
  * 存储窗口和算术后端无关，因而可由所有上层核心形态复用。
  * 性能配方同时声明各级驻留/停顿信号；引出由 `++ TraceConfig` /
  * `++ FinalLogConfig` 决定。
  */
abstract class PerformBundle(layers: ConfigFragment) extends ConfigBundle(layers)

/** 流水线的驻留/停顿/提交采样硬件依赖。 */
class PipelineLogConfig extends ConfigBundle(
  new WithPipelineLogConfig
)

/** 无流水线性能基线。 */
class ScalarPerformConfig extends PerformBundle(
  new PipelineLogConfig ++
    new BasePerformConfig
)

/** 流水线开启，但 ID/EX 前递均关闭。 */
class PipelinePerformConfig extends PerformBundle(
  new PipelineLogConfig ++
    new WithPipelineConfig ++
    new BasePerformConfig
)

/** 流水线开启，仅启用 ID 前递。 */
class PipelineIdFwdPerformConfig extends PerformBundle(
  new PipelineLogConfig ++
    new WithNpcIdForwardingConfig ++
    new WithPipelineConfig ++
    new BasePerformConfig
)

/** 流水线开启，仅启用 EX 前递。 */
class PipelineExFwdPerformConfig extends PerformBundle(
  new PipelineLogConfig ++
    new WithNpcExecuteForwardingConfig ++
    new WithPipelineConfig ++
    new BasePerformConfig
)

/** 流水线开启，并启用 ID/EX 两条前递路径。 */
class PipelineDualFwdPerformConfig extends PerformBundle(
  new PipelineLogConfig ++
    new WithNpcExecuteForwardingConfig ++
    new WithNpcIdForwardingConfig ++
    new WithPipelineConfig ++
    new BasePerformConfig
)

/** 流水线开启、双前递，并将普通整数执行拆成 EX0/EX1 两拍。 */
class PipelineDualFwdTwoStageIntegerExecutePerformConfig extends PerformBundle(
  new WithTwoStageIntegerExecuteConfig ++
    new PipelineDualFwdPerformConfig
)

/** 流水线开启、双前递、两拍普通整数执行，并寄存首个取指请求。 */
class PipelineDualFwdTwoStageIntegerExecuteRegisteredFetchPerformConfig extends PerformBundle(
  new WithRegisteredInitialFetchRequestConfig ++
    new PipelineDualFwdTwoStageIntegerExecutePerformConfig
)

/** 流水线开启、双前递、两拍普通整数执行、寄存首个取指请求，且切断串行结果的 ID 回送。 */
class PipelineDualFwdTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluPerformConfig extends PerformBundle(
  new WithoutSerialExecuteResultForwardingConfig ++
  new WithSeparateSerialIntegerAluConfig ++
    new PipelineDualFwdTwoStageIntegerExecuteRegisteredFetchPerformConfig
)

/** 流水线开启、双前递、普通整数两拍且串行控制三拍，并开启全部 300 MHz 时序切分。 */
class PipelineDualFwdTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecutePerformConfig
  extends PerformBundle(
    new WithSerialExecuteAdditionalStagesConfig(2) ++
      new PipelineDualFwdTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluPerformConfig
  )

/** 本地缓存性能路径：普通整数保持单拍 EX，串行控制和首个外部取指仍保留时序切分。 */
class PipelineDualFwdOneStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecutePerformConfig
  extends PerformBundle(
    new WithSerialExecuteAdditionalStagesConfig(2) ++
      new WithoutSerialExecuteResultForwardingConfig ++
      new WithSeparateSerialIntegerAluConfig ++
      new WithRegisteredInitialFetchRequestConfig ++
      new PipelineDualFwdPerformConfig
  )

/** 本地缓存热路径：普通整数在 MEM 空闲时从 EX 直接锁入 WB，省去空 EX/MEM 驻留。 */
class PipelineDualFwdOneStageIntegerExecuteDirectWritebackRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecutePerformConfig
  extends PerformBundle(
    new WithDirectIntegerWritebackBypassConfig ++
      new PipelineDualFwdOneStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecutePerformConfig
  )
