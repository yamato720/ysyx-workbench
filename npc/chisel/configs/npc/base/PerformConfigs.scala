package npc

/** NPC 性能参数的起点：无流水线、互锁开启、ID/EX 前递关闭。 */
class BasePerformConfig extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig = base.copy(
    pipeline = PipelineConfig(
      enablePipeline = false,
      enableInterlock = true,
      forwarding = ForwardingConfig(
        enableIdForwarding = false,
        enableExecuteForwarding = false
      ),
      integerExecuteStages = 1,
      serialExecuteStages = 1,
      registerInitialFetchRequest = false,
      separateSerialIntegerAlu = false,
      serialExecuteResultForwarding = true
    )
  )
}

/** 在既有性能基础上启用流水线；互锁和前递由其他片段决定。 */
class WithPipelineConfig extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(pipeline = base.pipeline.copy(enablePipeline = true))
}

/** 显式关闭流水线。 */
class WithoutPipelineConfig extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(pipeline = base.pipeline.copy(enablePipeline = false))
}

/** 启用流水线互锁。 */
class WithInterlockConfig extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(pipeline = base.pipeline.copy(enableInterlock = true))
}

/** 关闭流水线互锁，仅用于结构或时序实验。 */
class WithoutInterlockConfig extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(pipeline = base.pipeline.copy(enableInterlock = false))
}

/** 启用 ID 阶段前递。 */
class WithNpcIdForwardingConfig extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig = base.copy(
    pipeline = base.pipeline.copy(forwarding = base.pipeline.forwarding.copy(enableIdForwarding = true))
  )
}

/** 关闭 ID 阶段前递。 */
class WithoutNpcIdForwardingConfig extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig = base.copy(
    pipeline = base.pipeline.copy(forwarding = base.pipeline.forwarding.copy(enableIdForwarding = false))
  )
}

/** 启用 EX 阶段前递。 */
class WithNpcExecuteForwardingConfig extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig = base.copy(
    pipeline = base.pipeline.copy(forwarding = base.pipeline.forwarding.copy(enableExecuteForwarding = true))
  )
}

/** 关闭 EX 阶段前递。 */
class WithoutNpcExecuteForwardingConfig extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig = base.copy(
    pipeline = base.pipeline.copy(forwarding = base.pipeline.forwarding.copy(enableExecuteForwarding = false))
  )
}

/** 将普通整数执行路径固定为 ID/EX -> EX0 -> EX/MEM 两拍。 */
class WithTwoStageIntegerExecuteConfig extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig = base.copy(
    pipeline = base.pipeline.copy(integerExecuteStages = 2)
  )
}

/** 在基线串行执行后额外插入 1 或 2 个寄存器级。
  *
  * `1` 将结果级寄存为总两拍；`2` 再寄存请求控制字，形成总三拍并
  * 切断 executeState 到宽结果选择网络的组合路径。
  */
class WithSerialExecuteAdditionalStagesConfig(additionalStages: Int) extends ConfigFragment {
  require(additionalStages == 1 || additionalStages == 2,
    s"serial execute additionalStages must be 1 or 2, got $additionalStages")

  override private[npc] def applyTo(base: NpcConfig): NpcConfig = base.copy(
    pipeline = base.pipeline.copy(serialExecuteStages = additionalStages + 1)
  )
}

/** 将空闲态的首个取指 AXI 请求寄存器化，切断 PC 到外部 AR 的组合路径。 */
class WithRegisteredInitialFetchRequestConfig extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig = base.copy(
    pipeline = base.pipeline.copy(registerInitialFetchRequest = true)
  )
}

/** 为 CSR、异常和 mret 的串行控制路径使用独立整数 ALU。 */
class WithSeparateSerialIntegerAluConfig extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig = base.copy(
    pipeline = base.pipeline.copy(separateSerialIntegerAlu = true)
  )
}

/** 关闭串行执行完成结果直接回送 ID 的组合前递，保留互锁直到 EX/MEM 可前递。 */
class WithoutSerialExecuteResultForwardingConfig extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig = base.copy(
    pipeline = base.pipeline.copy(serialExecuteResultForwarding = false)
  )
}
