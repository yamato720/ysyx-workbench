package scpu.fpga

import scpu._

/** FPGA 构建选择的组件集合；共享流水线只通过中立接口调用这些端点。 */
object FpgaCoreComponents extends NpcCoreComponents {
  override val name: String = "fpga"
  override val arithmeticIp: npc.ip.arithmetic.ArithmeticIpProvider = CommonXilinxArithmeticIpComponents

  override def exposesArithmeticAssist(config: NpcConfig): Boolean = config.operators.routes.requiresHostFallback
  override def exposesDispatchControl(config: NpcConfig): Boolean = true

  def forBoard(board: FpgaBoard): NpcCoreComponents = board match {
    case FpgaBoard.U55c => new BoardFpgaCoreComponents("fpga-u55c", u55c.U55cIpComponents)
    case FpgaBoard.Zcu102 => new BoardFpgaCoreComponents("fpga-zcu102", zcu102.Zcu102IpComponents)
  }
}

private final class BoardFpgaCoreComponents(
  override val name: String,
  override val arithmeticIp: npc.ip.arithmetic.ArithmeticIpProvider
) extends NpcCoreComponents {
  override def exposesArithmeticAssist(config: NpcConfig): Boolean = config.operators.routes.requiresHostFallback
  override def exposesDispatchControl(config: NpcConfig): Boolean = true
}
