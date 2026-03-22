package scpu

import chisel3._
import chisel3.util._

class Decoder extends Module{
  var io = IO(
    new Bundle{
      var instruction = Input(UInt(32.W))
      var busy = Input(Bool())
      var tick_ifid = Input(Bool())
      var opcode = Output(UInt(7.W))
      var funct3 = Output(UInt(3.W))
      var funct7 = Output(UInt(7.W))
      var rd = Output(UInt(5.W))
      var rs1 = Output(UInt(5.W))
      var rs2 = Output(UInt(5.W))
    }
  )

  var instruction_reg = RegInit("h00000013".U(32.W))
  when(reset.asBool){
    instruction_reg := "h00000013".U(32.W) // NOP指令
  }.elsewhen(!io.busy && io.tick_ifid){
    instruction_reg := io.instruction
  }.otherwise{
    instruction_reg := instruction_reg
  }

  io.opcode := instruction_reg(6,0)
  io.rd := instruction_reg(11,7)
  io.funct3 := instruction_reg(14,12)
  io.rs1 := instruction_reg(19,15)
  io.rs2 := instruction_reg(24,20)
  io.funct7 := instruction_reg(31,25)



}