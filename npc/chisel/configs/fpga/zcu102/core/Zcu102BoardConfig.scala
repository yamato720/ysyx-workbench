package npc.fpga.zcu102

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.npc.fpga.FpgaBoard
import _root_.npc.fpga.FpgaPlatformSettings
import _root_.npc.fpga.{WithFpgaBoardConfig, WithFpgaClockMHzConfig, WithFpgaPlatformConfig}
import _root_.npc.{OperatorIpTimingConfig, WithFpgaIpAttachmentConfig, XilinxIntegerIpAttachment}

/** ZCU102 复用的 Xilinx 整数 IP attachment。 */
object Zcu102XilinxIpAttachment {
  def apply(timing: OperatorIpTimingConfig = OperatorIpTimingConfig.Default): XilinxIntegerIpAttachment =
    XilinxIntegerIpAttachment(
      name = "xilinx-zcu102",
      arithmeticIp = Zcu102IpComponents,
      timing = timing,
      dividerIpCycles = 34,
      dividerAdapterCycles = 3
    )
}

/** ZCU102 的物理板卡策略，供裸 NPC 和 SoC 终端构造共同复用。 */
class Zcu102BoardConfig extends CDEConfig(
  new WithFpgaIpAttachmentConfig(Zcu102XilinxIpAttachment()) ++
    new WithFpgaClockMHzConfig(300) ++
    new WithFpgaPlatformConfig(FpgaPlatformSettings(
      board = FpgaBoard.Zcu102,
      clockMHz = 300,
      platformClockMHz = 300,
      memoryHostBase = 0x70000000L,
      controlBase = 0xa0000000L,
      mailboxBase = 0xa0010000L
    )) ++
    new WithFpgaBoardConfig(FpgaBoard.Zcu102)
)
