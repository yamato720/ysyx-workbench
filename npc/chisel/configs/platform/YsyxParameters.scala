package ysyx

import org.chipsalliance.cde.config.{Field, Parameters}
import _root_.scpu.fpga.FpgaConfigParameters

/** ysyxSoC 的 CDE 参数键和查询接口。
  *
  * 本文件不编入裸 NPC 的 SBT root，避免核心反向依赖 Rocket/CDE。
  */
sealed trait YsyxPlatform

object YsyxPlatform {
  case object Standalone extends YsyxPlatform
  case object Simulation extends YsyxPlatform
  case object Fpga extends YsyxPlatform
}

case object YsyxPlatformKey extends Field[YsyxPlatform](YsyxPlatform.Standalone)
case object YsyxChipLinkKey extends Field[Boolean](false)
case object YsyxAxiSdramKey extends Field[Boolean](false)

/** 统一读取 ysyxSoC 的平台能力，外围模块不直接访问 CDE 键。 */
object YsyxPlatformParameters {
  def platform(implicit parameters: Parameters): YsyxPlatform = parameters(YsyxPlatformKey)
  def isDpiSimulation(implicit parameters: Parameters): Boolean = platform == YsyxPlatform.Simulation
  def isFpga(implicit parameters: Parameters): Boolean = platform == YsyxPlatform.Fpga
  def enableNpcDebug(implicit parameters: Parameters): Boolean = isDpiSimulation || isFpga
  def fpgaBoard(implicit parameters: Parameters): Option[String] = FpgaConfigParameters.board.map(_.name)
  def hasChipLink(implicit parameters: Parameters): Boolean = parameters(YsyxChipLinkKey)
  def useAxiSdram(implicit parameters: Parameters): Boolean = parameters(YsyxAxiSdramKey)
  def npcCoreConfig(implicit parameters: Parameters) = FpgaConfigParameters.npcCoreConfig
}
