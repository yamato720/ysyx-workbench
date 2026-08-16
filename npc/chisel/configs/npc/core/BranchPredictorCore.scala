package npc

/** NPC 动态分支预测属性。
  *
  * 该配方启用 BHT、JALR 目标表和返回地址栈；只有支持流水取指的核心会实例化对应状态。
  * 是否采用该配方由最终 terminal 显式决定，基础性能配置不再隐式打开它。
  */
class BranchPredictorConfig extends ConfigBundle(
  new WithNpcBranchPredictorConfig
)
