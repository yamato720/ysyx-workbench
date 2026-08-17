package npc

/** 终端级观测导出成品。
  *
  * 域内 HTML 硬件依赖在对应 core 里 `++`：`InstructionLogConfig`、
  * `PipelineLogConfig`、`CacheLogConfig`、`BpLogConfig`。
  * 终端只叠加 SDB 与结束日志；`TraceConfig` 暂时保留给 U55C v13 HBM 通路。
  */
abstract class DebugBundle(layers: ConfigFragment) extends ConfigBundle(layers)

/** 逐提交观测：按已挂载的 arch/cache/pipeline/branch 信号导出采样引脚。 */
class TraceConfig extends DebugBundle(
  new WithTraceConfig
)

/** NEMU SDB 互动：导出停核、单步和架构快照引脚。 */
class SdbDebugConfig extends DebugBundle(
  new WithSdbDebugConfig
)

/** 结束日志：导出运行结束时的聚合计数引脚。 */
class FinalLogConfig extends DebugBundle(
  new WithFinalLogConfig
)
