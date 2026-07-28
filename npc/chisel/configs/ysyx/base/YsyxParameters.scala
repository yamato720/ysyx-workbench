package ysyx

import org.chipsalliance.cde.config.{Field, Parameters}
import _root_.npc.{FpgaIpAttachmentKey, NpcCoreConfigKey}

/** ysyxSoC 的 CDE 参数键和查询接口。
  *
  * 本文件不编入裸 NPC 的 SBT root，避免核心反向依赖 Rocket/CDE。
 */
case object YsyxChipLinkKey extends Field[Boolean](false)
case object YsyxAxiSdramKey extends Field[Boolean](false)

/** 统一读取 ysyxSoC 的平台能力。
  *
  * FPGA 与仿真的分支没有独立的运行时参数：终端 CDE 图是否已有通用的
  * `FpgaIpAttachmentKey` 就是唯一事实来源。这样板卡层固定后，SoC 不需要
  * 依赖板卡实现类型或重复叠加一个平台标签。
 */
object YsyxPlatformParameters {
  def isFpga(implicit parameters: Parameters): Boolean = parameters(FpgaIpAttachmentKey).nonEmpty
  def isDpiSimulation(implicit parameters: Parameters): Boolean = !isFpga
  def enableNpcDebug(implicit parameters: Parameters): Boolean = isDpiSimulation || isFpga
  def hasChipLink(implicit parameters: Parameters): Boolean = parameters(YsyxChipLinkKey)
  def useAxiSdram(implicit parameters: Parameters): Boolean = parameters(YsyxAxiSdramKey)
  def npcCoreConfig(implicit parameters: Parameters) = parameters(NpcCoreConfigKey)
}
