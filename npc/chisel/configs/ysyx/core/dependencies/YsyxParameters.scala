package ysyx

import org.chipsalliance.cde.config.{Field, Parameters}
import _root_.scpu.fpga.FpgaConfigParameters

/** ysyxSoC 的 CDE 参数键和查询接口。
  *
  * 本文件不编入裸 NPC 的 SBT root，避免核心反向依赖 Rocket/CDE。
 */
case object YsyxChipLinkKey extends Field[Boolean](false)
case object YsyxAxiSdramKey extends Field[Boolean](false)

/** 统一读取 ysyxSoC 的平台能力。
  *
  * FPGA 与仿真的分支没有独立的运行时参数：终端 CDE 图是否已有 `FpgaBoardKey`
  * 就是唯一事实来源。这样板卡层固定后，SoC 不需要再重复叠加一个平台标签。
  */
object YsyxPlatformParameters {
  def isFpga(implicit parameters: Parameters): Boolean = FpgaConfigParameters.board.nonEmpty
  def isDpiSimulation(implicit parameters: Parameters): Boolean = !isFpga
  def enableNpcDebug(implicit parameters: Parameters): Boolean = isDpiSimulation || isFpga
  def fpgaBoard(implicit parameters: Parameters): Option[String] = FpgaConfigParameters.board.map(_.name)
  def hasChipLink(implicit parameters: Parameters): Boolean = parameters(YsyxChipLinkKey)
  def useAxiSdram(implicit parameters: Parameters): Boolean = parameters(YsyxAxiSdramKey)
  def npcCoreConfig(implicit parameters: Parameters) = FpgaConfigParameters.npcCoreConfig
}
