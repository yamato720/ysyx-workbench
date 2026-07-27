package npc.fpga

import npc._

/** FPGA 构建选择的组件集合；共享流水线只通过中立接口调用这些端点。 */
object FpgaCoreComponents {
  def forAttachment(attachment: FpgaIpAttachment): NpcCoreComponents =
    new AttachedFpgaCoreComponents(attachment)
}

private final class AttachedFpgaCoreComponents(attachment: FpgaIpAttachment) extends NpcCoreComponents {
  override val name: String = attachment.name
  override val arithmeticIp: npc.ip.arithmetic.ArithmeticIpProvider = attachment.arithmeticIp
  override def exposesDispatchControl(config: NpcConfig): Boolean = true
}
