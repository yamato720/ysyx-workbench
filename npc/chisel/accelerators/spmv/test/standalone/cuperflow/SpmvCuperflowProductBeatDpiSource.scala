package accelerators.spmv.standalone.cuperflow

import chisel3._
import chisel3.util.HasBlackBoxInline

/** 稳定的 V0 fixture selector；只供 standalone L1/L2 Verilator DPI test 使用。 */
object SpmvCuperflowProductBeatDpiFixture {
  final val Full8 = 0
  final val Tail44 = 1
  final val Tail2222 = 2
  final val Pad3And1 = 3
  final val EmptyPcRow = 4
  final val EmptyBatch = 5
  final val LastShortBatch = 6
  final val SameLocalRowNextBatch = 7
  final val MultiWaveSameY = 8
  final val ExplicitZero = 9
  final val EightXSegments = 10
  final val Max = EightXSegments
}

/** 仅用于 standalone L1/L2 测试的 Verilator DPI ProductBeat source。
  *
  * 正式 Cuperflow 顶层只能接真实 `SpmvCuperflowInputTop.product`；这个 BlackBox 位于
  * Test source tree，不进入 construction、FPGA manifest 或 input-mul 数据路径。`fixture`
  * 必须在 reset 期间固定，复位释放后再改值没有定义语义。
  */
