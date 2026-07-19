package scpu.fpga

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
  val cycleCount = UInt(64.W)
  val fcsr = UInt(8.W)
  val mstatus = UInt(width.W)
  val mcause = UInt(width.W)
  val mepc = UInt(width.W)
  val mtvec = UInt(width.W)
  val coreBusy = Bool()
  val dispatchFire = Bool()
  val backpressureReasons = UInt(9.W)
  val gprs = Vec(32, UInt(width.W))
  val fprs = Vec(32, UInt(width.W))
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
  def ebreak: UInt = 3.U(width.W)
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
  val terminalHalted = Bool()
  val protocolError = Bool()
  val completedSequence = UInt(32.W)
  val stopReason = UInt(FpgaStopReason.width.W)
  val haltCode = UInt(width.W)
  val haltPc = UInt(width.W)
  val haltNextPc = UInt(width.W)
  val commitCount = UInt(64.W)
}
