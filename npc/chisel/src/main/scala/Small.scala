package scpu

import chisel3.util._
import chisel3._

class Adder(Width: Int) extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt(Width.W))
    val b = Input(UInt(Width.W))
    val sum = Output(UInt(Width.W))
  })

  io.sum := io.a + io.b
}

class AndGate(Width: Int) extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt(Width.W))
    val b = Input(UInt(Width.W))
    val out = Output(UInt(Width.W))
  })

  io.out := io.a & io.b
}

class LeftShifter(Width: Int, count: Int) extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(Width.W))
    val out = Output(UInt(Width.W))
  })

  io.out := io.in << count
}

class Mux2_1(Width: Int) extends Module {
  val io = IO(new Bundle {
    val in0 = Input(UInt(Width.W))
    val in1 = Input(UInt(Width.W))
    val sel = Input(Bool())
    val out = Output(UInt(Width.W))
  })

  io.out := Mux(io.sel, io.in1, io.in0)
}

class Mux8_3(Width: Int = 32) extends Module {
  val io = IO(new Bundle {
    val in0 = Input(UInt(Width.W))
    val in1 = Input(UInt(Width.W))
    val in2 = Input(UInt(Width.W))
    val in3 = Input(UInt(Width.W))
    val in4 = Input(UInt(Width.W))
    val in5 = Input(UInt(Width.W))
    val in6 = Input(UInt(Width.W))
    val in7 = Input(UInt(Width.W))
    val sel = Input(UInt(3.W))
    val out = Output(UInt(Width.W))
  })

  when(io.sel === 0.U) {
    io.out := io.in0
  } .elsewhen(io.sel === 1.U) {
    io.out := io.in1
  } .elsewhen(io.sel === 2.U) {
    io.out := io.in2
  } .elsewhen(io.sel === 3.U) {
    io.out := io.in3
  } .elsewhen(io.sel === 4.U) {
    io.out := io.in4
  } .elsewhen(io.sel === 5.U) {
    io.out := io.in5
  } .elsewhen(io.sel === 6.U) {
    io.out := io.in6
  } .otherwise {
    io.out := io.in7
  }
}

class PC_Align(Width: Int = 32) extends Module {
  val io = IO(new Bundle {
    val pc_in = Input(UInt(Width.W))
    val pc_out = Output(UInt(Width.W))
  })

  io.pc_out := Cat(io.pc_in(Width-1,1), 0.U(1.W))
}

