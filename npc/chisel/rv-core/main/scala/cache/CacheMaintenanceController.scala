package npc

import chisel3._
import chisel3.util._

/** Serializes FENCE/FENCE.I and the FPGA completion drain against the in-order backend. */
class CacheMaintenanceController(hasInstructionCache: Boolean, hasDataCache: Boolean) extends Module {
  val io = IO(new Bundle {
    val fencePending = Input(Bool())
    val fenceInvalidatesInstruction = Input(Bool())
    val fenceAccepted = Input(Bool())
    val backendBusy = Input(Bool())
    val externalDrainRequest = Input(Bool())

    val dcacheFlush = Output(Bool())
    val dcacheFlushDone = Input(Bool())
    val icacheInvalidate = Output(Bool())
    val icacheInvalidateDone = Input(Bool())
    val dispatchPermit = Output(Bool())
    val externalDrained = Output(Bool())
  })

  val Seq(sIdle, sWaitOlder, sFlushData, sInvalidateInstruction,
    sReleaseFence, sExternalDone) = Enum(6)
  val state = RegInit(sIdle)
  val externalOperation = RegInit(false.B)
  val fenceInvalidatesInstruction = RegInit(false.B)

  io.dcacheFlush := state === sFlushData
  io.icacheInvalidate := state === sInvalidateInstruction
  io.dispatchPermit := state === sIdle && !io.fencePending && !io.externalDrainRequest ||
    state === sReleaseFence
  io.externalDrained := state === sExternalDone

  def releaseOrInvalidateInstruction(): Unit = {
    if (hasInstructionCache) {
      when(!externalOperation && fenceInvalidatesInstruction) {
        state := sInvalidateInstruction
      }.otherwise {
        state := Mux(externalOperation, sExternalDone, sReleaseFence)
      }
    } else {
      state := Mux(externalOperation, sExternalDone, sReleaseFence)
    }
  }

  def afterOlderDrained(): Unit = {
    if (hasDataCache) state := sFlushData
    else releaseOrInvalidateInstruction()
  }

  switch(state) {
    is(sIdle) {
      when(io.externalDrainRequest) {
        externalOperation := true.B
        fenceInvalidatesInstruction := false.B
        state := sWaitOlder
      }.elsewhen(io.fencePending) {
        externalOperation := false.B
        fenceInvalidatesInstruction := io.fenceInvalidatesInstruction
        state := sWaitOlder
      }
    }
    is(sWaitOlder) {
      when(!externalOperation && !io.fencePending) {
        // A redirect from an older instruction discarded the speculative
        // fence while the controller was waiting for that instruction.
        state := sIdle
      }.elsewhen(!io.backendBusy) {
        afterOlderDrained()
      }
    }
    is(sFlushData) {
      when(io.dcacheFlushDone) {
        releaseOrInvalidateInstruction()
      }
    }
    is(sInvalidateInstruction) {
      when(io.icacheInvalidateDone) {
        state := Mux(externalOperation, sExternalDone, sReleaseFence)
      }
    }
    is(sReleaseFence) {
      when(io.fenceAccepted) { state := sIdle }
    }
    is(sExternalDone) {
      when(!io.externalDrainRequest) { state := sIdle }
    }
  }
}
