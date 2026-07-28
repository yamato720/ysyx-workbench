package npc.fpga

import chisel3._
import chisel3.util.Valid
/** FPGA 调试控制器读取的架构状态与流水线状态。 */
class FpgaRuntimeDebug(width: Int) extends Bundle {
  val currentPc = UInt(width.W)
  val nextArchitecturalPc = UInt(width.W)
  val frontendInstruction = UInt(32.W)
  val commitValid = Bool()
  val commitPc = UInt(width.W)
  val commitInstruction = UInt(32.W)
  val commitNextPc = UInt(width.W)
  // The registered debug commit view is retained for SDB.  The sample view is
  // the same-cycle WB payload used by the non-blocking trace writer.
  val sampleCommitValid = Bool()
  val sampleCommitPc = UInt(width.W)
  val sampleCommitInstruction = UInt(32.W)
  val sampleCommitNextPc = UInt(width.W)
  val sampleFetchCycles = UInt(64.W)
  val sampleDecodeCycles = UInt(64.W)
  val sampleExecuteCycles = UInt(64.W)
  val sampleMemoryCycles = UInt(64.W)
  val sampleWritebackCycles = UInt(64.W)
  val completionCommitValid = Bool()
  val completionCommitPc = UInt(width.W)
  val completionCommitNextPc = UInt(width.W)
  val cycleCount = UInt(64.W)
  val fcsr = UInt(8.W)
  val mstatus = UInt(width.W)
  val mcause = UInt(width.W)
  val mepc = UInt(width.W)
  val mtvec = UInt(width.W)
  val coreBusy = Bool()
  val dispatchFire = Bool()
  val backpressureReasons = UInt(9.W)
  val fetchAxiWaitCycles = UInt(64.W)
  val redirectFlushCount = UInt(64.W)
  val idStallCycles = UInt(64.W)
  val executeStallCycles = UInt(64.W)
  val memoryStallCycles = UInt(64.W)
  val pipelineFeatures = UInt(3.W)
  val gprs = Vec(32, UInt(width.W))
}

object FpgaRuntimeTrace {
  val formatVersion = 1
  val recordBytes = 72
  val defaultMaxRecords = 200000
  val classCount = 30
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
