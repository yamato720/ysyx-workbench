package accelerators.spmv

import org.chipsalliance.cde.config.{Config => CDEConfig, Field}

/** SPMV 输入封装及其 HBM reader 的静态参数。 */
final case class SpmvInputConfig(
  /** 单个 A 输入封装内的 HBM/reader 数量。 */
  aReaderCount: Int = 16,
  /** 单个 X 输入封装内的 HBM/reader 数量。 */
  xReaderCount: Int = 1,
  /** Cuper A 编码使用的 channel 数，不包含独立的 X HBM。 */
  hbmChannelCount: Int = 16,
  hbmBase: Long = 0x80000000L,
  hbmBytes: Long = 128L * 1024L * 1024L,
  channelBaseAlignmentBytes: Int = 4096,
  axiAddrWidth: Int = 64,
  axiDataWidth: Int = 512,
  axiIdWidth: Int = 4,
  maxOutstandingBursts: Int = 2
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
  require(maxOutstandingBursts >= 2,
    s"满带宽 reader 至少需要两笔 outstanding burst，实际为 $maxOutstandingBursts")
  require(hbmBytes % channelBaseAlignmentBytes == 0,
    s"HBM 容量必须按 channel 对齐，实际为 $hbmBytes/$channelBaseAlignmentBytes")

  /** A 与 X 输入封装在顶层合计暴露的物理 HBM master 数。 */
  val totalHbmPortCount: Int = aReaderCount + xReaderCount
}

object SpmvInputConfig {
  /** 当前 Cuper 输入布局：一个 16-HBM A 封装和一个 1-HBM X 封装。 */
  val Cuper16Hbm: SpmvInputConfig = SpmvInputConfig()
}

case object SpmvInputConfigKey extends Field[Option[SpmvInputConfig]](None)

class WithSpmvInputConfig(config: SpmvInputConfig) extends CDEConfig((_, _, _) => {
  case SpmvInputConfigKey => Some(config)
})
