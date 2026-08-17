package npc

/**
  * 启用独立的动态分支预测参数；流水线性能配方不参与选择。
  *
  * 动态模式对条件分支使用 2-bit 饱和计数器，JALR 使用已训练的 PC 目标表，return
  * 使用返回地址栈；尚未训练的条件分支回退到 BTFNT（Backward Taken, Forward Not Taken：
  * 后向目标预测跳转，前向目标预测不跳转）。
  */
class WithNpcBranchPredictorConfig(
  table: BranchPredictorTableConfig = BranchPredictorTableConfig()
) extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig = base.copy(
    branchPredictor = BranchPredictorParameters(
      enabled = true,
      table = table
    )
  )
}

/**
  * 选择静态控制流预测，保留 JAL 与后向条件分支的既有静态行为。
  *
  * 静态模式不分配 BHT、JALR 目标表或 RAS：JAL 直接按立即数跳转，条件分支采用
  * BTFNT（Backward Taken, Forward Not Taken：后向目标预测跳转，前向目标预测不跳转），
  * JALR 不做提前目标预测，最终由后端解析并重定向。
  */
class WithoutNpcBranchPredictorConfig extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig = base.copy(
    branchPredictor = BranchPredictorParameters()
  )
}

/** 动态分支预测的预测/实际 next-PC 硬件依赖。 */
class WithBpLogConfig extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(branchPredictor = base.branchPredictor.copy(bpLog = true))
}
