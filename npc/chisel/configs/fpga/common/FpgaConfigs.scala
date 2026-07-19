package scpu.fpga

import org.chipsalliance.cde.config.{Config => CDEConfig, Field, Parameters}
import _root_.scpu.NpcConfig
import _root_.scpu.fpga.FpgaBuildSettings

/** 裸 NPC 和 ysyxSoC FPGA 生成共享的 CDE 键。
  *
  * 核心仍是无依赖的 `NpcConfig`；本文件只负责将完成的核心构造接入 FPGA CDE 图。
  */
case object NpcCoreConfigKey extends Field[NpcConfig](NpcConfig())
case object FpgaPlatformSettingsKey extends Field[Option[FpgaPlatformSettings]](None)
case object FpgaToolSettingsKey extends Field[Option[FpgaToolSettings]](None)
case object FpgaBuildSettingsKey extends Field[Option[FpgaBuildSettings]](None)
case object FpgaBoardKey extends Field[Option[FpgaBoard]](None)
case object FpgaClockMHzKey extends Field[Option[Int]](None)

object FpgaConfigParameters {
  /** 取得当前 CDE 图选择的已完成 NPC 参数。 */
  def npcCoreConfig(implicit parameters: Parameters): NpcConfig = parameters(NpcCoreConfigKey)

  /** 取得 FPGA 平台参数。
    *
    * 物理板卡和频率属于终端 CDE Config；入口设置只提供地址、IP 时序等平台输入。
    */
  def platform(implicit parameters: Parameters): FpgaPlatformSettings = {
    val board = parameters(FpgaBoardKey).getOrElse(
      throw new IllegalArgumentException("FPGA CDE configuration requires WithFpgaBoardConfig")
    )
    parameters(FpgaPlatformSettingsKey).map { settings =>
      settings.copy(board = board, clockMHz = parameters(FpgaClockMHzKey).getOrElse(settings.clockMHz))
    }.getOrElse(
      throw new IllegalArgumentException("FPGA CDE configuration requires WithFpgaPlatformConfig")
    )
  }

  /** 取得终端板卡 Config 固定的实现工具策略。 */
  def tools(implicit parameters: Parameters): FpgaToolSettings =
    parameters(FpgaToolSettingsKey).getOrElse(
      throw new IllegalArgumentException("FPGA CDE configuration requires WithFpgaToolConfig")
    )

  /** 取得终端板卡 Config 固定的宿主构造并行度和策略搜索开关。 */
  def build(implicit parameters: Parameters): FpgaBuildSettings =
    parameters(FpgaBuildSettingsKey).getOrElse(
      throw new IllegalArgumentException("FPGA CDE configuration requires WithFpgaBuildSettingsConfig")
    )

  /** 取得当前 CDE 图绑定的板卡；未绑定时仅允许非板级调用者继续处理。 */
  def board(implicit parameters: Parameters): Option[FpgaBoard] = parameters(FpgaBoardKey)
}

/** 用一个已完成的 NPC 参数覆盖 CDE 图中的默认核心。应放在链的最左侧。 */
class WithNpcCoreConfig(npcConfig: NpcConfig) extends CDEConfig((_, _, _) => {
  case NpcCoreConfigKey => npcConfig
})

/** 裸核 FPGA 的固定核心策略。 */
class NpcFpgaCdeConfig extends CDEConfig((_, _, _) => {
  case NpcCoreConfigKey => new _root_.scpu.NpcFpgaConfig().config
})

/** 通用 SoC 的固定核心策略：具有外部 AXI 的 NPC，不含 FPGA 派发控制。 */
class NpcExternalAxiCdeConfig extends CDEConfig((_, _, _) => {
  case NpcCoreConfigKey => new _root_.scpu.NpcExternalAxiConfig().config
})

/** 固定 FPGA 平台地址和 IP 适配时序。 */
class WithFpgaPlatformConfig(platform: FpgaPlatformSettings) extends CDEConfig((_, _, _) => {
  case FpgaPlatformSettingsKey => Some(platform)
})

/** 固定 FPGA 实现工具、器件和时序策略。 */
class WithFpgaToolConfig(settings: FpgaToolSettings) extends CDEConfig((_, _, _) => {
  case FpgaToolSettingsKey => Some(settings)
})

/** 固定宿主 Vivado/Vitis 的并行任务数和可选策略搜索。 */
class WithFpgaBuildSettingsConfig(settings: FpgaBuildSettings) extends CDEConfig((_, _, _) => {
  case FpgaBuildSettingsKey => Some(settings)
})

/** 在 CDE 图中选择目标 FPGA 板卡。 */
class WithFpgaBoardConfig(board: FpgaBoard) extends CDEConfig((_, _, _) => {
  case FpgaBoardKey => Some(board)
})

/** 覆盖入口平台参数中的 FPGA 主时钟频率，单位为 MHz。 */
class WithFpgaClockMHzConfig(clockMHz: Int) extends CDEConfig((_, _, _) => {
  case FpgaClockMHzKey => Some(clockMHz)
})
