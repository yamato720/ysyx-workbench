package ysyx.fpga

import chisel3._
import freechips.rocketchip.diplomacy.LazyModule
import org.chipsalliance.cde.config.Parameters
import _root_.scpu.fpga.{FpgaFallbackMailbox, FpgaSystemIO}
import _root_.ysyx.{ChipLinkParam, YsyxPlatformParameters, ysyxSoCASIC}

/** Board-neutral ysyxSoC system: CPU, SoC interconnect, AXI memory, and mailbox. */
class YsyxSocFpgaSystem(implicit val parameters: Parameters) extends Module {
  private val npcConfig = YsyxPlatformParameters.npcCoreConfig
  require(npcConfig.isa.xlen == 32, "ysyxSoC FPGA integration only supports RV32")
  require(YsyxPlatformParameters.isFpga, "YsyxSocFpgaSystem requires an FPGA platform Config")
  require(YsyxPlatformParameters.fpgaBoard.nonEmpty, "YsyxSocFpgaSystem requires a board-specific FPGA Config")

  val io = IO(new FpgaSystemIO(32, 32, ChipLinkParam.idBits))
  val soc = LazyModule(new ysyxSoCASIC)
  val mailbox = Module(new FpgaFallbackMailbox(32))
  val msoc = withReset(reset.asBool || mailbox.io.coreReset) { Module(soc.module) }
  val memory = soc.fpgaMemory.head

  msoc.intr_from_chipSlave := io.interrupt

  io.master.aw.valid := memory.aw.valid
  io.master.aw.bits.id := memory.aw.bits.id
  io.master.aw.bits.addr := memory.aw.bits.addr
  io.master.aw.bits.len := memory.aw.bits.len
  io.master.aw.bits.size := memory.aw.bits.size
  io.master.aw.bits.burst := memory.aw.bits.burst
  io.master.aw.bits.lock := memory.aw.bits.lock
  io.master.aw.bits.cache := memory.aw.bits.cache
  io.master.aw.bits.prot := memory.aw.bits.prot
  io.master.aw.bits.qos := memory.aw.bits.qos
  memory.aw.ready := io.master.aw.ready

  io.master.w.valid := memory.w.valid
  io.master.w.bits.data := memory.w.bits.data
  io.master.w.bits.strb := memory.w.bits.strb
  io.master.w.bits.last := memory.w.bits.last
  memory.w.ready := io.master.w.ready

  memory.b.valid := io.master.b.valid
  memory.b.bits.id := io.master.b.bits.id
  memory.b.bits.resp := io.master.b.bits.resp
  io.master.b.ready := memory.b.ready

  io.master.ar.valid := memory.ar.valid
  io.master.ar.bits.id := memory.ar.bits.id
  io.master.ar.bits.addr := memory.ar.bits.addr
  io.master.ar.bits.len := memory.ar.bits.len
  io.master.ar.bits.size := memory.ar.bits.size
  io.master.ar.bits.burst := memory.ar.bits.burst
  io.master.ar.bits.lock := memory.ar.bits.lock
  io.master.ar.bits.cache := memory.ar.bits.cache
  io.master.ar.bits.prot := memory.ar.bits.prot
  io.master.ar.bits.qos := memory.ar.bits.qos
  memory.ar.ready := io.master.ar.ready

  memory.r.valid := io.master.r.valid
  memory.r.bits.id := io.master.r.bits.id
  memory.r.bits.data := io.master.r.bits.data
  memory.r.bits.resp := io.master.r.bits.resp
  memory.r.bits.last := io.master.r.bits.last
  io.master.r.ready := memory.r.ready

  io.mailboxInterrupt := mailbox.io.interrupt
  io.memoryHostBase := mailbox.io.memoryHostBase
  mailbox.io.putch <> msoc.putch.get

  mailbox.io.axi.aw.valid := io.control.aw.valid
  mailbox.io.axi.aw.bits := io.control.aw.bits
  io.control.aw.ready := mailbox.io.axi.aw.ready
  mailbox.io.axi.w.valid := io.control.w.valid
  mailbox.io.axi.w.bits := io.control.w.bits
  io.control.w.ready := mailbox.io.axi.w.ready
  io.control.b.valid := mailbox.io.axi.b.valid
  io.control.b.bits := mailbox.io.axi.b.bits
  mailbox.io.axi.b.ready := io.control.b.ready
  mailbox.io.axi.ar.valid := io.control.ar.valid
  mailbox.io.axi.ar.bits := io.control.ar.bits
  io.control.ar.ready := mailbox.io.axi.ar.ready
  io.control.r.valid := mailbox.io.axi.r.valid
  io.control.r.bits := mailbox.io.axi.r.bits
  mailbox.io.axi.r.ready := io.control.r.ready

  msoc.arithmeticAssist match {
    case Some(assist) =>
      mailbox.io.core.request.valid := assist.request.valid
      mailbox.io.core.request.bits := assist.request.bits
      assist.request.ready := mailbox.io.core.request.ready
      assist.response.valid := mailbox.io.core.response.valid
      assist.response.bits := mailbox.io.core.response.bits
      mailbox.io.core.response.ready := assist.response.ready
      mailbox.io.core.busy := assist.busy
    case None =>
      mailbox.io.core.request.valid := false.B
      mailbox.io.core.request.bits := 0.U.asTypeOf(mailbox.io.core.request.bits)
      mailbox.io.core.response.ready := false.B
      mailbox.io.core.busy := false.B
  }

  val debug = msoc.debug.get
  mailbox.io.runtime.currentPc := debug.frontend.currentPc
  mailbox.io.runtime.nextArchitecturalPc := debug.frontend.nextArchitecturalPc
  mailbox.io.runtime.frontendInstruction := debug.frontend.frontendInstruction
  mailbox.io.runtime.commitValid := debug.backend.commitValid
  mailbox.io.runtime.commitPc := debug.backend.commitPc
  mailbox.io.runtime.commitInstruction := debug.backend.commitInstruction
  mailbox.io.runtime.commitNextPc := debug.backend.commitNextPc
  mailbox.io.runtime.cycleCount := debug.backend.cycleCount
  mailbox.io.runtime.fcsr := debug.backend.fcsr
  mailbox.io.runtime.mstatus := debug.backend.mstatus
  mailbox.io.runtime.mcause := debug.backend.mcause
  mailbox.io.runtime.mepc := debug.backend.mepc
  mailbox.io.runtime.mtvec := debug.backend.mtvec
  mailbox.io.runtime.coreBusy := debug.coreBusy
  mailbox.io.runtime.dispatchFire := msoc.dispatchControl.get.dispatchFire
  msoc.dispatchControl.get.dispatchPermit := mailbox.io.dispatchPermit
  mailbox.io.runtime.backpressureReasons := debug.backpressureReasons
  mailbox.io.runtime.gprs := debug.backend.registers
  mailbox.io.runtime.fprs := debug.backend.floatingRegisters
}
