package scpu.ipdpishell

import chisel3._
import chisel3.util._
import chisel3.experimental._

/** 面向 NEMU 后端内存映射设备的 AXI 仿真外壳。 */
class MMIO_Core extends BlackBox with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())

    val len  = Input(UInt(5.W))
    val addr = Input(UInt(32.W))
    val din  = Input(UInt(64.W))
    val dout = Output(UInt(64.W))
    val we   = Input(Bool())
    val re   = Input(Bool())
  })

  addResource("/IP-DPI-shell/MMIO_Core.v")
}

/** AXI DPI RAM 外壳；地址由 AXI 适配器对齐。 */
class DPIMem64 extends BlackBox with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clk   = Input(Clock())
    val rst   = Input(Bool())
    val addr  = Input(UInt(32.W))
    val din   = Input(UInt(64.W))
    val dout  = Output(UInt(64.W))
    val wstrb = Input(UInt(8.W))
    val ren   = Input(Bool())
    val wen   = Input(Bool())
  })
  addResource("/IP-DPI-shell/DPIMem64.v")
}
