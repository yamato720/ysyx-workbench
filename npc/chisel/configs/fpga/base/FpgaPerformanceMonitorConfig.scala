package fpga

import _root_.npc.PerformanceMonitorProfile

/** Hardware ABI for the optional U55C batch performance monitor.
  *
  * This is a board capability, not a NEMU sampling option.  The ordinary
  * U55C runtime remains monitor-free and keeps the v11 ABI.  The monitor
  * profile owns a separate, wide HBM writer and is intentionally a distinct
  * xclbin ABI.
  */
final case class FpgaPerformanceMonitorConfig(
  enabled: Boolean,
  hbmBank: Int,
  bufferBytes: Int,
  maxRecords: Int,
  cacheRecords: Int,
  traceDataWidth: Int,
  burstRecords: Int
) {
  require(hbmBank >= 0, s"performance-monitor HBM bank cannot be negative: $hbmBank")
  require(bufferBytes >= 0 && bufferBytes % 4096 == 0,
    s"performance-monitor buffer must be a nonnegative 4 KiB multiple: $bufferBytes")
  require(maxRecords >= 0, s"performance-monitor record limit cannot be negative: $maxRecords")
  require(!enabled || bufferBytes >= maxRecords * FpgaPerformanceMonitorConfig.RecordBytes,
    s"performance-monitor buffer $bufferBytes is smaller than $maxRecords records")
  require(!enabled || (cacheRecords >= 2 && (cacheRecords & (cacheRecords - 1)) == 0),
    s"performance-monitor cache must be a power-of-two depth of at least two: $cacheRecords")
  require(!enabled || traceDataWidth == FpgaPerformanceMonitorConfig.TraceDataWidth,
    s"U55C performance-monitor trace port must be ${FpgaPerformanceMonitorConfig.TraceDataWidth} bits")
  require(!enabled || burstRecords >= 2 && (burstRecords & (burstRecords - 1)) == 0,
    s"performance-monitor burst size must be a power of two of at least two: $burstRecords")
  require(!enabled || (hbmBank == 1 && bufferBytes == 8 * 1024 * 1024 &&
    maxRecords == 200000 && cacheRecords == 2048 && burstRecords == 16),
    "the public U55C performance-monitor ABI is fixed to HBM[1], 8 MiB, 200000 records, a 2048-record FIFO, and 16-record bursts")

  def profile: PerformanceMonitorProfile = PerformanceMonitorProfile(
    enabled = enabled,
    hbmBank = hbmBank,
    bufferBytes = bufferBytes,
    maxRecords = maxRecords,
    cacheRecords = cacheRecords,
    formatVersion = if (enabled) FpgaPerformanceMonitorConfig.FormatVersion else 0,
    recordBytes = if (enabled) FpgaPerformanceMonitorConfig.RecordBytes else 0,
    traceDataWidth = traceDataWidth,
    burstRecords = burstRecords
  )
}

object FpgaPerformanceMonitorConfig {
  val FormatVersion = 2
  val RecordBytes = 32
  val TraceDataWidth = 256
  val U55cBatch: FpgaPerformanceMonitorConfig = FpgaPerformanceMonitorConfig(
    enabled = true,
    hbmBank = 1,
    bufferBytes = 8 * 1024 * 1024,
    maxRecords = 200000,
    cacheRecords = 2048,
    traceDataWidth = TraceDataWidth,
    burstRecords = 16
  )
  val Disabled: FpgaPerformanceMonitorConfig = FpgaPerformanceMonitorConfig(
    enabled = false,
    hbmBank = 0,
    bufferBytes = 0,
    maxRecords = 0,
    cacheRecords = 0,
    traceDataWidth = 0,
    burstRecords = 0
  )
}
