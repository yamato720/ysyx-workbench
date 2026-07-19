package scpu.fpga.u55c

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.scpu.fpga.FpgaBoard
import _root_.scpu.fpga.{FpgaBuildSettings, FpgaPlatformSettings, FpgaToolSettings}
import _root_.scpu.fpga.{WithFpgaBoardConfig, WithFpgaBuildSettingsConfig, WithFpgaClockMHzConfig, WithFpgaPlatformConfig, WithFpgaToolConfig}

/** U55C 的物理板卡策略，供裸 NPC 和 SoC 终端构造共同复用。 */
class U55cBoardConfig extends CDEConfig(
  new WithFpgaClockMHzConfig(300) ++
    new WithFpgaBuildSettingsConfig(FpgaBuildSettings(
      synthesisParallelJobs = 4,
      implementationParallelJobs = 8,
      implementationStrategySearch = false
    )) ++
    new WithFpgaToolConfig(FpgaToolSettings(
      fpgaType = "alveo",
      part = "xcu55c-fsvh2892-2L-e",
      platform = "xilinx_u55c_gen3x16_xdma_3_202210_1",
      boardPart = "",
      vivadoVersion = "2022.2",
      vitisVersion = "2022.2",
      vitisTarget = "hw",
      timingWnsMinNs = "0.000",
      implementationStrategy = "Performance_ExplorePostRoutePhysOpt",
      memoryKind = "hbm",
      floatingFallback = "host-mailbox"
    )) ++
    new WithFpgaPlatformConfig(FpgaPlatformSettings(
      board = FpgaBoard.U55c,
      clockMHz = 300,
      memoryHostBase = 0x00000000L,
      controlBase = 0xa0000000L,
      mailboxBase = 0xa0010000L,
      dividerIpCycles = 34,
      dividerAdapterCycles = 3
    )) ++
    new WithFpgaBoardConfig(FpgaBoard.U55c)
)
