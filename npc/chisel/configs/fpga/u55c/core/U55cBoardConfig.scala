package npc.fpga.u55c

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.npc.fpga.FpgaBoard
import _root_.npc.fpga.FpgaPlatformSettings
import _root_.npc.fpga.{WithFpgaBoardConfig, WithFpgaClockMHzConfig, WithFpgaPlatformConfig}
import _root_.npc.{FpgaIpAttachment, OperatorIpTimingConfig, WithFpgaIpAttachmentConfig, XilinxIntegerIpAttachment}

/** U55C 复用的 Xilinx 整数 IP attachment；板卡 core 可在此基础上覆盖算子时序。 */
object U55cXilinxIpAttachment {
  def apply(timing: OperatorIpTimingConfig = OperatorIpTimingConfig.Default): XilinxIntegerIpAttachment =
    XilinxIntegerIpAttachment(
      name = "xilinx-u55c",
      arithmeticIp = U55cIpComponents,
      timing = timing,
      dividerIpCycles = 34,
      dividerAdapterCycles = 3
    )
}

/** U55C 的物理板卡策略，供裸 NPC 和 SoC 终端构造共同复用。 */
class U55cBoardConfig(
  clockMHz: Int = 125,
  ipAttachment: FpgaIpAttachment = U55cXilinxIpAttachment()
) extends CDEConfig(
  new WithFpgaIpAttachmentConfig(ipAttachment) ++
    new WithFpgaClockMHzConfig(clockMHz) ++
    new WithFpgaPlatformConfig(FpgaPlatformSettings(
      board = FpgaBoard.U55c,
      clockMHz = clockMHz,
      memoryHostBase = 0x00000000L,
      controlBase = 0xa0000000L,
      mailboxBase = 0xa0010000L
    )) ++
    new WithFpgaBoardConfig(FpgaBoard.U55c)
)

/** U55C 的 250 MHz 板卡策略。
  *
  * RV64 乘法器改用五级流水切分 DSP 组合链；其余 IP 时序保持 U55C 默认值，乘除法
  * 均维持 II=1。
  */
class U55c250MHzBoardConfig extends U55cBoardConfig(
  clockMHz = 250,
  ipAttachment = U55cXilinxIpAttachment(
    OperatorIpTimingConfig.Default.copy(
      multiply = OperatorIpTimingConfig.Default.multiply.copy(latency = 5)
    )
  )
)
