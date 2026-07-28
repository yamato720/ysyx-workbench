package npc.fpga

import _root_.npc.RuntimeTraceProfile

/** FPGA runtime trace 的固定硬件 ABI。
  *
  * Trace 是显式的板卡硬件能力，而不是 NEMU host 的可选采样开关。开启后会增加
  * 独立 AXI master，因此只能由需要它的终端 Config 挂载。
  */
final case class FpgaRuntimeTraceConfig(
  enabled: Boolean,
  hbmBank: Int,
  bufferBytes: Int,
  maxRecords: Int,
  /** On-chip producer/consumer FIFO depth.  This is synthesized as URAM. */
  cacheRecords: Int
) {
  require(hbmBank >= 0, s"trace HBM bank cannot be negative: $hbmBank")
  require(bufferBytes >= 0 && bufferBytes % 4096 == 0,
    s"trace buffer must be a nonnegative 4 KiB multiple: $bufferBytes")
  require(maxRecords >= 0, s"trace record limit cannot be negative: $maxRecords")
  require(cacheRecords >= 0 && (!enabled || (cacheRecords >= 2 && (cacheRecords & (cacheRecords - 1)) == 0)),
    s"trace cache must be disabled or a power-of-two depth of at least two: $cacheRecords")
  require(!enabled || bufferBytes >= maxRecords * FpgaRuntimeTraceConfig.recordBytes,
    s"trace buffer $bufferBytes is smaller than $maxRecords trace records")
  require(!enabled || (hbmBank == 1 && bufferBytes == 16 * 1024 * 1024 && maxRecords == 200000),
    "the public FPGA trace ABI is fixed to HBM[1], 16 MiB, and 200000 records")
}

object FpgaRuntimeTraceConfig {
  private val recordBytes = 72
  val DefaultCacheRecords = 4096

  val Disabled: FpgaRuntimeTraceConfig = FpgaRuntimeTraceConfig(
    enabled = false, hbmBank = 0, bufferBytes = 0, maxRecords = 0, cacheRecords = 0)

  /** Builds the U55C v12 runtime-trace ABI with an explicitly sized URAM FIFO. */
  def u55cDebug(cacheRecords: Int = DefaultCacheRecords): FpgaRuntimeTraceConfig =
    FpgaRuntimeTraceConfig(
      enabled = true,
      hbmBank = 1,
      bufferBytes = 16 * 1024 * 1024,
      maxRecords = 200000,
      cacheRecords = cacheRecords
    )

  val U55cDebug: FpgaRuntimeTraceConfig = u55cDebug()

  def from(profile: RuntimeTraceProfile): FpgaRuntimeTraceConfig = FpgaRuntimeTraceConfig(
    enabled = profile.enabled,
    hbmBank = profile.hbmBank,
    bufferBytes = profile.bufferBytes,
    maxRecords = profile.maxRecords,
    cacheRecords = profile.cacheRecords
  )
}
