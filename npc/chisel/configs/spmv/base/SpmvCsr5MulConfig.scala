package npc

import org.chipsalliance.cde.config.{Config => CDEConfig, Field}

sealed trait SpmvXMode { def name: String }

object SpmvXMode {
  case object Paired extends SpmvXMode { override val name: String = "paired" }
  case object Cached extends SpmvXMode { override val name: String = "cached" }
}

/** 单 HBM CSR5 乘法仿真的完整硬件参数。 */
final case class SpmvCsr5MulConfig(
  xMode: SpmvXMode = SpmvXMode.Paired,
  hbmBase: Long = 0x80000000L,
  hbmBytes: Long = 128L * 1024L * 1024L,
  axiAddrWidth: Int = 64,
  axiDataWidth: Int = 512,
  axiIdWidth: Int = 4,
  valueWidth: Int = 32,
  coordWidth: Int = 32,
  omega: Int = 8,
  sigma: Int = 16,
  maxBlockRows: Int = 8192,
  maxBlockCols: Int = 8192,
  xReplicas: Int = 0,
  xReadLanes: Int = 8,
  multiplierCount: Int = 8,
  multiplierLatency: Int = 4,
  multiplierInitiationInterval: Int = 1,
  maxBurstBeats: Int = 64,
  outstandingBursts: Int = 2,
  hbmFirstBeatLatencyMin: Int = 73,
  hbmFirstBeatLatencyMax: Int = 81,
  hbmTimingSeed: Long = 0x13579bdfL,
  inputFifoDepth: Int = 128,
  productFifoDepth: Int = 8,
  unitId: Int = 0
) {
  require(hbmBase >= 0 && hbmBytes == 128L * 1024L * 1024L)
  require((hbmBase & 63L) == 0L && (hbmBytes & 63L) == 0L)
  require(axiAddrWidth == 64 && axiDataWidth == 512 && axiIdWidth > 0)
  require(valueWidth == 32 && coordWidth == 32)
  require(omega == 8 && sigma == 16 && omega * sigma == 128)
  require(maxBlockRows == 8192 && maxBlockCols == 8192)
  require(xReadLanes == 8)
  require((xMode == SpmvXMode.Paired && xReplicas == 0) ||
    (xMode == SpmvXMode.Cached && xReplicas == 4))
  require(multiplierCount == 8 && multiplierLatency == 4 && multiplierInitiationInterval == 1)
  require(maxBurstBeats == 64 && outstandingBursts == 2)
  require(hbmFirstBeatLatencyMin == 73 && hbmFirstBeatLatencyMax == 81)
  require(inputFifoDepth >= outstandingBursts * maxBurstBeats &&
    productFifoDepth >= multiplierLatency)
  require(unitId >= 0 && unitId <= 255)

  val bytesPerBeat: Int = axiDataWidth / 8
  val tileNnz: Int = omega * sigma
  val recordsPerBeat: Int = axiDataWidth / 64
  val fullTileBeats: Int = 1 + sigma
  val xElementsPerBeat: Int = axiDataWidth / valueWidth
  val xCacheWords: Int = maxBlockCols / xElementsPerBeat
}

object SpmvCsr5MulConfig {
  val OneHbmFp32X8192Paired: SpmvCsr5MulConfig = SpmvCsr5MulConfig()
  val OneHbmFp32X8192Cached: SpmvCsr5MulConfig = SpmvCsr5MulConfig(
    xMode = SpmvXMode.Cached,
    xReplicas = 4
  )

  val OneHbmFp32X8192: SpmvCsr5MulConfig = OneHbmFp32X8192Paired
}

case object SpmvCsr5MulConfigKey extends Field[Option[SpmvCsr5MulConfig]](None)

class WithSpmvCsr5MulConfig(config: SpmvCsr5MulConfig) extends CDEConfig((_, _, _) => {
  case SpmvCsr5MulConfigKey => Some(config)
})
