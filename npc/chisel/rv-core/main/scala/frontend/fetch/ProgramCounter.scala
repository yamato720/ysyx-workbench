package scpu

import chisel3._

/** 由前端拥有的架构程序计数器。 */
class ProgramCounter(
  width: Int = 32,
  resetVector: BigInt = BigInt("80000000", 16)
) extends Module {
  val io = IO(new Bundle {
    val nextPc = Input(UInt(width.W))
    val writeEnable = Input(Bool())
    val pc = Output(UInt(width.W))
    val pcPlus4 = Output(UInt(width.W))
  })

  val currentPc = RegInit(resetVector.U(width.W))
  io.pc := currentPc
  io.pcPlus4 := currentPc + 4.U

  when(io.writeEnable) {
    currentPc := io.nextPc
  }
}
