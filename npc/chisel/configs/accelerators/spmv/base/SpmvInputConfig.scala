package accelerators.spmv

import org.chipsalliance.cde.config.{Config => CDEConfig, Field}

/** SPMV 输入封装及其 HBM reader 的静态参数。 */
final case class SpmvInputConfig(
  /** 单个 A 输入封装内的 HBM/reader 数量。 */
  aReaderCount: Int = 16,
  /** 单个 X 输入封装内的 HBM/reader 数量。 */
  xReaderCount: Int = 2,
  /** 侧带控制 HBM/reader 数量。当前广播 map，之后可供 JPCG 复用读写。 */
  ctrlReaderCount: Int = 1,
  /** Cuper A 编码使用的 channel 数，不包含独立的 X HBM。 */
  hbmChannelCount: Int = 16,
  hbmBase: Long = 0x80000000L,
  hbmBytes: Long = 128L * 1024L * 1024L,
  channelBaseAlignmentBytes: Int = 4096,
  axiAddrWidth: Int = 64,
  axiDataWidth: Int = 512,
  axiIdWidth: Int = 4,
  maxOutstandingBursts: Int = 2,
  /** 片上 X 窗口，单位是 FP64 元素；与 Cuper A 分片同宽。 */
  xWindowSize: Int = 8192,
  /** 每个窗口的 local_X 副本数；PE p 读 replica p/2。 */
  xReplicaCount: Int = 4,
  /** 片上 X 元素位宽。当前固定 FP64，对应 Mixed-V3。 */
  xElementWidth: Int = 64,
  /** FP64 乘法公共 IP 接口的请求到响应延迟。 */
  fp64MultiplyLatency: Int = 4,
  /** FP64 乘法公共 IP 接口的最小启动间隔。 */
  fp64MultiplyInitiationInterval: Int = 1,
  /** FP64 乘法公共 IP 接口的响应 FIFO 深度。 */
  fp64MultiplyResponseFifoDepth: Int = 4,
  /** 每个 512-bit Cuper A beat 同时送入乘法 IP 的 slot 数。 */
  fp64MultiplyLaneCount: Int = 8,
  /** Cuper A slot v3 的 [63:51] 本地列号宽度。 */
  cuperSlotColumnBits: Int = 13,
  /** Cuper A slot v3 的 [50:48] 完整保留 tag 宽度，不参与当前乘法控制。 */
  cuperSlotTagBits: Int = 3,
  /** Cuper A slot v3 的 [47:32] 直接 CSR 行标宽度。 */
  cuperSlotRowBits: Int = 16
) {
  require(aReaderCount > 0, s"A reader 数量必须为正数，实际为 $aReaderCount")
  require(xReaderCount > 0, s"X reader 数量必须为正数，实际为 $xReaderCount")
  require(ctrlReaderCount == 1, s"当前控制面要求一路可广播 HBM，实际为 $ctrlReaderCount")
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
  require(xElementWidth == 64, s"片上 X 当前必须是 FP64，实际为 $xElementWidth")
  require(axiDataWidth % xElementWidth == 0,
    s"AXI 数据位宽必须能整除 X 元素位宽，实际为 $axiDataWidth/$xElementWidth")
  require(xWindowSize > 0 && (xWindowSize & (xWindowSize - 1)) == 0,
    s"X 窗口必须是正的二次幂，实际为 $xWindowSize")
  require(xReplicaCount > 0, s"local_X 副本数必须为正数，实际为 $xReplicaCount")
  require(fp64MultiplyLatency >= 1,
    s"FP64 乘法 IP latency 必须为正数，实际为 $fp64MultiplyLatency")
  require(fp64MultiplyInitiationInterval >= 1,
    s"FP64 乘法 IP II 必须为正数，实际为 $fp64MultiplyInitiationInterval")
  require(fp64MultiplyResponseFifoDepth >= 1,
    s"FP64 乘法 IP 响应 FIFO 深度必须为正数，实际为 $fp64MultiplyResponseFifoDepth")
  require(fp64MultiplyLaneCount == 8,
    s"当前 Cuper 512-bit A beat 固定包含 8 个乘法 slot，实际为 $fp64MultiplyLaneCount")
  require(cuperSlotColumnBits == 13 && cuperSlotTagBits == 3 && cuperSlotRowBits == 16,
    s"当前 Cuper slot v3 必须是 col/tag/row=13/3/16，实际为 " +
      s"$cuperSlotColumnBits/$cuperSlotTagBits/$cuperSlotRowBits")
  require(cuperSlotColumnBits + cuperSlotTagBits + cuperSlotRowBits + 32 == 64,
    "Cuper slot v3 的列/tag/行/FP32 位域必须恰好填满 64 bit")
  require(xWindowSize <= (1 << cuperSlotColumnBits),
    s"X 窗口不能超过 Cuper slot v3 的列号容量，实际为 $xWindowSize")

  /** 一个 X HBM beat 携带的 FP64 元素数。 */
  val xElementsPerBeat: Int = axiDataWidth / xElementWidth
  /** 双路 X 同时广播时，一拍写入 local_X 的元素数。 */
  val xWriteLanes: Int = xReaderCount * xElementsPerBeat
  /** 每拍每个 bank 写 1 个元素，因此 bank 数等于写宽度。 */
  val xBankCount: Int = xWriteLanes
  require(xWindowSize >= xBankCount && xWindowSize % xBankCount == 0,
    s"X 窗口必须按 bank 对齐，实际为 $xWindowSize/$xBankCount")
  val xBankDepth: Int = xWindowSize / xBankCount

  /** A 与 X 输入封装在顶层合计暴露的物理 HBM master 数。 */
  val totalHbmPortCount: Int = aReaderCount + xReaderCount + ctrlReaderCount
  /** 每个 A HBM channel 对应一个独立 Cuper FP64 乘法 PE。 */
  val fp64MultiplyCoreCount: Int = aReaderCount
  /** 所有 PE 合计实例化的 FP64 乘法 IP lane 数。 */
  val fp64MultiplyTotalLaneCount: Int = fp64MultiplyCoreCount * fp64MultiplyLaneCount
}

object SpmvInputConfig {
  /** 当前 Cuper 输入布局：16-HBM A、2-HBM X、1-HBM 控制广播，以及 8192×4 的 FP64 local_X。 */
  val Cuper16Hbm: SpmvInputConfig = SpmvInputConfig()
}

case object SpmvInputConfigKey extends Field[Option[SpmvInputConfig]](None)

class WithSpmvInputConfig(config: SpmvInputConfig) extends CDEConfig((_, _, _) => {
  case SpmvInputConfigKey => Some(config)
})
