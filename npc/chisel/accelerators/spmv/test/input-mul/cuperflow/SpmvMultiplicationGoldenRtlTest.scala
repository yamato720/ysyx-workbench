package accelerators.spmv.inputmul.cuperflow

import _root_.circt.stage.ChiselStage
import accelerators.spmv.{SpmvCuperflowConfig, SpmvInputConfig, SpmvXPortSchedule}
import accelerators.spmv.inputmul.common.{SpmvLocalX, SpmvMulEngine}
import chisel3._
import chisel3.util._
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import org.scalatest.flatspec.AnyFlatSpec
import scala.io.Source
import scala.jdk.CollectionConverters._

/** 三种输入模式共用的乘法结果 golden harness。
  *
  * 这里故意绕开 HBM reader，只验证进入乘法核心前后不可替代的部分：X 写入和读取、
  * Cuperflow marker 解码、FP32 到 FP64 提升，以及 FP64 FMUL 的返回位型。HBM reader
  * 自身由 reader 单元测试覆盖。
  */
private[cuperflow] sealed trait SpmvGoldenMode {
  def label: String
}

private[cuperflow] object SpmvGoldenMode {
  case object Cuperflow extends SpmvGoldenMode {
    override val label: String = "cuperflow"
  }

  case object Preload extends SpmvGoldenMode {
    override val label: String = "preload-x"
  }

  case object PingPong extends SpmvGoldenMode {
    override val label: String = "ax-pingpong"
  }
}

/** 只给 RTL golden test 使用的单个乘法通路包装。 */
private[cuperflow] final class SpmvMultiplicationGoldenHarness(mode: SpmvGoldenMode) extends Module {
  private val inputConfig = SpmvInputConfig(
    aReaderCount = 8,
    hbmChannelCount = 8,
    hbmBase = 0,
    hbmBytes = 8192,
    xPortSchedule = mode match {
      case SpmvGoldenMode.PingPong => SpmvXPortSchedule.PingPong
      case _ => SpmvXPortSchedule.Preload
    }
  )
  private val cuperflowConfig = SpmvCuperflowConfig(
    hbmPcCount = 8,
    hbmBase = 0,
    hbmBytes = 8192,
    xRegionBytes = 4096
  )
  private val mulConfig = if (mode == SpmvGoldenMode.Cuperflow) {
    cuperflowConfig.mulConfig
  } else {
    inputConfig
  }
  private val slotsPerBeat = 8

  val io = IO(new Bundle {
    val xClear = Input(Bool())
    val xBeatValid = Input(Bool())
    val xBeatReady = Output(Bool())
    val xBeatData = Input(UInt(512.W))
    val xBeatValidWords = Input(UInt(log2Ceil(cuperflowConfig.xWordsPerBeat + 1).W))
    val xBeatLast = Input(Bool())
    val xRangeElements = Input(UInt(log2Ceil(cuperflowConfig.xWindowSize + 1).W))
    val xActivate = Input(Bool())
    val xWriteValid = Input(Bool())
    val xWriteColumn = Input(UInt(log2Ceil(inputConfig.xWindowSize).W))
    val xWriteElements = Input(Vec(slotsPerBeat, UInt(64.W)))
    val xWindowReady = Input(Bool())
    val aValid = Input(Bool())
    val aReady = Output(Bool())
    val aData = Input(UInt(512.W))
    val aLast = Input(Bool())
    val streamsComplete = Input(Bool())
    val productValid = Output(Vec(slotsPerBeat, Bool()))
    val productData = Output(Vec(slotsPerBeat, UInt(64.W)))
    val error = Output(Bool())
  })

  private val mul = Module(new SpmvMulEngine(mulConfig, channel = 0))
  mul.io.enable := true.B
  mul.io.clearChecksum := false.B
  mul.io.batch := 0.U
  mul.io.workExpected := true.B
  mul.io.aSlotValidMask := "hff".U(mulConfig.fp64MultiplyLaneCount.W)
  mul.io.pageReady := VecInit(Seq.fill(mulConfig.xWindowSize / 64)(true.B))
  mul.io.xWindowReady := io.xWindowReady
  mul.io.portSafeOverlap := (mode == SpmvGoldenMode.PingPong).B && io.xWriteValid && !io.xWindowReady
  mul.io.streamsComplete := io.streamsComplete
  mul.io.a.valid := io.aValid
  mul.io.a.bits.data := io.aData
  mul.io.a.bits.last := io.aLast
  mul.io.a.bits.error := false.B
  io.aReady := mul.io.a.ready
  mul.io.product.zipWithIndex.foreach { case (product, lane) =>
    product.ready := true.B
    io.productValid(lane) := product.valid
    io.productData(lane) := product.bits.product
  }

  mode match {
    case SpmvGoldenMode.Cuperflow =>
      val decoder = Module(new SpmvCuperflowXDecoder8(cuperflowConfig))
      val localX = Module(new SpmvCuperflowLocalX(cuperflowConfig))
      decoder.io.clear := io.xClear
      decoder.io.rangeElements := io.xRangeElements
      decoder.io.input.valid := io.xBeatValid
      decoder.io.input.bits.data := io.xBeatData
      decoder.io.input.bits.validWords := io.xBeatValidWords
      decoder.io.input.bits.last := io.xBeatLast
      io.xBeatReady := decoder.io.input.ready
      localX.io.write <> decoder.io.write
      localX.io.loadBank := !localX.io.activeBank
      localX.io.activate := io.xActivate
      localX.io.readEnable := mul.io.xReadEnable
      localX.io.readColumn := mul.io.xReadColumn
      mul.io.xReadData := localX.io.readData
      io.error := decoder.io.error || mul.io.error

    case _ =>
      val localX = Module(new SpmvLocalX(inputConfig))
      io.xBeatReady := false.B
      localX.io.writeValid := io.xWriteValid
      localX.io.writeColumn := io.xWriteColumn
      localX.io.writeElements := io.xWriteElements
      localX.io.writeMask := VecInit(Seq.fill(slotsPerBeat)(io.xWriteValid))
      localX.io.readEnable := mul.io.xReadEnable
      localX.io.readColumn := mul.io.xReadColumn
      mul.io.xReadData := localX.io.readData
      io.error := mul.io.error
  }

  override def desiredName: String = "SpmvMultiplicationGoldenHarness"
}

