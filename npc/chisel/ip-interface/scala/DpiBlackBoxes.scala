package npc.ip.memory

import chisel3._
import chisel3.util.{HasBlackBoxInline, HasBlackBoxResource}

/** NEMU RAM 的稳定 pin-level BlackBox；AXI/APB wrapper 不再声明 DPI 模型。 */
final class DpiMemory(dataWidth: Int) extends BlackBox(Map("DATA_WIDTH" -> dataWidth)) with HasBlackBoxResource {
  require(dataWidth == 32 || dataWidth == 64)
  override def desiredName: String = "DPIMem"
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())
    val addr = Input(UInt(32.W))
    val din = Input(UInt(dataWidth.W))
    val dout = Output(UInt(dataWidth.W))
    val wstrb = Input(UInt((dataWidth / 8).W))
    val ren = Input(Bool())
    val wen = Input(Bool())
  })
  addResource("/npc/ip/memory/DPIMem.v")
}

/** NEMU MMIO 的稳定 pin-level BlackBox。 */
final class DpiMmio(dataWidth: Int) extends BlackBox(Map("DATA_WIDTH" -> dataWidth)) with HasBlackBoxResource {
  require(dataWidth == 32 || dataWidth == 64)
  override def desiredName: String = "MMIOCore"
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())
    val len = Input(UInt(5.W))
    val addr = Input(UInt(32.W))
    val din = Input(UInt(dataWidth.W))
    val dout = Output(UInt(dataWidth.W))
    val strb = Input(UInt((dataWidth / 8).W))
    val we = Input(Bool())
    val re = Input(Bool())
  })
  addResource("/npc/ip/memory/MMIOCore.v")
}

/** NEMU_ABORT fault sink。 */
final class DpiMemoryFaultSink extends BlackBox with HasBlackBoxResource {
  override def desiredName: String = "MemoryFaultDpi"
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())
    val valid = Input(Bool())
    val addr = Input(UInt(32.W))
    val write = Input(Bool())
    val len = Input(UInt(4.W))
    val reason = Input(UInt(3.W))
  })
  addResource("/npc/ip/memory/MemoryFaultDpi.v")
}

/** SoC APB wrapper使用的固定 32 位 DPI RAM pin。 */
final class DpiApbRam extends BlackBox with HasBlackBoxInline {
  override def desiredName: String = "SimAPBDpiRam"
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Reset())
    val paddr = Input(UInt(32.W))
    val psel = Input(Bool())
    val penable = Input(Bool())
    val pwrite = Input(Bool())
    val pwdata = Input(UInt(32.W))
    val pstrb = Input(UInt(4.W))
    val pready = Output(Bool())
    val prdata = Output(UInt(32.W))
    val pslverr = Output(Bool())
  })
  setInline("SimAPBDpiRam.v", """module SimAPBDpiRam(input clock,input reset,input [31:0] paddr,input psel,input penable,input pwrite,input [31:0] pwdata,input [3:0] pstrb,output pready,output [31:0] prdata,output pslverr);
    import "DPI-C" function void pmem_read_word(input int addr,input int word_bytes,output longint unsigned data);
    import "DPI-C" function void pmem_write_word(input int addr,input int word_bytes,input longint unsigned data,input byte unsigned strb);
    reg [63:0] read_data;
    always @(posedge clock) begin
      if (reset) read_data <= 64'b0;
      else if (psel && !penable) begin
        if (pwrite) pmem_write_word(paddr,4,{32'b0,pwdata},pstrb);
        else pmem_read_word(paddr,4,read_data);
      end
    end
    assign pready = psel && penable;
    assign pslverr = 1'b0;
    assign prdata = read_data[31:0];
  endmodule""")
}

/** SoC APB wrapper使用的固定 32 位 MMIO pin。 */
final class DpiApbMmio extends BlackBox with HasBlackBoxInline {
  override def desiredName: String = "SimAPBDpiMmio"
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Reset())
    val paddr = Input(UInt(32.W))
    val psel = Input(Bool())
    val penable = Input(Bool())
    val pwrite = Input(Bool())
    val pwdata = Input(UInt(32.W))
    val pstrb = Input(UInt(4.W))
    val pready = Output(Bool())
    val prdata = Output(UInt(32.W))
    val pslverr = Output(Bool())
  })
  setInline("SimAPBDpiMmio.v", """module SimAPBDpiMmio(input clock,input reset,input [31:0] paddr,input psel,input penable,input pwrite,input [31:0] pwdata,input [3:0] pstrb,output pready,output [31:0] prdata,output pslverr);
    import "DPI-C" function void mmio_read_word(input int addr,input int len,input int word_bytes,output longint unsigned word_data);
    import "DPI-C" function void mmio_write_word(input int addr,input int len,input int word_bytes,input longint unsigned word_data,input byte unsigned strb);
    reg [63:0] read_data;
    function automatic integer access_len(input [3:0] strb);
      begin case (strb)
        4'b0001,4'b0010,4'b0100,4'b1000: access_len=1;
        4'b0011,4'b1100: access_len=2;
        default: access_len=4;
      endcase end
    endfunction
    always @(posedge clock) begin
      if (reset) read_data <= 64'b0;
      else if (psel && !penable) begin
        if (pwrite) mmio_write_word(paddr,access_len(pstrb),4,{32'b0,pwdata},pstrb);
        else mmio_read_word(paddr,access_len(pstrb),4,read_data);
      end
    end
    assign pready = psel && penable;
    assign pslverr = 1'b0;
    assign prdata = read_data[31:0];
  endmodule""")
}

/** SoC 仿真串口写事件 pin。 */
final class DpiPutchSink extends BlackBox with HasBlackBoxInline {
  override def desiredName: String = "SimPutchSink"
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Reset())
    val valid = Input(Bool())
    val bits = Input(UInt(8.W))
    val ready = Output(Bool())
  })
  setInline("SimPutchSink.v", """module SimPutchSink(input clock,input reset,input valid,input [7:0] bits,output ready);
    import "DPI-C" function void mmio_write_word(input int addr,input int len,input int word_bytes,input longint unsigned word_data,input byte unsigned strb);
    assign ready=1'b1;
    always @(posedge clock) if (!reset && valid) mmio_write_word(32'ha00003f8,1,4,{56'b0,bits},8'h01);
  endmodule""")
}
