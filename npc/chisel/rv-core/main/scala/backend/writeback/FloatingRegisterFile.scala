package scpu

import chisel3._
import chisel3.util._

/** 架构浮点寄存器文件。
  *
  * 与整数寄存器文件不同，f0 是普通可写寄存器。RV32F 直接保存 binary32；RV64F
  * 在 XLEN 宽寄存器的低 32 位保存经 NaN-boxing 的数值。
  */
class FloatingRegisterFile(width: Int) extends Module {
  require(width == 32 || width == 64, s"FloatingRegisterFile supports RV32/RV64, got $width")

  val io = IO(new Bundle {
    val rs1 = Input(UInt(5.W))
    val rs2 = Input(UInt(5.W))
    val rs3 = Input(UInt(5.W))
    val rd = Input(UInt(5.W))
    val writeEnable = Input(Bool())
    val writeData = Input(UInt(width.W))
    val commit = Input(Bool())
    val rs1Data = Output(UInt(width.W))
    val rs2Data = Output(UInt(width.W))
    val rs3Data = Output(UInt(width.W))
    val registersOut = Output(Vec(32, UInt(width.W)))
  })

  val registers = RegInit(VecInit(Seq.fill(32)(0.U(width.W))))
  io.rs1Data := registers(io.rs1)
  io.rs2Data := registers(io.rs2)
  io.rs3Data := registers(io.rs3)
  io.registersOut := registers

  when(io.commit && io.writeEnable) {
    if (width == 64) {
      registers(io.rd) := Cat(Fill(32, 1.U(1.W)), io.writeData(31, 0))
    } else {
      registers(io.rd) := io.writeData
    }
  }
}
