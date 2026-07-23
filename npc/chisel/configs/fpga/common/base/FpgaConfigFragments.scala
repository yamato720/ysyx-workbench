package scpu.fpga

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.scpu.{NpcCoreConfigKey, OperatorIpTimingConfig, OperatorRouteConfig}

/** 固定 FPGA 平台地址和 IP 适配时序。 */
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

/** 用板级 CDE 片段覆盖 L1 本地模型路由；左侧 Config 始终优先。 */
class WithFpgaOperatorRoutesConfig(routes: OperatorRouteConfig) extends CDEConfig((_, _, up) => {
  case NpcCoreConfigKey =>
    val core = up(NpcCoreConfigKey)
    core.copy(operators = core.operators.copy(routes = core.operators.routes.overlay(routes)))
})

/** 根据已完成的 L1 核心 XLEN 选择 Xilinx 整数 IP 路由。
  *
  * 位宽属于 NPC/SoC 核心 ABI，板卡只选择 Xilinx 路由能力。`up` 读取右侧完整
  * 核心后，RV32 与 RV64 会分别得到匹配的 IP 操作数宽度。
  */
class WithXilinxFpgaOperatorRoutesConfig(timing: OperatorIpTimingConfig) extends CDEConfig((_, _, up) => {
  case NpcCoreConfigKey =>
    val core = up(NpcCoreConfigKey)
    val routes = FpgaOperatorRoutes.xilinx(core.isa.xlen, timing)
    core.copy(operators = core.operators.copy(routes = core.operators.routes.overlay(routes)))
})
