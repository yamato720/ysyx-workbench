package scpu.fpga.zcu102

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.scpu.fpga.FpgaBoard
import _root_.scpu.fpga.{FpgaBuildSettings, FpgaPlatformSettings, FpgaToolSettings}
import _root_.scpu.fpga.{WithFpgaBoardConfig, WithFpgaBuildSettingsConfig, WithFpgaClockMHzConfig, WithFpgaPlatformConfig, WithFpgaToolConfig}

/** ZCU102 的物理板卡策略，供裸 NPC 和 SoC 终端构造共同复用。 */
class Zcu102BoardConfig extends CDEConfig(
  new WithFpgaClockMHzConfig(300) ++
    new WithFpgaBuildSettingsConfig(FpgaBuildSettings(
      synthesisParallelJobs = 4,
      implementationParallelJobs = 8,
      implementationStrategySearch = false
    )) ++
    new WithFpgaToolConfig(FpgaToolSettings(
      fpgaType = "zynqmp",
      part = "xczu9eg-ffvb1156-2-i",
      platform = "",
      boardPart = "xilinx.com:zcu102:part0:3.4",
      vivadoVersion = "2022.2",
      vitisVersion = "none",
      vitisTarget = "none",
      timingWnsMinNs = "0.000",
      implementationStrategy = "Performance_ExplorePostRoutePhysOpt",
      memoryKind = "ps-ddr",
      plGicSpi = 89,
      floatingFallback = "host-mailbox"
    )) ++
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
