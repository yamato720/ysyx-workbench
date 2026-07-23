package scpu.fpga.u55c

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.scpu.fpga.FpgaBoard
import _root_.scpu.fpga.FpgaPlatformSettings
import _root_.scpu.fpga.{WithFpgaBoardConfig, WithFpgaClockMHzConfig, WithFpgaPlatformConfig, WithXilinxFpgaOperatorRoutesConfig}
import _root_.scpu.OperatorIpTimingConfig

/** U55C 的物理板卡策略，供裸 NPC 和 SoC 终端构造共同复用。 */
class U55cBoardConfig(clockMHz: Int = 125) extends CDEConfig(
  new WithXilinxFpgaOperatorRoutesConfig(OperatorIpTimingConfig.Default) ++
    new WithFpgaClockMHzConfig(clockMHz) ++
    new WithFpgaPlatformConfig(FpgaPlatformSettings(
      board = FpgaBoard.U55c,
      clockMHz = clockMHz,
      memoryHostBase = 0x00000000L,
      controlBase = 0xa0000000L,
      mailboxBase = 0xa0010000L,
      dividerIpCycles = 34,
      dividerAdapterCycles = 3
    )) ++
    new WithFpgaBoardConfig(FpgaBoard.U55c)
)