final class SpmvCuperflowProductBeatDpiSource extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val fixture = Input(UInt(4.W))
    val ready = Input(Bool())
    val valid = Output(Bool())
    val pc = Output(UInt(16.W))
    val wave = Output(UInt(16.W))
    val batch = Output(UInt(16.W))
    val beatSeq = Output(UInt(32.W))
    val laneValid = Output(UInt(8.W))
    val chunkMode = Output(UInt(2.W))
    val localRow = Output(Vec(8, UInt(13.W)))
    val rowLast = Output(Vec(8, Bool()))
    val product = Output(Vec(8, UInt(64.W)))
  })

  override def desiredName: String = "SpmvCuperflowProductBeatDpiSource"

  setInline("SpmvCuperflowProductBeatDpiSource.sv",
    """module SpmvCuperflowProductBeatDpiSource(
      |  input clock, input reset, input [3:0] fixture, input ready,
      |  output reg valid, output reg [15:0] pc, output reg [15:0] wave,
      |  output reg [15:0] batch, output reg [31:0] beatSeq,
      |  output reg [7:0] laneValid, output reg [1:0] chunkMode,
      |  output reg [12:0] localRow_0, output reg [12:0] localRow_1,
      |  output reg [12:0] localRow_2, output reg [12:0] localRow_3,
      |  output reg [12:0] localRow_4, output reg [12:0] localRow_5,
      |  output reg [12:0] localRow_6, output reg [12:0] localRow_7,
      |  output reg rowLast_0, output reg rowLast_1, output reg rowLast_2,
      |  output reg rowLast_3, output reg rowLast_4, output reg rowLast_5,
      |  output reg rowLast_6, output reg rowLast_7,
      |  output reg [63:0] product_0, output reg [63:0] product_1,
      |  output reg [63:0] product_2, output reg [63:0] product_3,
      |  output reg [63:0] product_4, output reg [63:0] product_5,
      |  output reg [63:0] product_6, output reg [63:0] product_7
      |);
      |  import "DPI-C" function void spmv_cuperflow_product_beat_dpi_reset(input int fixture);
      |  import "DPI-C" function void spmv_cuperflow_product_beat_dpi_step(
      |    input int ready, output int valid, output int pc, output int wave, output int batch,
      |    output int beatSeq, output int laneValid, output int chunkMode,
      |    output int localRow0, output int localRow1, output int localRow2, output int localRow3,
      |    output int localRow4, output int localRow5, output int localRow6, output int localRow7,
      |    output int rowLast0, output int rowLast1, output int rowLast2, output int rowLast3,
      |    output int rowLast4, output int rowLast5, output int rowLast6, output int rowLast7,
      |    output longint unsigned product0, output longint unsigned product1,
      |    output longint unsigned product2, output longint unsigned product3,
      |    output longint unsigned product4, output longint unsigned product5,
      |    output longint unsigned product6, output longint unsigned product7
      |  );
      |  integer dpiValid, dpiPc, dpiWave, dpiBatch, dpiBeatSeq, dpiLaneValid, dpiChunkMode;
      |  integer dpiLocalRow0, dpiLocalRow1, dpiLocalRow2, dpiLocalRow3;
      |  integer dpiLocalRow4, dpiLocalRow5, dpiLocalRow6, dpiLocalRow7;
      |  integer dpiRowLast0, dpiRowLast1, dpiRowLast2, dpiRowLast3;
      |  integer dpiRowLast4, dpiRowLast5, dpiRowLast6, dpiRowLast7;
      |  longint unsigned dpiProduct0, dpiProduct1, dpiProduct2, dpiProduct3;
      |  longint unsigned dpiProduct4, dpiProduct5, dpiProduct6, dpiProduct7;
      |  always @(posedge clock) begin
      |    if (reset) begin
      |      spmv_cuperflow_product_beat_dpi_reset(fixture);
      |      valid <= 1'b0; pc <= '0; wave <= '0; batch <= '0; beatSeq <= '0;
      |      laneValid <= '0; chunkMode <= '0;
      |      localRow_0 <= '0; localRow_1 <= '0; localRow_2 <= '0; localRow_3 <= '0;
      |      localRow_4 <= '0; localRow_5 <= '0; localRow_6 <= '0; localRow_7 <= '0;
      |      rowLast_0 <= 1'b0; rowLast_1 <= 1'b0; rowLast_2 <= 1'b0; rowLast_3 <= 1'b0;
      |      rowLast_4 <= 1'b0; rowLast_5 <= 1'b0; rowLast_6 <= 1'b0; rowLast_7 <= 1'b0;
      |      product_0 <= '0; product_1 <= '0; product_2 <= '0; product_3 <= '0;
      |      product_4 <= '0; product_5 <= '0; product_6 <= '0; product_7 <= '0;
      |    end else begin
      |      spmv_cuperflow_product_beat_dpi_step(valid && ready,
      |        dpiValid, dpiPc, dpiWave, dpiBatch, dpiBeatSeq, dpiLaneValid, dpiChunkMode,
      |        dpiLocalRow0, dpiLocalRow1, dpiLocalRow2, dpiLocalRow3,
      |        dpiLocalRow4, dpiLocalRow5, dpiLocalRow6, dpiLocalRow7,
      |        dpiRowLast0, dpiRowLast1, dpiRowLast2, dpiRowLast3,
      |        dpiRowLast4, dpiRowLast5, dpiRowLast6, dpiRowLast7,
      |        dpiProduct0, dpiProduct1, dpiProduct2, dpiProduct3,
      |        dpiProduct4, dpiProduct5, dpiProduct6, dpiProduct7);
      |      valid <= dpiValid; pc <= dpiPc; wave <= dpiWave; batch <= dpiBatch;
      |      beatSeq <= dpiBeatSeq; laneValid <= dpiLaneValid; chunkMode <= dpiChunkMode;
      |      localRow_0 <= dpiLocalRow0; localRow_1 <= dpiLocalRow1;
      |      localRow_2 <= dpiLocalRow2; localRow_3 <= dpiLocalRow3;
      |      localRow_4 <= dpiLocalRow4; localRow_5 <= dpiLocalRow5;
      |      localRow_6 <= dpiLocalRow6; localRow_7 <= dpiLocalRow7;
      |      rowLast_0 <= dpiRowLast0; rowLast_1 <= dpiRowLast1;
      |      rowLast_2 <= dpiRowLast2; rowLast_3 <= dpiRowLast3;
      |      rowLast_4 <= dpiRowLast4; rowLast_5 <= dpiRowLast5;
      |      rowLast_6 <= dpiRowLast6; rowLast_7 <= dpiRowLast7;
      |      product_0 <= dpiProduct0; product_1 <= dpiProduct1;
      |      product_2 <= dpiProduct2; product_3 <= dpiProduct3;
      |      product_4 <= dpiProduct4; product_5 <= dpiProduct5;
      |      product_6 <= dpiProduct6; product_7 <= dpiProduct7;
      |    end
      |  end
      |endmodule
      |""".stripMargin)
}