class SpmvMultiplicationGoldenRtlTest extends AnyFlatSpec {
  private val testbench =
    """module SpmvMultiplicationGoldenTb #(
      |  parameter integer MODE = 0
      |);
      |  logic clock = 1'b0;
      |  logic reset = 1'b1;
      |  logic io_xClear;
      |  logic io_xBeatValid;
      |  wire  io_xBeatReady;
      |  logic [511:0] io_xBeatData;
      |  logic [3:0] io_xBeatValidWords;
      |  logic io_xBeatLast;
      |  logic [13:0] io_xRangeElements;
      |  logic io_xActivate;
      |  logic io_xWriteValid;
      |  logic [12:0] io_xWriteColumn;
      |  logic [63:0] io_xWriteElements_0;
      |  logic [63:0] io_xWriteElements_1;
      |  logic [63:0] io_xWriteElements_2;
      |  logic [63:0] io_xWriteElements_3;
      |  logic [63:0] io_xWriteElements_4;
      |  logic [63:0] io_xWriteElements_5;
      |  logic [63:0] io_xWriteElements_6;
      |  logic [63:0] io_xWriteElements_7;
      |  logic io_xWindowReady;
      |  logic io_aValid;
      |  wire  io_aReady;
      |  logic [511:0] io_aData;
      |  logic io_aLast;
      |  logic io_streamsComplete;
      |  wire io_productValid_0;
      |  wire io_productValid_1;
      |  wire io_productValid_2;
      |  wire io_productValid_3;
      |  wire io_productValid_4;
      |  wire io_productValid_5;
      |  wire io_productValid_6;
      |  wire io_productValid_7;
      |  wire [63:0] io_productData_0;
      |  wire [63:0] io_productData_1;
      |  wire [63:0] io_productData_2;
      |  wire [63:0] io_productData_3;
      |  wire [63:0] io_productData_4;
      |  wire [63:0] io_productData_5;
      |  wire [63:0] io_productData_6;
      |  wire [63:0] io_productData_7;
      |  wire io_error;
      |
      |  wire [7:0] productValid = {
      |    io_productValid_7, io_productValid_6, io_productValid_5, io_productValid_4,
      |    io_productValid_3, io_productValid_2, io_productValid_1, io_productValid_0
      |  };
      |  wire [63:0] productData [0:7];
      |  assign productData[0] = io_productData_0;
      |  assign productData[1] = io_productData_1;
      |  assign productData[2] = io_productData_2;
      |  assign productData[3] = io_productData_3;
      |  assign productData[4] = io_productData_4;
      |  assign productData[5] = io_productData_5;
      |  assign productData[6] = io_productData_6;
      |  assign productData[7] = io_productData_7;
      |
      |  SpmvMultiplicationGoldenHarness dut (.*);
      |
      |  always #5 clock = ~clock;
      |
      |  function automatic [63:0] slot(
      |    input [12:0] column,
      |    input [2:0] tag,
      |    input [15:0] row,
      |    input [31:0] value
      |  );
      |    slot = {column, tag, row, value};
      |  endfunction
      |
      |  function automatic [63:0] marker(input [12:0] address);
      |    marker = 64'h7ff9000034b4a000 | address;
      |  endfunction
      |
      |  logic [31:0] aValues [0:7];
      |  logic [12:0] columns [0:7];
      |  logic [63:0] expected [0:7];
      |  logic [63:0] xValues [0:7];
      |  logic [7:0] seen;
      |  integer lane;
      |
      |  always @(posedge clock) begin
      |    for (lane = 0; lane < 8; lane = lane + 1) begin
      |      if (productValid[lane]) begin
      |        if (productData[lane] !== expected[lane]) begin
      |          $fatal(1, "lane %0d product mismatch: got %h expected %h", lane,
      |            productData[lane], expected[lane]);
      |        end
      |        seen[lane] <= 1'b1;
      |      end
      |    end
      |  end
      |
      |  task automatic prepare_a_beat;
      |    integer index;
      |    begin
      |      io_aData = '0;
      |      for (index = 0; index < 8; index = index + 1) begin
      |        io_aData[index * 64 +: 64] = slot(columns[index], index[2:0], 16'h20 + index,
      |          aValues[index]);
      |      end
      |    end
      |  endtask
      |
      |  task automatic send_x_beat(input [511:0] beat, input [3:0] validWords, input bit last);
      |    begin
      |      @(negedge clock);
      |      io_xBeatData = beat;
      |      io_xBeatValidWords = validWords;
      |      io_xBeatLast = last;
      |      io_xBeatValid = 1'b1;
      |      while (!io_xBeatReady) @(negedge clock);
      |      @(posedge clock);
      |      @(negedge clock);
      |      io_xBeatValid = 1'b0;
      |      io_xBeatLast = 1'b0;
      |    end
      |  endtask
      |
      |  task automatic send_a;
      |    begin
      |      @(negedge clock);
      |      io_aValid = 1'b1;
      |      while (!io_aReady) @(negedge clock);
      |      @(posedge clock);
      |      @(negedge clock);
      |      io_aValid = 1'b0;
      |      io_streamsComplete = 1'b1;
      |    end
      |  endtask
      |
      |  task automatic wait_for_products;
      |    integer timeout;
      |    begin
      |      timeout = 0;
      |      while (seen != 8'hff && timeout < 80) begin
      |        @(posedge clock);
      |        timeout = timeout + 1;
      |      end
      |      if (seen != 8'hff) $fatal(1, "products timed out, seen=%b", seen);
      |      if (io_error) $fatal(1, "multiplication path raised error");
      |    end
      |  endtask
      |
      |  initial begin
      |    io_xClear = 1'b0;
      |    io_xBeatValid = 1'b0;
      |    io_xBeatData = '0;
      |    io_xBeatValidWords = '0;
      |    io_xBeatLast = 1'b0;
      |    io_xRangeElements = 14'd13;
      |    io_xActivate = 1'b0;
      |    io_xWriteValid = 1'b0;
      |    io_xWriteColumn = '0;
      |    io_xWriteElements_0 = '0;
      |    io_xWriteElements_1 = '0;
      |    io_xWriteElements_2 = '0;
      |    io_xWriteElements_3 = '0;
      |    io_xWriteElements_4 = '0;
      |    io_xWriteElements_5 = '0;
      |    io_xWriteElements_6 = '0;
      |    io_xWriteElements_7 = '0;
      |    io_xWindowReady = 1'b0;
      |    io_aValid = 1'b0;
      |    io_aData = '0;
      |    io_aLast = 1'b1;
      |    io_streamsComplete = 1'b0;
      |    seen = '0;
      |
      |    aValues[0] = 32'h3fc00000;  // 1.5
      |    aValues[1] = 32'hc0000000;  // -2.0
      |    aValues[2] = 32'h3e800000;  // 0.25
      |    aValues[3] = 32'h40400000;  // 3.0
      |    aValues[4] = 32'hbf000000;  // -0.5
      |    aValues[5] = 32'h40800000;  // 4.0
      |    aValues[6] = 32'h3fa00000;  // 1.25
      |    aValues[7] = 32'hbf800000;  // -1.0
      |    xValues[0] = 64'h4000000000000000;  // 2.0
      |    xValues[1] = 64'h3fe0000000000000;  // 0.5
      |    xValues[2] = 64'hc010000000000000;  // -4.0
      |    xValues[3] = 64'h3ff4000000000000;  // 1.25
      |    xValues[4] = 64'h4020000000000000;  // 8.0
      |    xValues[5] = 64'hbfe8000000000000;  // -0.75
      |    xValues[6] = 64'h3fe0000000000000;  // 0.5
      |    xValues[7] = 64'h4008000000000000;  // 3.0
      |    expected[0] = 64'h4008000000000000;  // 3.0
      |    expected[1] = 64'hbff0000000000000;  // -1.0
      |    expected[2] = 64'hbff0000000000000;  // -1.0
      |    expected[3] = 64'h400e000000000000;  // 3.75
      |    expected[4] = 64'hc010000000000000;  // -4.0
      |    expected[5] = 64'hc008000000000000;  // -3.0
      |    expected[6] = 64'h3fe4000000000000;  // 0.625
      |    expected[7] = 64'hc008000000000000;  // -3.0
      |
      |    repeat (3) @(posedge clock);
      |    @(negedge clock);
      |    reset = 1'b0;
      |
      |    if (MODE == 0) begin
      |      columns[0] = 13'd0;
      |      columns[1] = 13'd1;
      |      columns[2] = 13'd4;
      |      columns[3] = 13'd5;
      |      columns[4] = 13'd6;
      |      columns[5] = 13'd10;
      |      columns[6] = 13'd11;
      |      columns[7] = 13'd12;
      |      prepare_a_beat();
      |
      |      @(negedge clock);
      |      io_xClear = 1'b1;
      |      @(posedge clock);
      |      @(negedge clock);
      |      io_xClear = 1'b0;
      |      io_xBeatData = '0;
      |      io_xBeatData[0 +: 64] = xValues[0];
      |      io_xBeatData[64 +: 64] = xValues[1];
      |      io_xBeatData[128 +: 64] = marker(13'd4);
      |      io_xBeatData[192 +: 64] = xValues[2];
      |      io_xBeatData[256 +: 64] = xValues[3];
      |      io_xBeatData[320 +: 64] = xValues[4];
      |      io_xBeatData[384 +: 64] = marker(13'd10);
      |      io_xBeatData[448 +: 64] = xValues[5];
      |      send_x_beat(io_xBeatData, 4'd8, 1'b0);
      |      io_xBeatData = '0;
      |      io_xBeatData[0 +: 64] = xValues[6];
      |      io_xBeatData[64 +: 64] = xValues[7];
      |      send_x_beat(io_xBeatData, 4'd2, 1'b1);
      |      repeat (3) @(posedge clock);
      |      @(negedge clock);
      |      io_xActivate = 1'b1;
      |      @(posedge clock);
      |      @(negedge clock);
      |      io_xActivate = 1'b0;
      |      io_xWindowReady = 1'b1;
      |      send_a();
      |    end else begin
      |      for (lane = 0; lane < 8; lane = lane + 1) columns[lane] = lane;
      |      prepare_a_beat();
      |      io_xWriteElements_0 = xValues[0];
      |      io_xWriteElements_1 = xValues[1];
      |      io_xWriteElements_2 = xValues[2];
      |      io_xWriteElements_3 = xValues[3];
      |      io_xWriteElements_4 = xValues[4];
      |      io_xWriteElements_5 = xValues[5];
      |      io_xWriteElements_6 = xValues[6];
      |      io_xWriteElements_7 = xValues[7];
      |      if (MODE == 1) begin
      |        @(negedge clock);
      |        io_xWriteValid = 1'b1;
      |        @(posedge clock);
      |        @(negedge clock);
      |        io_xWriteValid = 1'b0;
      |        io_xWindowReady = 1'b1;
      |        send_a();
      |      end else begin
      |        @(negedge clock);
      |        io_xWriteValid = 1'b1;
      |        io_aValid = 1'b1;
      |        if (!io_aReady) $fatal(1, "pingpong A beat was not accepted with a ready X page");
      |        @(posedge clock);
      |        @(negedge clock);
      |        io_xWriteValid = 1'b0;
      |        io_aValid = 1'b0;
      |        io_xWindowReady = 1'b1;
      |        io_streamsComplete = 1'b1;
      |      end
      |    end
      |
      |    wait_for_products();
      |    $display("Spmv multiplication golden passed: mode=%0d", MODE);
      |    $finish;
      |  end
      |endmodule
      |""".stripMargin

