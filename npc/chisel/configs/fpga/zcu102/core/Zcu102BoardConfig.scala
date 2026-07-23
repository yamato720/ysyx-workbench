package scpu.fpga.zcu102

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.scpu.fpga.FpgaBoard
import _root_.scpu.fpga.FpgaPlatformSettings
import _root_.scpu.fpga.{WithFpgaBoardConfig, WithFpgaClockMHzConfig, WithFpgaPlatformConfig, WithXilinxFpgaOperatorRoutesConfig}
import _root_.scpu.OperatorIpTimingConfig

/** ZCU102 的物理板卡策略，供裸 NPC 和 SoC 终端构造共同复用。 */
class Zcu102BoardConfig extends CDEConfig(
  new WithXilinxFpgaOperatorRoutesConfig(OperatorIpTimingConfig.Default) ++
    new WithFpgaClockMHzConfig(300) ++
    new WithFpgaPlatformConfig(FpgaPlatformSettings(
      board = FpgaBoard.Zcu102,
      clockMHz = 300,
      memoryHostBase = 0x70000000L,
      controlBase = 0xa0000000L,
      mailboxBase = 0xa0010000L,
      dividerIpCycles = 34,
      dividerAdapterCycles = 3
    )) ++
    new WithFpgaBoardConfig(FpgaBoard.Zcu102)
)
