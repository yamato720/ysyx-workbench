package fpga

import chisel3._
import chisel3.util.Valid
import _root_.npc.CacheStatistics
/** SDB-only architectural snapshot.
  *
  * This is intentionally absent from batch monitor elaborations.  Keeping it
  * out of that configuration prevents the GPR/CSR fanout and halt controller
  * from sharing the performance monitor's timing and routing budget.
  */
class FpgaRuntimeSdbSnapshot(width: Int) extends Bundle {
  val currentPc = UInt(width.W)
  val nextArchitecturalPc = UInt(width.W)
  val frontendInstruction = UInt(32.W)
  val fcsr = UInt(8.W)
  val mstatus = UInt(width.W)
  val mcause = UInt(width.W)
  val mepc = UInt(width.W)
  val mtvec = UInt(width.W)
  // Architectural backend/LSU work only. Frontend prefetch can remain queued
  // while the debugger holds dispatch closed and is therefore not a halt
  // barrier.
  val coreBusy = Bool()
  val dispatchFire = Bool()
  val backpressureReasons = UInt(9.W)
  val gprs = Vec(32, UInt(width.W))
}

/** FPGA mailbox state common to both interactive SDB and batch monitoring. */
class FpgaRuntimeDebug(width: Int, sdbEnabled: Boolean = true) extends Bundle {
  val commitValid = Bool()
  val commitPc = UInt(width.W)
  val commitInstruction = UInt(32.W)
  val commitNextPc = UInt(width.W)
  val completionCommitValid = Bool()
  val completionCommitPc = UInt(width.W)
  val completionCommitNextPc = UInt(width.W)
  val completionCode = UInt(width.W)
  val cycleCount = UInt(64.W)
  val sdb = if (sdbEnabled) Some(new FpgaRuntimeSdbSnapshot(width)) else None
}

/** Narrow telemetry path used only by the performance-monitor construction.
  *
  * Keeping this distinct from `FpgaRuntimeDebug` is deliberate: the debug
  * bundle includes GPR and CSR snapshots for SDB and is over 3 Kbit wide on
  * RV64.  A batch monitor must not route that state to its writer/statistics
  * pipeline on every clock.
  */
class FpgaCommitTelemetry(width: Int) extends Bundle {
  val commitValid = Bool()
  val commitPc = UInt(width.W)
  val commitInstruction = UInt(32.W)
  val commitCycle = UInt(64.W)
  val stages = Vec(FpgaPerformanceMonitor.stageCount, UInt(64.W))
  val completionValid = Bool()
  val completionCycle = UInt(64.W)
  val completionStalls = Vec(FpgaPerformanceMonitor.stallCount, UInt(64.W))
  val pipelineFeatures = UInt(3.W)
}

object FpgaPerformanceMonitor {
  val formatVersion = 2
  val recordBytes = 32
  val traceDataWidth = 256
  val defaultMaxRecords = 200000
  // normal, load/store/M summaries, 24 detailed operation classes, and all.
  // Keep this in lock-step with NEMU's NPC_TIMING_CLASS_COUNT.
  val classCount = 29
  val stageCount = 5
  val stallCount = 5
}

/** Mailbox-visible trace and statistics snapshot. */
class FpgaRuntimeTraceStatus extends Bundle {
  val enabled = Bool()
  val formatVersion = UInt(32.W)
  val recordBytes = UInt(32.W)
  val maxRecords = UInt(32.W)
  val records = UInt(64.W)
  val dropped = UInt(64.W)
  val drained = Bool()
  val cycles = UInt(64.W)
  val commits = UInt(64.W)
  val pipelineFeatures = UInt(3.W)
  val classSampleCount = UInt(64.W)
  val classStageTotal = UInt(64.W)
  val classMaxTotal = UInt(64.W)
  val classLastPc = UInt(64.W)
  val classLastInstruction = UInt(32.W)
  val classLastStage = UInt(64.W)
  val lastClass = UInt(5.W)
  val lastStage = UInt(64.W)
  val lastTotal = UInt(64.W)
  val selectedStall = UInt(64.W)
}

/** Static cache geometry/policy together with the live controller counters.
  *
  * This deliberately lives in the mailbox path rather than the performance
  * trace record: configuration changes only at elaboration time, while cache
  * events are aggregate properties of the whole run rather than one commit.
  */
class FpgaCacheConfiguration extends Bundle {
  val enabled = Bool()
  val capacityBytes = UInt(32.W)
  val lineBytes = UInt(32.W)
  val ways = UInt(32.W)
  val sets = UInt(32.W)
  val mapping = UInt(2.W)
  val replacement = UInt(2.W)
  val readMiss = UInt(1.W)
  val writePolicy = UInt(1.W)
  val writeMiss = UInt(1.W)
  val storage = UInt(2.W)
}

class FpgaCacheStatus extends Bundle {
  val configuration = new FpgaCacheConfiguration
  val statistics = new CacheStatistics
}

/** Read-only cache monitor state exposed through the 4 KiB runtime mailbox. */
class FpgaRuntimeCacheStatus extends Bundle {
  val instruction = new FpgaCacheStatus
  val data = new FpgaCacheStatus
  val instructionBufferEnabled = Bool()
  val instructionBufferEntries = UInt(32.W)
}

object FpgaDebugOperation {
  val width = 3
  def halt: UInt = 1.U(width.W)
  def resume: UInt = 2.U(width.W)
  def step: UInt = 3.U(width.W)
}

object FpgaStopReason {
  val width = 4
  def none: UInt = 0.U(width.W)
  def haltRequest: UInt = 1.U(width.W)
  def step: UInt = 2.U(width.W)
}

class FpgaDebugCommand extends Bundle {
  val sequence = UInt(32.W)
  val operation = UInt(FpgaDebugOperation.width.W)
}

class FpgaResetControl extends Bundle {
  val asserted = Bool()
  val run = Bool()
}

/** 导出到 AXI-Lite 寄存器组的稳定控制器状态。 */
class FpgaDebugStatus(width: Int) extends Bundle {
  val running = Bool()
  val halting = Bool()
  val halted = Bool()
  val stableHalted = Bool()
  val stepping = Bool()
  val protocolError = Bool()
  val completedSequence = UInt(32.W)
  val stopReason = UInt(FpgaStopReason.width.W)
  val haltCode = UInt(width.W)
  val haltPc = UInt(width.W)
  val haltNextPc = UInt(width.W)
  val commitCount = UInt(64.W)
}
