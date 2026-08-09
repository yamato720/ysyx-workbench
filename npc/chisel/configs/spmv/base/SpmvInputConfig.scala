package npc

import org.chipsalliance.cde.config.{Config => CDEConfig, Field}

/** 新 SPMV 输入架构的静态 HBM reader 参数。 */
final case class SpmvInputConfig(
  aReaderCount: Int = 16,
  xReaderCount: Int = 1,
  hbmChannelCount: Int = 16,
  hbmBase: Long = 0x80000000L,
  hbmBytes: Long = 128L * 1024L * 1024L,
  channelBaseAlignmentBytes: Int = 4096,
  axiAddrWidth: Int = 64,
  axiDataWidth: Int = 512,
  axiIdWidth: Int = 4
) {
  require(aReaderCount > 0, s"A reader 数量必须为正数，实际为 $aReaderCount")
  require(xReaderCount > 0, s"X reader 数量必须为正数，实际为 $xReaderCount")
  require(hbmChannelCount > 0 && hbmChannelCount <= 32,
    s"HBM channel 数量必须在 1..32，实际为 $hbmChannelCount")
  require(aReaderCount == hbmChannelCount,
    s"当前 Cuper A 输入要求每个 HBM channel 一个 A reader，实际为 $aReaderCount/$hbmChannelCount")
  require(hbmBase >= 0 && hbmBytes > 0, "HBM 地址窗口必须为非空的非负区间")
  require(channelBaseAlignmentBytes > 0 &&
    (channelBaseAlignmentBytes & (channelBaseAlignmentBytes - 1)) == 0,
    s"channel 基地址对齐必须是正的二次幂，实际为 $channelBaseAlignmentBytes")
  require((hbmBase & (channelBaseAlignmentBytes - 1L)) == 0L,
    s"HBM 基地址必须按 channel 对齐，实际为 0x${java.lang.Long.toUnsignedString(hbmBase, 16)}")
  require(axiAddrWidth == 64, s"HBM AXI 地址位宽必须为 64，实际为 $axiAddrWidth")
  require(axiDataWidth == 512, s"当前 reader HBM 数据位宽必须为 512，实际为 $axiDataWidth")
  require(axiIdWidth > 0, s"HBM AXI ID 位宽必须为正数，实际为 $axiIdWidth")
  require(hbmBytes % channelBaseAlignmentBytes == 0,
    s"HBM 容量必须按 channel 对齐，实际为 $hbmBytes/$channelBaseAlignmentBytes")
}

object SpmvInputConfig {
  /** 当前 Cuper 输入布局：16 路 A channel，共享一个 128 MiB HBM 地址窗口。 */
  val Cuper16Hbm: SpmvInputConfig = SpmvInputConfig()
}

case object SpmvInputConfigKey extends Field[Option[SpmvInputConfig]](None)

class WithSpmvInputConfig(config: SpmvInputConfig) extends CDEConfig((_, _, _) => {
  case SpmvInputConfigKey => Some(config)
})
