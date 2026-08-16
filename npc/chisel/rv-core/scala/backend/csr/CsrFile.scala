package npc

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
    val externalInterruptTrapVector = Output(UInt(cfg.xlen.W))
    val machineExternalInterruptPending = Output(Bool())
    val mret = Input(Bool())

    val machineExceptionPc = Output(UInt(cfg.xlen.W))

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
  val mtestexit = RegInit(0.U(cfg.xlen.W))
  val mip = Cat(
    mipSoftware(cfg.xlen - 1, CsrInterruptBit.meip + 1),
    io.externalInterrupt,
    mipSoftware(CsrInterruptBit.meip - 1, 0)
  )
  private val misaExtensions =
    (BigInt(1) << ('i' - 'a')) |
      (if (cfg.M) BigInt(1) << ('m' - 'a') else BigInt(0))
  private val misaMxl = BigInt(if (cfg.xlen == 64) 2 else 1) << (cfg.xlen - 2)
  private val misa = (misaMxl | misaExtensions).U(cfg.xlen.W)

  io.readData := MuxLookup(io.address, 0.U)(Seq(
    CsrAddress.mstatus.U -> mstatus,
    CsrAddress.misa.U -> misa,
    CsrAddress.mie.U -> mie,
    CsrAddress.mtvec.U -> mtvec,
    CsrAddress.mepc.U -> mepc,
    CsrAddress.mcause.U -> mcause,
    CsrAddress.mip.U -> mip,
    CsrAddress.mtestexit.U -> mtestexit,
  ))
  val mtvecBase = Cat(mtvec(cfg.xlen - 1, 2), 0.U(2.W))
  io.trapVector := mtvecBase
  io.externalInterruptTrapVector := Mux(mtvec(1, 0) === 1.U,
    mtvecBase + (CsrCause.machineExternalInterrupt * 4).U(cfg.xlen.W), mtvecBase)
  io.machineExternalInterruptPending := mstatus(CsrStatusBit.mie) &&
    mie(CsrInterruptBit.meip) && mip(CsrInterruptBit.meip)
  io.machineExceptionPc := mepc
  io.mstatusOut := mstatus
  io.mcauseOut := mcause
  io.mtvecOut := mtvec

  when(io.trapEnable) {
    mcause := io.trapCause
    mepc := io.trapEpc
    mstatus := mstatus
      .bitSet(CsrStatusBit.mpie.U, mstatus(CsrStatusBit.mie))
      .bitSet(CsrStatusBit.mie.U, false.B)
      .bitSet(CsrStatusBit.mppLow.U, true.B)
      .bitSet(CsrStatusBit.mppHigh.U, true.B)
  }.elsewhen(io.mret) {
    mstatus := mstatus
      .bitSet(CsrStatusBit.mie.U, mstatus(CsrStatusBit.mpie))
      .bitSet(CsrStatusBit.mpie.U, true.B)
      .bitSet(CsrStatusBit.mppLow.U, false.B)
      .bitSet(CsrStatusBit.mppHigh.U, false.B)
  }.elsewhen(io.writeEnable && io.accessAllowed) {
    switch(io.address) {
      is(CsrAddress.mstatus.U) { mstatus := io.writeData }
      is(CsrAddress.mie.U) { mie := io.writeData }
      is(CsrAddress.mtvec.U) { mtvec := io.writeData }
      is(CsrAddress.mepc.U) { mepc := io.writeData }
      is(CsrAddress.mcause.U) { mcause := io.writeData }
      is(CsrAddress.mip.U) { mipSoftware := io.writeData }
      is(CsrAddress.mtestexit.U) { mtestexit := io.writeData }
    }
  }
}
