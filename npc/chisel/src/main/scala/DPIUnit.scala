package scpu

import chisel3._
import chisel3.util._
import chisel3.experimental._

/**
 * DPI-C Memory BlackBox
 * Dual-port byte-addressable memory interface
 * 
 * Port A and Port B can operate independently
 * Each port: 1 cycle for address, 1 cycle for data
 */
//class DPIMem extends BlackBox with HasBlackBoxInline {
//  val io = IO(new Bundle {
//    val clk = Input(Clock())
//    val rst = Input(Bool())
//
//    // Port A
//    val addr_a = Input(UInt(32.W))
//    val din_a  = Input(UInt(8.W))
//    val dout_a = Output(UInt(8.W))
//    val we_a   = Input(Bool())
//    val en_a   = Input(Bool())
//
//    // Port B
//    val addr_b = Input(UInt(32.W))
//    val din_b  = Input(UInt(8.W))
//    val dout_b = Output(UInt(8.W))
//    val we_b   = Input(Bool())
//    val en_b   = Input(Bool())
//  })
//
//  setInline("DPIMem.v",
//    s"""module DPIMem(
//       |  input         clk,
//       |  input         rst,
//       |  input  [31:0] addr_a,
//       |  input  [7:0]  din_a,
//       |  output reg [7:0]  dout_a,
//       |  input         we_a,
//       |  input         en_a,
//       |  input  [31:0] addr_b,
//       |  input  [7:0]  din_b,
//       |  output reg [7:0]  dout_b,
//       |  input         we_b,
//       |  input         en_b
//       |);
//       |
//       |  // DPI-C function imports
//       |  import "DPI-C" function byte pmem_read_a(input int addr);
//       |  import "DPI-C" function void pmem_write_a(input int addr, input byte data);
//       |  import "DPI-C" function byte pmem_read_b(input int addr);
//       |  import "DPI-C" function void pmem_write_b(input int addr, input byte data);
//       |
//       |  // Port A logic
//       |  always @(posedge clk) begin
//       |    if (rst) begin
//       |      dout_a <= 8'h0;
//       |    end else if (en_a) begin
//       |      if (we_a) begin
//       |        pmem_write_a(addr_a, din_a);
//       |      end
//       |      dout_a <= pmem_read_a(addr_a);
//       |    end
//       |  end
//       |
//       |  // Port B logic
//       |  always @(posedge clk) begin
//       |    if (rst) begin
//       |      dout_b <= 8'h0;
//       |    end else if (en_b) begin
//       |      if (we_b) begin
//       |        pmem_write_b(addr_b, din_b);
//       |      end
//       |      dout_b <= pmem_read_b(addr_b);
//       |    end
//       |  end
//       |
//       |endmodule
//       |""".stripMargin)
//}


class DPIMem extends BlackBox with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())

    // Port A
    val addr_a = Input(UInt(32.W))
    val din_a  = Input(UInt(8.W))
    val dout_a = Output(UInt(8.W))
    val we_a   = Input(Bool())
    val en_a   = Input(Bool())

    // Port B
    val addr_b = Input(UInt(32.W))
    val din_b  = Input(UInt(8.W))
    val dout_b = Output(UInt(8.W))
    val we_b   = Input(Bool())
    val en_b   = Input(Bool())
  })
  
  this.addResource("/rtl/shell/DPIMem.v")

}

class ALU_Core_M extends BlackBox with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())

    val a = Input(UInt(64.W))
    val b = Input(UInt(64.W))
    val sel = Input(UInt(5.W))

    val result = Output(UInt(64.W))
  })

  this.addResource("/rtl/shell/ALU_Core_M.v")

}

class MMIO_Core() extends BlackBox with HasBlackBoxResource {
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

  this.addResource("/rtl/shell/MMIO_Core.v")

}

/**
 * DPI-C 64-bit Memory BlackBox
 * Single-port 64-bit read/write with byte-lane write mask (wstrb).
 * Used by AxiLiteDpiRamSlave for AXI4-Lite bus integration.
 * Address alignment is handled in the C++ DPI functions.
 */
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
  this.addResource("/rtl/shell/DPIMem64.v")
}

