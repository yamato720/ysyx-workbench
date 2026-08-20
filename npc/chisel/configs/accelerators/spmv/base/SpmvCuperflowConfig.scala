package accelerators.spmv

import org.chipsalliance.cde.config.{Config => CDEConfig, Field}
import npc.ip.memory.OnChipMemoryPrimitive

/** Cuperflow 输入模型的静态几何。
  *
  * 每个 PC 只保留一个 HBM master。该 PC 的可访问地址窗口固定分成低地址 X 区和
  * 高地址 A 区；一个 work 仅携带这两个区内的 beat 偏移，不能跨越分区。片上 X
  * 仍限制在一个 sliceGroup 的 8192 个 FP64 元素内。map 最多给出八段连续 X 的
  * `start/count` descriptor，X payload 按段顺序构成纯 FP64 value 流。
  */
final case class SpmvCuperflowConfig(
  hbmPcCount: Int = 16,
  hbmBase: Long = 0x80000000L,
  hbmBytes: Long = 128L * 1024L * 1024L,
  xRegionBytes: Long = 64L * 1024L * 1024L,
  channelBaseAlignmentBytes: Int = 4096,
  axiAddrWidth: Int = 64,
  axiDataWidth: Int = 512,
  axiIdWidth: Int = 4,
  maxOutstandingBursts: Int = 2,
  xWindowSize: Int = 8192,
  xReplicaCount: Int = 4,
  /** 为 true 时 local-X 实例化两套窗口，写 inactive、activate 后读 active。 */
  xPingPong: Boolean = false,
  xElementWidth: Int = 64,
  /** local-X 物理 RAM 的原语；仿真使用行为模型，U55C 固定选择 UltraRAM。 */
  xMemoryPrimitive: OnChipMemoryPrimitive = OnChipMemoryPrimitive.Auto,
  /** local-X 内部单口返回的数据宽度，按半个 HBM beat 打包以适配 URAM。 */
  xMemoryDataWidth: Int = 256,
  fp64MultiplyLatency: Int = 4,
  fp64MultiplyInitiationInterval: Int = 1,
  fp64MultiplyResponseFifoDepth: Int = 4,
  fp64MulProvider: SpmvFp64MulProvider = SpmvFp64MulProvider.Simulation
) {
  require(hbmPcCount > 0 && hbmPcCount <= 32 && hbmPcCount % 8 == 0,
    s"Cuperflow HBM PC 数量必须是 1..32 内的 8 的倍数，实际为 $hbmPcCount")
  require(hbmBase >= 0 && hbmBytes > 0, "HBM 地址窗口必须为非空的非负区间")
  require(channelBaseAlignmentBytes > 0 &&
    (channelBaseAlignmentBytes & (channelBaseAlignmentBytes - 1)) == 0,
    s"channel 基地址对齐必须是正的二次幂，实际为 $channelBaseAlignmentBytes")
  require((hbmBase & (channelBaseAlignmentBytes - 1L)) == 0L,
    s"HBM 基地址必须按 channel 对齐，实际为 0x${java.lang.Long.toUnsignedString(hbmBase, 16)}")
  require(axiAddrWidth == 64, s"HBM AXI 地址位宽必须为 64，实际为 $axiAddrWidth")
  require(axiDataWidth == 512, s"Cuperflow HBM beat 必须为 512 bit，实际为 $axiDataWidth")
  require(axiIdWidth > 0, s"HBM AXI ID 位宽必须为正数，实际为 $axiIdWidth")
  require(maxOutstandingBursts >= 2,
    s"满带宽 reader 至少需要两笔 outstanding burst，实际为 $maxOutstandingBursts")
  require(xRegionBytes > 0 && xRegionBytes < hbmBytes && xRegionBytes % 64L == 0L,
    s"X 分区必须是 HBM 窗口内按 beat 对齐的非空真子区间，实际为 $xRegionBytes/$hbmBytes")
  require(xWindowSize == 8192,
    s"Cuperflow group localColumn 当前固定支持 8192 个 FP64，实际为 $xWindowSize")
  require(xReplicaCount == 4,
    s"Cuperflow 当前固定为 4 份 X replica，实际为 $xReplicaCount")
  require(xElementWidth == 64, s"Cuperflow X 当前必须是 FP64，实际为 $xElementWidth")
  require(xMemoryDataWidth == axiDataWidth / 2 && xMemoryDataWidth % xElementWidth == 0,
    s"Cuperflow local-X RAM 必须使用半个 AXI beat且包含完整 FP64，实际为 $xMemoryDataWidth/$axiDataWidth")
  require(fp64MultiplyLatency >= 1 && fp64MultiplyInitiationInterval >= 1 &&
    fp64MultiplyResponseFifoDepth >= 1, "FP64 multiply 的 latency、II 和响应 FIFO 深度必须为正数")

  val beatBytes: Int = axiDataWidth / 8
  val xWordsPerBeat: Int = axiDataWidth / xElementWidth
  val aRegionBytes: Long = hbmBytes - xRegionBytes
  val aRegionBase: Long = hbmBase + xRegionBytes
  val xMaxEncodedWords: Int = xWindowSize
  val xBankCount: Int = if (xPingPong) 2 else 1
  /** map lane4-7 携带 8 个 X 段 descriptor，payload 是按段顺序的冻结格式。 */
  val mapAbi: String = "cuperflow-map-multisegment-v3"

  /** 把共享 Mixed-V3 FMUL 引擎的公共几何投影为旧输入引擎所需参数。 */
  val mulConfig: SpmvInputConfig = SpmvInputConfig(
    aReaderCount = hbmPcCount,
    hbmChannelCount = hbmPcCount,
    hbmBase = hbmBase,
    hbmBytes = hbmBytes,
    channelBaseAlignmentBytes = channelBaseAlignmentBytes,
    axiAddrWidth = axiAddrWidth,
    axiDataWidth = axiDataWidth,
    axiIdWidth = axiIdWidth,
    maxOutstandingBursts = maxOutstandingBursts,
    xWindowSize = xWindowSize,
    xReplicaCount = xReplicaCount,
    xElementWidth = xElementWidth,
    fp64MultiplyLatency = fp64MultiplyLatency,
    fp64MultiplyInitiationInterval = fp64MultiplyInitiationInterval,
    fp64MultiplyResponseFifoDepth = fp64MultiplyResponseFifoDepth,
    xPortSchedule = SpmvXPortSchedule.Preload,
    fp64MulProvider = fp64MulProvider
  )
}

