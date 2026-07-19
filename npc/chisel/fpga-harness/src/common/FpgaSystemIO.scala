package scpu.fpga

import chisel3._
import scpu.protocol.{Axi4FullMasterIO, AxiLiteMasterIO}

/** Stable boundary between a board-neutral FPGA system and a board shell. */
class FpgaSystemIO(addrWidth: Int, dataWidth: Int, idWidth: Int) extends Bundle {
  val interrupt = Input(Bool())
  val master = new Axi4FullMasterIO(addrWidth, dataWidth, idWidth)
  val control = Flipped(new AxiLiteMasterIO(32, 32))
  val mailboxInterrupt = Output(Bool())
  val memoryHostBase = Output(UInt(64.W))
}

object FpgaSystemIO {
  def connect(shell: FpgaSystemIO, system: FpgaSystemIO): Unit = {
    system.interrupt := shell.interrupt
    shell.master <> system.master

    system.control.aw.valid := shell.control.aw.valid
    system.control.aw.bits := shell.control.aw.bits
    shell.control.aw.ready := system.control.aw.ready
    system.control.w.valid := shell.control.w.valid
    system.control.w.bits := shell.control.w.bits
    shell.control.w.ready := system.control.w.ready
    shell.control.b.valid := system.control.b.valid
    shell.control.b.bits := system.control.b.bits
    system.control.b.ready := shell.control.b.ready
    system.control.ar.valid := shell.control.ar.valid
    system.control.ar.bits := shell.control.ar.bits
    shell.control.ar.ready := system.control.ar.ready
    shell.control.r.valid := system.control.r.valid
    shell.control.r.bits := system.control.r.bits
    system.control.r.ready := shell.control.r.ready

    shell.mailboxInterrupt := system.mailboxInterrupt
    shell.memoryHostBase := system.memoryHostBase
  }
}
