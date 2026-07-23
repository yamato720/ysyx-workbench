package scpu.ipdpishell

import chisel3._
import chisel3.util._
import chisel3.experimental._

/** 面向 NEMU 后端内存映射设备的参数化 AXI 仿真外壳。 */
class MMIOCore(dataWidth: Int) extends BlackBox(Map("DATA_WIDTH" -> dataWidth)) with HasBlackBoxResource {
  require(dataWidth == 32 || dataWidth == 64, s"MMIOCore only supports 32/64-bit words, got $dataWidth")

  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())

    val len  = Input(UInt(5.W))
    val addr = Input(UInt(32.W))
    val din  = Input(UInt(dataWidth.W))
    val dout = Output(UInt(dataWidth.W))
    val strb = Input(UInt((dataWidth / 8).W))
    val we   = Input(Bool())
    val re   = Input(Bool())
  })

  addResource("/IP-DPI-shell/MMIOCore.v")
}

/** AXI DPI RAM 外壳；每次调用恰好传输一个 XLEN 宽的对齐 beat。 */
class DPIMem(dataWidth: Int) extends BlackBox(Map("DATA_WIDTH" -> dataWidth)) with HasBlackBoxResource {
  require(dataWidth == 32 || dataWidth == 64, s"DPIMem only supports 32/64-bit words, got $dataWidth")

  val io = IO(new Bundle {
    val clk   = Input(Clock())
    val rst   = Input(Bool())
    val addr  = Input(UInt(32.W))
    val din   = Input(UInt(dataWidth.W))
    val dout  = Output(UInt(dataWidth.W))
    val wstrb = Input(UInt((dataWidth / 8).W))
    val ren   = Input(Bool())
    val wen   = Input(Bool())
  })
  addResource("/IP-DPI-shell/DPIMem.v")
}

/** 本地 Verilator/NEMU 构建将硬件访存故障转换为 NEMU_ABORT。 */
class MemoryFaultDpi extends BlackBox with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())
    val valid = Input(Bool())
    val addr = Input(UInt(32.W))
    val write = Input(Bool())
    val len = Input(UInt(4.W))
    val reason = Input(UInt(3.W))
  })

  addResource("/IP-DPI-shell/MemoryFaultDpi.v")
}
