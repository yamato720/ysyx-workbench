package scpu

/** NPC 性能参数的起点：无流水线、互锁开启、ID/EX 前递关闭。 */
class BasePerformConfig extends ConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig = base.copy(
    pipeline = PipelineConfig(
      enablePipeline = false,
      enableInterlock = true,
      forwarding = ForwardingConfig(
        enableIdForwarding = false,
        enableExecuteForwarding = false
      )
    )
  )
}

/** 在既有性能基础上启用流水线；互锁和前递由其他片段决定。 */
class WithPipelineConfig extends ConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(pipeline = base.pipeline.copy(enablePipeline = true))
}

/** 显式关闭流水线。 */
class WithoutPipelineConfig extends ConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(pipeline = base.pipeline.copy(enablePipeline = false))
}

/** 启用流水线互锁。 */
class WithInterlockConfig extends ConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(pipeline = base.pipeline.copy(enableInterlock = true))
}

/** 关闭流水线互锁，仅用于结构或时序实验。 */
class WithoutInterlockConfig extends ConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(pipeline = base.pipeline.copy(enableInterlock = false))
}

/** 启用 ID 阶段前递。 */
class WithNpcIdForwardingConfig extends ConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig = base.copy(
    pipeline = base.pipeline.copy(forwarding = base.pipeline.forwarding.copy(enableIdForwarding = true))
  )
}

/** 关闭 ID 阶段前递。 */
class WithoutNpcIdForwardingConfig extends ConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig = base.copy(
    pipeline = base.pipeline.copy(forwarding = base.pipeline.forwarding.copy(enableIdForwarding = false))
  )
}

/** 启用 EX 阶段前递。 */
class WithNpcExecuteForwardingConfig extends ConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig = base.copy(
    pipeline = base.pipeline.copy(forwarding = base.pipeline.forwarding.copy(enableExecuteForwarding = true))
  )
}

/** 关闭 EX 阶段前递。 */
class WithoutNpcExecuteForwardingConfig extends ConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig = base.copy(
    pipeline = base.pipeline.copy(forwarding = base.pipeline.forwarding.copy(enableExecuteForwarding = false))
  )
}
