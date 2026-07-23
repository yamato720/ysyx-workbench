package scpu.fpga

import npc.ip.arithmetic.{ArithmeticIpProvider, SimulationIpComponents}
import scpu._

/** FPGA 构建选择的组件集合；共享流水线只通过中立接口调用这些端点。 */
object FpgaCoreComponents extends NpcCoreComponents {
  override val name: String = "fpga"
  override val arithmeticIp: ArithmeticIpProvider = SimulationIpComponents

  override def exposesArithmeticAssist(config: NpcConfig): Boolean = config.operators.routes.requiresHostFallback
  override def exposesDispatchControl(config: NpcConfig): Boolean = true
}
