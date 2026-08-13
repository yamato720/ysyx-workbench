package accelerators.spmv.fpga.u55c

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.fpga.{
  FpgaBoard,
  FpgaPlatformSettings,
  WithFpgaBoardConfig,
  WithFpgaClockMHzConfig,
  WithFpgaPlatformConfig
}
import _root_.fpga.u55c.U55cBoardConfig

/** SPMV 资源探针使用的 U55C 板卡、地址与 kernel 时钟策略。 */
class U55cSpmvBoardConfig(
  coreClockMHz: Int = U55cBoardConfig.PlatformDataClockMHz
) extends CDEConfig(
  new WithFpgaClockMHzConfig(U55cBoardConfig.checkedCoreClockMHz(coreClockMHz)) ++
    new WithFpgaPlatformConfig(FpgaPlatformSettings(
      board = FpgaBoard.U55c,
      clockMHz = U55cBoardConfig.checkedCoreClockMHz(coreClockMHz),
      platformClockMHz = U55cBoardConfig.PlatformDataClockMHz,
      memoryHostBase = 0x00000000L,
      controlBase = 0x00000000L,
      mailboxBase = 0x00000000L
    )) ++
    new WithFpgaBoardConfig(FpgaBoard.U55c)
)
