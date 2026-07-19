package scpu

import chisel3._
import chisel3.util._

/** 执行 Zicsr 读改写操作和同步陷入。 */
class CsrExecution(cfg: ISAConfig = ISAConfig()) extends Module {
  val io = IO(new Bundle {
    val csrRequestEnable = Input(Bool())
    val csrOperation = Input(UInt(2.W))
    val csrUseImmediate = Input(Bool())
    val trapRequested = Input(Bool())
    val requestedTrapCause = Input(UInt(cfg.xlen.W))
    val mretRequested = Input(Bool())

    val capture = Input(Bool())

    val rs1Data = Input(UInt(cfg.xlen.W))
    val zimm = Input(UInt(5.W))
    val requestedCsrAddress = Input(UInt(12.W))
    val pc = Input(UInt(cfg.xlen.W))
    val previousCsrValue = Input(UInt(cfg.xlen.W))

    val csrAddress = Output(UInt(12.W))
    val csrWriteEnable = Output(Bool())
    val csrWriteData = Output(UInt(cfg.xlen.W))
    val accessAllowed = Output(Bool())
    val trapEnable = Output(Bool())
    val trapCause = Output(UInt(cfg.xlen.W))
    val trapEpc = Output(UInt(cfg.xlen.W))
    val mretEnable = Output(Bool())
    val readData = Output(UInt(cfg.xlen.W))
  })

  val validAddresses = Seq(
    CsrAddress.mvendorid, CsrAddress.marchid, CsrAddress.mimpid, CsrAddress.mhartid,
    CsrAddress.fflags, CsrAddress.frm, CsrAddress.fcsr,
    CsrAddress.mstatus, CsrAddress.misa, CsrAddress.mie, CsrAddress.mtvec,
    CsrAddress.mscratch, CsrAddress.mepc, CsrAddress.mcause, CsrAddress.mtval, CsrAddress.mip
  ).map(_.U(12.W))

  val csrEnabled = RegInit(false.B)
  val csrOperation = RegInit(0.U(2.W))
  val csrImmediate = RegInit(false.B)
  val trapEnabled = RegInit(false.B)
  val trappedCause = RegInit(0.U(cfg.xlen.W))
  val returnFromMachine = RegInit(false.B)
  val sourceRegister = RegInit(0.U(cfg.xlen.W))
  val immediate = RegInit(0.U(5.W))
  val address = RegInit(0.U(12.W))
  val faultingPc = RegInit(0.U(cfg.xlen.W))
  val previousValue = RegInit(0.U(cfg.xlen.W))

  when(io.capture) {
    csrEnabled := io.csrRequestEnable
    csrOperation := io.csrOperation
    csrImmediate := io.csrUseImmediate
    trapEnabled := io.trapRequested
    trappedCause := io.requestedTrapCause
    returnFromMachine := io.mretRequested
    sourceRegister := io.rs1Data
    immediate := io.zimm
    address := io.requestedCsrAddress
    faultingPc := io.pc
    previousValue := io.previousCsrValue
  }

  val addressValid = validAddresses.map(_ === address).reduce(_ || _)

  io.csrAddress := address
  io.accessAllowed := addressValid
  io.readData := previousValue
  io.trapEnable := trapEnabled
  io.trapEpc := faultingPc
  io.trapCause := trappedCause
  io.mretEnable := returnFromMachine

  if (cfg.Zicsr) {
    val source = Mux(csrImmediate, immediate.asTypeOf(UInt(cfg.xlen.W)), sourceRegister)
    val writeData = WireDefault(0.U(cfg.xlen.W))
    when(csrOperation === NpcCsrOp.write) {
      writeData := source
    }.elsewhen(csrOperation === NpcCsrOp.set) {
      writeData := previousValue | source
    }.elsewhen(csrOperation === NpcCsrOp.clear) {
      writeData := previousValue & ~source
    }
    io.csrWriteData := writeData
    io.csrWriteEnable := csrEnabled && addressValid
  } else {
    io.csrWriteData := 0.U
    io.csrWriteEnable := false.B
  }
}