object SpmvCuperflowConfig {
  val Simulation: SpmvCuperflowConfig = SpmvCuperflowConfig()

  /** U55C FPGA provider；Vivado floating_point v7.1 Binary64 multiply 为 12 拍。 */
  val U55c: SpmvCuperflowConfig = SpmvCuperflowConfig(
    // FPGA kernel 的每个 m_axi 由 XRT pointer 提供基址，RTL 请求使用 BO 内偏移。
    hbmBase = 0L,
    xMemoryPrimitive = OnChipMemoryPrimitive.UltraRam,
    fp64MultiplyLatency = 12,
    fp64MulProvider = SpmvFp64MulProvider.XilinxFloatingPointV71
  )
}

case object SpmvCuperflowConfigKey extends Field[Option[SpmvCuperflowConfig]](None)

class WithSpmvCuperflowConfig(config: SpmvCuperflowConfig) extends CDEConfig((_, _, _) => {
  case SpmvCuperflowConfigKey => Some(config)
})

/** 打开 Cuperflow local-X 的第二套 ping/pong 窗口。必须叠在已有 Cuperflow Config 左侧。 */
class WithSpmvCuperflowLocalXPingPongConfig extends CDEConfig((_, _, up) => {
  case SpmvCuperflowConfigKey =>
    up(SpmvCuperflowConfigKey).map(_.copy(xPingPong = true))
})