  private def deleteTree(root: Path): Unit = {
    val paths = Files.walk(root)
    try paths.iterator.asScala.toVector.reverse.foreach(Files.deleteIfExists)
    finally paths.close()
  }

  private def runCommand(command: Seq[String], directory: Path): Unit = {
    val process = new ProcessBuilder(command: _*)
      .directory(directory.toFile)
      .redirectErrorStream(true)
      .start()
    val output = Source.fromInputStream(process.getInputStream)(scala.io.Codec.UTF8).mkString
    val exit = process.waitFor()
    assert(exit == 0, s"命令失败（$exit）：${command.mkString(" ")}\n$output")
  }

  private def runGolden(mode: SpmvGoldenMode, modeNumber: Int): Unit = {
    val directory = Files.createTempDirectory(s"spmv-${mode.label}-golden-")
    try {
      ChiselStage.emitSystemVerilogFile(
        new SpmvMultiplicationGoldenHarness(mode),
        Array("--target-dir", directory.toString, "--split-verilog"),
        Array("--disable-annotation-unknown")
      )
      val testbenchPath = directory.resolve("SpmvMultiplicationGoldenTb.sv")
      Files.writeString(testbenchPath, testbench, StandardCharsets.UTF_8)
      val outputDirectory = directory.resolve("verilator")
      runCommand(Seq(
        "verilator", "--binary", "--timing", "-Wno-fatal", "-Wno-WIDTHTRUNC",
        "-Wno-WIDTHEXPAND", "--top-module", "SpmvMultiplicationGoldenTb",
        s"-GMODE=$modeNumber",
        "--Mdir", outputDirectory.toString,
        "-f", directory.resolve("filelist.f").toString,
        "-f", directory.resolve("firrtl_black_box_resource_files.f").toString,
        testbenchPath.toString
      ), directory)
      runCommand(Seq(outputDirectory.resolve("VSpmvMultiplicationGoldenTb").toString), directory)
    } finally {
      deleteTree(directory)
    }
  }

  "SPMV 乘法通路" should "逐 lane 匹配 Cuperflow、preload-X 与 AX pingpong 的 FP64 golden" in {
    runGolden(SpmvGoldenMode.Cuperflow, 0)
    runGolden(SpmvGoldenMode.Preload, 1)
    runGolden(SpmvGoldenMode.PingPong, 2)
  }
}
