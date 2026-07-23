package scpu

/** 由 FPGA 终端直接挂载的完整工具链与运行配方。 */
final case class FpgaToolchainConfig(
  device: FpgaDeviceConfig,
  flow: FpgaFlowConfig,
  reports: FpgaReportConfig,
  runtime: FpgaRuntimeConfig
) {
  require(device.fpgaType == "alveo" || flow.vitisXrtMode == "inherit",
    s"仅 Alveo Vitis 构造可改变 XRT 环境策略：${device.fpgaType}/${flow.vitisXrtMode}")
  require(runtime.notificationMode != "xrt-poll" || device.fpgaType == "alveo",
    s"xrt-poll 只支持 Alveo，实际为 ${device.fpgaType}")
  require(runtime.notificationMode != "ps-uio-irq" || device.fpgaType == "zynqmp",
    s"ps-uio-irq 只支持 ZynqMP，实际为 ${device.fpgaType}")
  require(device.board != "u55c" || device.fpgaType == "alveo",
    s"U55C 工具链必须使用 alveo 类型，实际为 ${device.fpgaType}")
  require(device.board != "zcu102" || device.fpgaType == "zynqmp",
    s"ZCU102 工具链必须使用 zynqmp 类型，实际为 ${device.fpgaType}")

  /** 保持现有 Make/Tcl 消费的 `FPGA_*` profile 字段。 */
  def profileValues: Seq[(String, String)] = {
    def bit(value: Boolean): String = if (value) "1" else "0"
    Seq(
      "FPGA_TYPE" -> device.fpgaType,
      "FPGA_PART" -> device.part,
      "FPGA_PLATFORM" -> device.platform,
      "FPGA_BOARD_PART" -> device.boardPart,
      "FPGA_VIVADO_VERSION" -> flow.vivadoVersion,
      "FPGA_VITIS_VERSION" -> flow.vitisVersion,
      "FPGA_VITIS_TARGET" -> flow.vitisTarget,
      "FPGA_VITIS_XRT_MODE" -> flow.vitisXrtMode,
      "FPGA_TIMING_WNS_MIN_NS" -> flow.timingWnsMinNs,
      "FPGA_VIVADO_SYNTH_JOBS" -> flow.synthesisParallelJobs.toString,
      "FPGA_VIVADO_IMPL_JOBS" -> flow.implementationParallelJobs.toString,
      "FPGA_VIVADO_IMPL_STRATEGY" -> flow.implementationStrategy,
      "FPGA_VIVADO_IMPL_STRATEGY_SEARCH" -> bit(flow.implementationStrategySearch),
      "FPGA_REPORT_TIMING_MAX_PATHS" -> reports.timingMaxPaths.toString,
      "FPGA_REPORT_TIMING_PATHS_PER_CLOCK" -> reports.timingPathsPerClock.toString,
      "FPGA_REPORT_CONGESTION" -> bit(reports.reportCongestion),
      "FPGA_REPORT_CLOCK_UTILIZATION" -> bit(reports.reportClockUtilization),
      "FPGA_REPORT_CONTROL_SETS" -> bit(reports.reportControlSets),
      "FPGA_REPORT_HIGH_FANOUT_NETS" -> bit(reports.reportHighFanoutNets),
      "FPGA_REPORT_METHODOLOGY" -> bit(reports.reportMethodology),
      "FPGA_REPORT_QOR_SUGGESTIONS" -> bit(reports.reportQorSuggestions),
      "FPGA_MEMORY_KIND" -> runtime.memoryKind,
      "FPGA_FLOATING_FALLBACK" -> runtime.floatingFallback,
      "FPGA_NOTIFICATION_MODE" -> runtime.notificationMode,
      "FPGA_PL_GIC_SPI" -> runtime.plGicSpi.toString
    )
  }
}

object FpgaToolchainConfig {
  /** U55C 的完整工具链基础配方。 */
  val U55cBase: FpgaToolchainConfig = FpgaToolchainConfig(
    device = FpgaDeviceConfig(
      board = "u55c",
      fpgaType = "alveo",
      part = "xcu55c-fsvh2892-2L-e",
      platform = "xilinx_u55c_gen3x16_xdma_3_202210_1",
      boardPart = ""
    ),
    flow = FpgaFlowConfig(
      vivadoVersion = "2022.2",
      vitisVersion = "2022.2",
      vitisTarget = "hw",
      vitisXrtMode = "unset",
      timingWnsMinNs = "0.000",
      implementationStrategy = "Performance_ExplorePostRoutePhysOpt",
      synthesisParallelJobs = 4,
      implementationParallelJobs = 8,
      implementationStrategySearch = false
    ),
    reports = FpgaReportConfig(
      timingMaxPaths = 50,
      timingPathsPerClock = 10,
      reportCongestion = true,
      reportClockUtilization = true,
      reportControlSets = true,
      reportHighFanoutNets = true,
      reportMethodology = true,
      reportQorSuggestions = true
    ),
    runtime = FpgaRuntimeConfig(
      memoryKind = "hbm",
      plGicSpi = 0,
      floatingFallback = "host-mailbox",
      notificationMode = "xrt-poll"
    )
  )

  /** ZCU102 的完整工具链基础配方。 */
  val Zcu102Base: FpgaToolchainConfig = FpgaToolchainConfig(
    device = FpgaDeviceConfig(
      board = "zcu102",
      fpgaType = "zynqmp",
      part = "xczu9eg-ffvb1156-2-i",
      platform = "",
      boardPart = "xilinx.com:zcu102:part0:3.4"
    ),
    flow = FpgaFlowConfig(
      vivadoVersion = "2022.2",
      vitisVersion = "none",
      vitisTarget = "none",
      vitisXrtMode = "inherit",
      timingWnsMinNs = "0.000",
      implementationStrategy = "Performance_ExplorePostRoutePhysOpt",
      synthesisParallelJobs = 4,
      implementationParallelJobs = 8,
      implementationStrategySearch = false
    ),
    reports = FpgaReportConfig(
      timingMaxPaths = 50,
      timingPathsPerClock = 10,
      reportCongestion = true,
      reportClockUtilization = true,
      reportControlSets = true,
      reportHighFanoutNets = true,
      reportMethodology = true,
      reportQorSuggestions = true
    ),
    runtime = FpgaRuntimeConfig(
      memoryKind = "ps-ddr",
      plGicSpi = 89,
      floatingFallback = "host-mailbox",
      notificationMode = "ps-uio-irq"
    )
  )
}
