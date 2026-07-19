package scpu

import chisel3._
import chisel3.util._

/** 整数寄存器文件：两个组合读端口和一个提交写端口。 */
class RegisterFile(numRegs: Int = 32, width: Int = 32, debug: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val rs1 = Input(UInt(log2Ceil(numRegs).W))
    val rs2 = Input(UInt(log2Ceil(numRegs).W))
    val rd = Input(UInt(log2Ceil(numRegs).W))
    val writeEnable = Input(Bool())
    val writeData = Input(UInt(width.W))
    val commit = Input(Bool())
    val rs1Data = Output(UInt(width.W))
    val rs2Data = Output(UInt(width.W))
    val registersOut = if (debug) Some(Output(Vec(numRegs, UInt(width.W)))) else None
  })

  val regs = RegInit(VecInit(Seq.fill(numRegs)(0.U(width.W))))

  io.rs1Data := Mux(io.rs1 === 0.U, 0.U, regs(io.rs1))
  io.rs2Data := Mux(io.rs2 === 0.U, 0.U, regs(io.rs2))
  regs(0) := 0.U

  if (debug) {
    io.registersOut.get := regs
  }

  when(io.writeEnable && io.rd =/= 0.U && io.commit) {
    regs(io.rd) := io.writeData
  }
}
