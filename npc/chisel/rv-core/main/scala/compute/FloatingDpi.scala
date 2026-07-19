package scpu

import chisel3._
import chisel3.experimental._
import chisel3.util._

/** 仅供 Verilator 使用的 binary32 执行外壳。
  *
  * 宿主实现使用 Berkeley SoftFloat，使仿真模型和整数算术模型一样，对每个接受的
  * 请求给出一个组合结果。时序仍由 [[ArithmeticIpModel]] 负责；FPGA IP 路径不会
  * 选择该外壳。
  */
private[scpu] class NpcFloatingPointDpi extends BlackBox with HasBlackBoxResource {
  override def desiredName: String = "NpcFloatingPointDpi"

  val io = IO(new Bundle {
    val valid = Input(Bool())
    val operandA = Input(UInt(64.W))
    val operandB = Input(UInt(64.W))
    val operandC = Input(UInt(64.W))
    val operation = Input(UInt(ArithmeticOperation.width.W))
    val roundingMode = Input(UInt(3.W))
    val xlen = Input(UInt(7.W))
    val result = Output(UInt(64.W))
    val exceptionFlags = Output(UInt(5.W))
  })

  addResource("/IP-DPI-shell/NpcFloatingPointDpi.sv")
}
