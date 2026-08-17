package npc

/** 所有生成目标共享的 ISA 选择。
  *
  * `instructionLog` 是逐指令详情（提交 PC/指令字）的硬件依赖，由架构配方
  * `++ new InstructionLogConfig` 打开。
  */
case class ISAConfig(
  xlen: Int = 64,
  M: Boolean = false,
  Zicsr: Boolean = true,
  Zifencei: Boolean = false,
  instructionLog: Boolean = false
)

/** 乘除法单元的实现与时序参数。 */
case class OperatorConfig(
  mulDiv: MulDivAlu.Config = MulDivAlu.Config(),
  routes: OperatorRouteConfig = OperatorRouteConfig()
)
