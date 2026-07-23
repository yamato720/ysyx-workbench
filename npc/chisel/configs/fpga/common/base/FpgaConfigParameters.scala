package scpu.fpga

import org.chipsalliance.cde.config.{Field, Parameters}
import _root_.scpu.{NpcConfig, NpcCoreConfigKey}

/** 裸 NPC 和 ysyxSoC FPGA 生成共享的 CDE 键与只读查询接口。 */
case object FpgaPlatformSettingsKey extends Field[Option[FpgaPlatformSettings]](None)
case object FpgaBoardKey extends Field[Option[FpgaBoard]](None)
case object FpgaClockMHzKey extends Field[Option[Int]](None)

object FpgaConfigParameters {
  /** 取得当前 CDE 图选择的已完成 NPC 参数。 */
  def npcCoreConfig(implicit parameters: Parameters): NpcConfig = parameters(NpcCoreConfigKey)

  /** 取得 FPGA 平台参数。物理板卡和频率都由终端 CDE Config 固定。 */
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

  def board(implicit parameters: Parameters): Option[FpgaBoard] = parameters(FpgaBoardKey)
}
