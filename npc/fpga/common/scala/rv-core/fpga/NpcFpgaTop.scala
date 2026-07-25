package npc.fpga

import chisel3._
import org.chipsalliance.cde.config.Parameters
import npc.NpcCore

/** Board-neutral bare-core system; DDR/HBM address conversion belongs to board RTL. */
class NpcFpgaSystem(implicit parameters: Parameters) extends Module {
  private val config = FpgaConfigParameters.npcCoreConfig
  require(config.debug.enableTopDebugIo, "NpcFpgaSystem requires runtime debug signals")

  private val width = config.isa.xlen
  private val axiConfig = config.axi
  val io = IO(new FpgaSystemIO(axiConfig.addrWidth, axiConfig.dataWidth, axiConfig.idWidth))

  val mailbox = Module(new FpgaRuntimeMailbox(width))
  val core = withReset(reset.asBool || mailbox.io.coreReset) {
    Module(new NpcCore(config, FpgaCoreComponents.forAttachment(FpgaConfigParameters.ipAttachment)))
  }
  core.io.interrupt := io.interrupt
  io.master <> core.io.master
  io.mailboxInterrupt := mailbox.io.interrupt
  io.memoryHostBase := mailbox.io.memoryHostBase
  mailbox.io.putch <> core.io.putch.get

  val debug = core.io.debug.get
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
  mailbox.io.runtime.dispatchFire := core.io.dispatchControl.get.dispatchFire
  core.io.dispatchControl.get.dispatchPermit := mailbox.io.dispatchPermit
  mailbox.io.runtime.backpressureReasons := debug.backpressureReasons
  mailbox.io.runtime.gprs := debug.backend.registers

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

}

/** Common shell for board-selected bare cores. */
abstract class NpcFpgaShell(board: FpgaBoard)(implicit parameters: Parameters) extends Module {
  private val config = FpgaConfigParameters.npcCoreConfig
  private val platform = FpgaConfigParameters.platform
  require(platform.board == board,
    s"elaboration selected ${platform.board.name}, but instantiated ${board.name} shell")
  require(FpgaConfigParameters.board.contains(board),
    s"CDE configuration selected ${FpgaConfigParameters.board.map(_.name).getOrElse("no board")}, but instantiated ${board.name} shell")
  override def desiredName: String = "NpcFpgaTop"

  private val axiConfig = config.axi
  val io = IO(new FpgaSystemIO(axiConfig.addrWidth, axiConfig.dataWidth, axiConfig.idWidth))
  private val system = Module(new NpcFpgaSystem)
  FpgaSystemIO.connect(io, system.io)
}

/** Compatibility entry point for older direct callers; it selects ZCU102. */
class NpcFpgaTop(implicit parameters: Parameters)
    extends NpcFpgaShell(FpgaBoard.Zcu102)
