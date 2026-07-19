package scpu

import chisel3._
import chisel3.util._

/** 由写回/提交边界拥有的机器态 CSR 状态。 */
class CsrFile(cfg: ISAConfig = ISAConfig()) extends Module {
  val io = IO(new Bundle {
    val address = Input(UInt(12.W))
    val writeData = Input(UInt(cfg.xlen.W))
    val writeEnable = Input(Bool())
    val accessAllowed = Input(Bool())
    val readData = Output(UInt(cfg.xlen.W))

    val externalInterrupt = Input(Bool())

    val trapEnable = Input(Bool())
    val trapCause = Input(UInt(cfg.xlen.W))
    val trapEpc = Input(UInt(cfg.xlen.W))
    val trapVector = Output(UInt(cfg.xlen.W))

    val machineExceptionPc = Output(UInt(cfg.xlen.W))

    val floatingCommit = Input(Bool())
    val floatingExceptionFlags = Input(UInt(5.W))
    val fEnabled = Output(Bool())
    val frmOut = Output(UInt(3.W))
    val fcsrOut = Output(UInt(8.W))
    val mstatusOut = Output(UInt(cfg.xlen.W))
    val mcauseOut = Output(UInt(cfg.xlen.W))
    val mtvecOut = Output(UInt(cfg.xlen.W))
  })

  val mstatus = RegInit(0.U(cfg.xlen.W))
  val mie = RegInit(0.U(cfg.xlen.W))
  val mtvec = RegInit(0.U(cfg.xlen.W))
  val mepc = RegInit(0.U(cfg.xlen.W))
  val mcause = RegInit(0.U(cfg.xlen.W))
  val mipSoftware = RegInit(0.U(cfg.xlen.W))
  val fflags = RegInit(0.U(5.W))
  val frm = RegInit(0.U(3.W))
  val mip = Cat(
    mipSoftware(cfg.xlen - 1, CsrInterruptBit.meip + 1),
    io.externalInterrupt,
    mipSoftware(CsrInterruptBit.meip - 1, 0)
  )

  io.readData := MuxLookup(io.address, 0.U)(Seq(
    CsrAddress.fflags.U -> fflags,
    CsrAddress.frm.U -> frm,
    CsrAddress.fcsr.U -> Cat(0.U((cfg.xlen - 8).W), frm, fflags),
    CsrAddress.mstatus.U -> mstatus,
    CsrAddress.mie.U -> mie,
    CsrAddress.mtvec.U -> mtvec,
    CsrAddress.mepc.U -> mepc,
    CsrAddress.mcause.U -> mcause,
    CsrAddress.mip.U -> mip,
  ))
  io.trapVector := mtvec
  io.machineExceptionPc := mepc
  io.fEnabled := cfg.F.B && mstatus(14, 13) =/= 0.U
  io.frmOut := frm
  io.fcsrOut := Cat(frm, fflags)
  io.mstatusOut := mstatus
  io.mcauseOut := mcause
  io.mtvecOut := mtvec

  when(io.trapEnable) {
    mcause := io.trapCause
    mepc := io.trapEpc
  }.elsewhen(io.floatingCommit) {
    fflags := fflags | io.floatingExceptionFlags
    mstatus := mstatus.bitSet(13.U, true.B).bitSet(14.U, true.B)
  }.elsewhen(io.writeEnable && io.accessAllowed) {
    switch(io.address) {
      is(CsrAddress.fflags.U) {
        fflags := io.writeData(4, 0)
        mstatus := mstatus.bitSet(13.U, true.B).bitSet(14.U, true.B)
      }
      is(CsrAddress.frm.U) {
        frm := io.writeData(2, 0)
        mstatus := mstatus.bitSet(13.U, true.B).bitSet(14.U, true.B)
      }
      is(CsrAddress.fcsr.U) {
        fflags := io.writeData(4, 0)
        frm := io.writeData(7, 5)
        mstatus := mstatus.bitSet(13.U, true.B).bitSet(14.U, true.B)
      }
      is(CsrAddress.mstatus.U) { mstatus := io.writeData }
      is(CsrAddress.mie.U) { mie := io.writeData }
      is(CsrAddress.mtvec.U) { mtvec := io.writeData }
      is(CsrAddress.mepc.U) { mepc := io.writeData }
      is(CsrAddress.mcause.U) { mcause := io.writeData }
      is(CsrAddress.mip.U) { mipSoftware := io.writeData }
    }
  }
}
