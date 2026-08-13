package fpga

import org.chipsalliance.cde.config.{Config => CDEConfig}

/** 固定 FPGA 平台地址和时钟。 */
class WithFpgaPlatformConfig(platform: FpgaPlatformSettings) extends CDEConfig((_, _, _) => {
  case FpgaPlatformSettingsKey => Some(platform)
})

/** 在 CDE 图中选择目标 FPGA 板卡。 */
class WithFpgaBoardConfig(board: FpgaBoard) extends CDEConfig((_, _, _) => {
  case FpgaBoardKey => Some(board)
})

/** 覆盖入口平台参数中的 FPGA 主时钟频率，单位为 MHz。 */
class WithFpgaClockMHzConfig(clockMHz: Int) extends CDEConfig((_, _, _) => {
  case FpgaClockMHzKey => Some(clockMHz)
})

/** Adds the fixed U55C performance-monitor ABI to a board CDE graph. */
class WithFpgaPerformanceMonitorConfig(monitor: FpgaPerformanceMonitorConfig) extends CDEConfig((_, _, _) => {
  case FpgaPerformanceMonitorConfigKey => monitor
})

/** Selects the synthesized interactive SDB mailbox path. */
class WithFpgaRuntimeSdbConfig(sdb: FpgaRuntimeSdbConfig) extends CDEConfig((_, _, _) => {
  case FpgaRuntimeSdbConfigKey => sdb
})
