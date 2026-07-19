package scpu.fpga

import chisel3._
import chisel3.util._

/** 在前后端派发边界实现精确暂停、恢复和单步控制。 */
class FpgaDebugController(width: Int) extends Module {
  require(width == 32 || width == 64)

  val io = IO(new Bundle {
    val runtime = Input(new FpgaRuntimeDebug(width))
    val coreReset = Input(Bool())
    val command = Input(Valid(new FpgaDebugCommand))
    val resetControl = Input(Valid(new FpgaResetControl))
    val clearError = Input(Bool())
    val dispatchPermit = Output(Bool())
    val status = Output(new FpgaDebugStatus(width))
  })

  val runningState :: haltingState :: haltedState :: steppingState :: Nil = Enum(4)
  val state = RegInit(haltedState)
  val stepCredit = RegInit(false.B)
  val stepDispatched = RegInit(false.B)
  val stepStartCommit = RegInit(0.U(64.W))
  val acceptedSequence = RegInit(0.U(32.W))
  val completedSequence = RegInit(0.U(32.W))
  val commandPending = RegInit(false.B)
  val protocolError = RegInit(false.B)
  val stopReason = RegInit(FpgaStopReason.haltRequest)
  val haltCode = RegInit(0.U(width.W))
  val haltPc = RegInit(0.U(width.W))
  val haltNextPc = RegInit(0.U(width.W))
  val commitCount = RegInit(0.U(64.W))

  val terminalCommit = io.runtime.commitValid &&
    io.runtime.commitInstruction === "h00100073".U
  val coreStable = !io.coreReset && !io.runtime.coreBusy

  io.dispatchPermit := !terminalCommit && (state === runningState ||
    (state === steppingState && stepCredit))

  when(io.clearError) {
    protocolError := false.B
  }

  when(io.runtime.commitValid && !io.coreReset) {
    commitCount := commitCount + 1.U
    when(terminalCommit) {
      state := haltingState
      stepCredit := false.B
      stopReason := FpgaStopReason.ebreak
      haltCode := io.runtime.gprs(10)
      haltPc := io.runtime.commitPc
      haltNextPc := io.runtime.commitNextPc
    }
  }

  when(state === steppingState && io.runtime.dispatchFire) {
    stepCredit := false.B
    stepDispatched := true.B
  }
  when(state === steppingState && stepDispatched &&
      commitCount =/= stepStartCommit && coreStable) {
    state := haltedState
    stopReason := FpgaStopReason.step
    haltPc := io.runtime.commitPc
    haltNextPc := io.runtime.nextArchitecturalPc
    when(commandPending) {
      completedSequence := acceptedSequence
      commandPending := false.B
    }
  }
  when(state === haltingState && coreStable) {
    state := haltedState
    haltPc := io.runtime.commitPc
    haltNextPc := io.runtime.nextArchitecturalPc
    when(commandPending) {
      completedSequence := acceptedSequence
      commandPending := false.B
    }
  }

  when(io.command.valid) {
    val sequenceDelta = io.command.bits.sequence - acceptedSequence
    val sequenceIsNew = io.command.bits.sequence =/= acceptedSequence && !sequenceDelta(31)
    when(!sequenceIsNew) {
      protocolError := true.B
    }.otherwise {
      acceptedSequence := io.command.bits.sequence
      when(io.command.bits.operation < FpgaDebugOperation.halt ||
          io.command.bits.operation > FpgaDebugOperation.step) {
        protocolError := true.B
        completedSequence := io.command.bits.sequence
      }.otherwise {
        switch(io.command.bits.operation) {
          is(FpgaDebugOperation.halt) {
            commandPending := true.B
            stopReason := FpgaStopReason.haltRequest
            stepCredit := false.B
            state := Mux(coreStable, haltedState, haltingState)
            when(coreStable) {
              completedSequence := io.command.bits.sequence
              commandPending := false.B
              haltPc := io.runtime.commitPc
              haltNextPc := io.runtime.nextArchitecturalPc
            }
          }
          is(FpgaDebugOperation.resume) {
            when(io.coreReset) {
              protocolError := true.B
              completedSequence := io.command.bits.sequence
            }.otherwise {
              state := runningState
              stepCredit := false.B
              stepDispatched := false.B
              stopReason := FpgaStopReason.none
              completedSequence := io.command.bits.sequence
            }
          }
          is(FpgaDebugOperation.step) {
            when(state === haltedState && coreStable) {
              state := steppingState
              stepCredit := true.B
              stepDispatched := false.B
              stepStartCommit := commitCount
              stopReason := FpgaStopReason.none
              commandPending := true.B
            }.otherwise {
              protocolError := true.B
              completedSequence := io.command.bits.sequence
            }
          }
        }
      }
    }
  }

  when(io.resetControl.valid) {
    when(io.resetControl.bits.asserted) {
      state := haltedState
      stepCredit := false.B
      stepDispatched := false.B
      commandPending := false.B
      stopReason := FpgaStopReason.haltRequest
      protocolError := false.B
    }.elsewhen(io.coreReset) {
      state := Mux(io.resetControl.bits.run, runningState, haltingState)
      stopReason := Mux(io.resetControl.bits.run,
        FpgaStopReason.none, FpgaStopReason.haltRequest)
      haltCode := 0.U
      haltPc := 0.U
      haltNextPc := 0.U
      commitCount := 0.U
    }.elsewhen(io.resetControl.bits.run) {
      state := runningState
      stepCredit := false.B
      stepDispatched := false.B
      stopReason := FpgaStopReason.none
    }
  }

  io.status.running := state === runningState
  io.status.halting := state === haltingState
  io.status.halted := state === haltedState
  io.status.stableHalted := io.status.halted && coreStable
  io.status.stepping := state === steppingState
  io.status.terminalHalted := io.status.halted && stopReason === FpgaStopReason.ebreak
  io.status.protocolError := protocolError
  io.status.completedSequence := completedSequence
  io.status.stopReason := stopReason
  io.status.haltCode := haltCode
  io.status.haltPc := haltPc
  io.status.haltNextPc := haltNextPc
  io.status.commitCount := commitCount
}
