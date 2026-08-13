package npc.fpga

import npc._

/** FPGA 构建选择的组件集合；共享流水线只通过中立接口调用这些端点。 */
object FpgaCoreComponents {
  def forAttachment(attachment: FpgaIpAttachment): NpcCoreComponents =
    NpcCoreComponents.externalArithmetic(attachment.name, attachment.arithmeticIp)
}
