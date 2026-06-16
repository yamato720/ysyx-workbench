package zcu102

import chisel3._
import chisel3.util._

class ZCU102NPCSelfTest extends Module {
  val io = IO(new Bundle {
    val axil = new AxiLite32SlaveIO
    val irqToPs = Output(Bool())
    val debugState = Output(UInt(4.W))
  })

  val debugger = Module(new ZCU102NPCDebugger)
  io.axil <> debugger.io.axil
  io.irqToPs := debugger.io.irqToPs

  val idle :: emitO :: emitK :: emitNl :: halt :: done :: Nil = Enum(6)
  val state = RegInit(idle)
  val bootPc = RegInit(0.U(32.W))
  val commitIndex = RegInit(0.U(3.W))

  val running = state =/= idle && state =/= done
  val halted = state === halt || state === done

  debugger.io.status.running := running
  debugger.io.status.halted := halted
  debugger.io.status.busy := false.B
  debugger.io.status.trapValid := false.B
  debugger.io.status.trapCause := 0.U

  debugger.io.commit.valid := false.B
  debugger.io.commit.pc := Cat(0.U(32.W), bootPc) + (commitIndex << 2)
  debugger.io.commit.inst := "h00000013".U
  debugger.io.commit.rd := 0.U
  debugger.io.commit.rdWen := false.B
  debugger.io.commit.rdWdata := 0.U

  debugger.io.mmio.putchValid := false.B
  debugger.io.mmio.putchData := 0.U
  debugger.io.mmio.haltValid := false.B
  debugger.io.mmio.exitValid := false.B
  debugger.io.mmio.exitCode := 0.U

  when(debugger.io.ctrl.reset) {
    state := idle
    bootPc := debugger.io.ctrl.bootPc
    commitIndex := 0.U
  }.otherwise {
    switch(state) {
      is(idle) {
        when(debugger.io.ctrl.run) {
          bootPc := debugger.io.ctrl.bootPc
          state := emitO
        }
      }
      is(emitO) {
        debugger.io.commit.valid := true.B
        debugger.io.mmio.putchValid := true.B
        debugger.io.mmio.putchData := "h4f".U
        commitIndex := commitIndex + 1.U
        state := emitK
      }
      is(emitK) {
        debugger.io.commit.valid := true.B
        debugger.io.mmio.putchValid := true.B
        debugger.io.mmio.putchData := "h4b".U
        commitIndex := commitIndex + 1.U
        state := emitNl
      }
      is(emitNl) {
        debugger.io.commit.valid := true.B
        debugger.io.mmio.putchValid := true.B
        debugger.io.mmio.putchData := "h0a".U
        commitIndex := commitIndex + 1.U
        state := halt
      }
      is(halt) {
        debugger.io.mmio.exitValid := true.B
        debugger.io.mmio.exitCode := 0.U
        debugger.io.mmio.haltValid := true.B
        state := done
      }
      is(done) {
        when(debugger.io.ctrl.haltReq) {
          state := done
        }
      }
    }
  }

  io.debugState := state
}

object ElaborateZCU102NPCSelfTest extends App {
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new ZCU102NPCSelfTest,
    Array("--target-dir", "../zcu102-runtime/generated"),
    Array("--disable-annotation-unknown")
  )
}
