package scpu

/** FPGA 器件与平台选择。 */
final case class FpgaDeviceConfig(
  board: String,
  fpgaType: String,
  part: String,
  platform: String,
  boardPart: String
) {
  require(Set("u55c", "zcu102").contains(board), s"不支持的 FPGA 板卡：$board")
  require(Set("zynqmp", "alveo").contains(fpgaType), s"不支持的 FPGA 类型：$fpgaType")
  require(part.nonEmpty, "FPGA part 不能为空")
}

/** Vivado/Vitis 版本、时序目标和实现流程策略。 */
final case class FpgaFlowConfig(
  vivadoVersion: String,
  vitisVersion: String,
  vitisTarget: String,
  vitisXrtMode: String,
  timingWnsMinNs: String,
  implementationStrategy: String,
  synthesisParallelJobs: Int,
  implementationParallelJobs: Int,
  implementationStrategySearch: Boolean
) {
  require(vivadoVersion.nonEmpty, "Vivado 版本不能为空")
  require(Set("inherit", "unset").contains(vitisXrtMode),
    s"不支持的 Vitis XRT 环境策略：$vitisXrtMode")
  require(implementationStrategy.matches("[A-Za-z0-9_]+"),
    s"FPGA 实现策略含非法字符：$implementationStrategy")
  require(synthesisParallelJobs > 0 && implementationParallelJobs > 0,
    "FPGA 构造并行任务数必须为正数")
  require(timingWnsMinNs.matches("-?[0-9]+(?:\\.[0-9]+)?"),
    s"FPGA WNS 阈值不是合法数值：$timingWnsMinNs")
}

/** 实现完成后生成的 Vivado 诊断报告策略。 */
final case class FpgaReportConfig(
  timingMaxPaths: Int,
  timingPathsPerClock: Int,
  reportCongestion: Boolean,
  reportClockUtilization: Boolean,
  reportControlSets: Boolean,
  reportHighFanoutNets: Boolean,
  reportMethodology: Boolean,
  reportQorSuggestions: Boolean
) {
  require(timingMaxPaths > 0, s"时序报告最大路径数必须为正数：$timingMaxPaths")
  require(timingPathsPerClock > 0, s"每时钟时序路径数必须为正数：$timingPathsPerClock")
}

/** FPGA 运行期内存、mailbox 浮点回退和主机通知 ABI。 */
final case class FpgaRuntimeConfig(
  memoryKind: String,
  plGicSpi: Int,
  floatingFallback: String,
  notificationMode: String
) {
  require(memoryKind.nonEmpty, "FPGA memory kind 不能为空")
  require(plGicSpi >= 0, s"FPGA PL GIC SPI 不能为负数：$plGicSpi")
  require(floatingFallback == "host-mailbox",
    s"不支持的 FPGA 浮点回退策略：$floatingFallback（当前仅支持 host-mailbox）")
  require(Set("ps-uio-irq", "xrt-poll", "host-poll").contains(notificationMode),
    s"不支持的 FPGA 主机通知模式：$notificationMode")
  require(notificationMode != "ps-uio-irq" || plGicSpi > 0,
    "ps-uio-irq 通知模式必须提供正数 PL GIC SPI")
}
