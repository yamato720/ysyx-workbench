package npc

/** NPC 动态分支预测属性。
  *
  * 该配方显式选择 32 项方向/JALR 目标表和 8 项返回地址栈：条件分支使用 2-bit 饱和
  * 计数器，JALR 使用已训练的 PC 目标，return 使用 RAS，冷启动回退到 BTFNT（Backward
  * Taken, Forward Not Taken：后向目标预测跳转，前向目标预测不跳转）。只有支持
  * 流水取指的核心会实例化对应状态；是否采用该配方由最终 terminal 显式决定，基础性能配置
  * 不再隐式打开它。动态预测同时声明分支观测信号；引出由 `++ TraceConfig` /
  * `++ FinalLogConfig` 决定。
  */
/** 动态分支预测的预测/实际 next-PC 硬件依赖。 */
class BpLogConfig extends ConfigBundle(
  new WithBpLogConfig
)

class BranchPredictorConfig extends ConfigBundle(
  new BpLogConfig ++
    new WithNpcBranchPredictorConfig(
      BranchPredictorTableConfig(entries = 32, returnEntries = 8)
    )
)

/** NPC 静态控制流预测属性；不依赖任何流水线性能配方。
  * JAL 直接计算立即数目标，条件分支采用 BTFNT（Backward Taken, Forward Not Taken：
  * 后向目标预测跳转，前向目标预测不跳转），JALR 等后端解析，不配置预测表状态。
  */
class StaticBranchPredictorConfig extends ConfigBundle(
  new WithoutNpcBranchPredictorConfig
)
