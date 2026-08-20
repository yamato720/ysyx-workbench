package accelerators.spmv.l1.cuperflow

import _root_.circt.stage.ChiselStage
import accelerators.spmv.standalone.cuperflow.{SpmvCuperflowProductBeatDpiFixture, SpmvCuperflowProductBeatDpiSource}
import chisel3._
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import org.scalatest.flatspec.AnyFlatSpec
import scala.io.Source
import scala.jdk.CollectionConverters._

private[cuperflow] final class SpmvCuperflowProductBeatDpiHarness(fixture: Int) extends Module {
  require(fixture >= 0 && fixture <= SpmvCuperflowProductBeatDpiFixture.Max,
    s"Cuperflow ProductBeat DPI fixture 越界：$fixture")

  private val source = Module(new SpmvCuperflowProductBeatDpiSource)
  val io = IO(new Bundle {
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
  source.io.clock := clock
  source.io.reset := reset.asBool
  source.io.fixture := fixture.U
  source.io.ready := io.ready
  io.valid := source.io.valid
  io.pc := source.io.pc
  io.wave := source.io.wave
  io.batch := source.io.batch
  io.beatSeq := source.io.beatSeq
  io.laneValid := source.io.laneValid
  io.chunkMode := source.io.chunkMode
  io.localRow := source.io.localRow
  io.rowLast := source.io.rowLast
  io.product := source.io.product
}

class SpmvCuperflowProductBeatDpiTest extends AnyFlatSpec {
  private val full8Testbench =
    """module SpmvCuperflowProductBeatDpiTb;
      |  logic clock = 1'b0;
      |  logic reset = 1'b1;
      |  logic io_ready;
      |  wire io_valid;
      |  wire [15:0] io_pc, io_wave, io_batch;
      |  wire [31:0] io_beatSeq;
      |  wire [7:0] io_laneValid;
      |  wire [1:0] io_chunkMode;
      |  wire [12:0] io_localRow_0, io_localRow_1, io_localRow_2, io_localRow_3;
      |  wire [12:0] io_localRow_4, io_localRow_5, io_localRow_6, io_localRow_7;
      |  wire io_rowLast_0, io_rowLast_1, io_rowLast_2, io_rowLast_3;
      |  wire io_rowLast_4, io_rowLast_5, io_rowLast_6, io_rowLast_7;
      |  wire [63:0] io_product_0, io_product_1, io_product_2, io_product_3;
      |  wire [63:0] io_product_4, io_product_5, io_product_6, io_product_7;
      |  SpmvCuperflowProductBeatDpiHarness dut (.*);
      |  always #5 clock = ~clock;
      |  task automatic check_first;
      |    begin
      |      if (!io_valid || io_pc != 0 || io_wave != 0 || io_batch != 0 || io_beatSeq != 0 ||
      |          io_laneValid != 8'hff || io_chunkMode != 2'b00 ||
      |          io_localRow_0 != 0 || io_localRow_7 != 0 || !io_rowLast_0 || !io_rowLast_7 ||
      |          io_product_0 != 64'h3fd0000000000000 || io_product_1 != 64'h4004000000000000)
      |        $fatal(1, "DPI ProductBeat 未使用 full8 package/golden 的冻结首 beat");
      |    end
      |  endtask
      |  initial begin
      |    io_ready = 1'b0;
      |    repeat (2) @(posedge clock);
      |    @(negedge clock); reset = 1'b0;
      |    @(posedge clock); #1; check_first();
      |    repeat (3) begin @(posedge clock); #1; check_first(); end
      |    @(negedge clock); io_ready = 1'b1;
      |    @(posedge clock); #1;
      |    if (!io_valid || io_beatSeq != 1 || io_localRow_0 != 1 || io_rowLast_0)
      |      $fatal(1, "DPI ProductBeat 在第一次握手后没有推进到第二个 full8 beat");
      |    @(posedge clock); #1;
      |    if (!io_valid || io_beatSeq != 2 || io_localRow_0 != 1 || !io_rowLast_0)
      |      $fatal(1, "DPI ProductBeat 没有保留最后一个 rowLast beat");
      |    @(posedge clock); #1;
      |    if (io_valid) $fatal(1, "DPI ProductBeat 在全部 golden beat 消费后仍保持 valid");
      |    $finish;
      |  end
      |endmodule
      |""".stripMargin

  private val tail44Testbench =
    """module SpmvCuperflowProductBeatDpiTb;
      |  logic clock = 1'b0;
      |  logic reset = 1'b1;
      |  logic io_ready;
      |  wire io_valid;
      |  wire [7:0] io_laneValid;
      |  wire [1:0] io_chunkMode;
      |  wire [12:0] io_localRow_0, io_localRow_3, io_localRow_4, io_localRow_7;
      |  wire io_rowLast_0, io_rowLast_3, io_rowLast_4, io_rowLast_7;
      |  SpmvCuperflowProductBeatDpiHarness dut (
      |    .clock(clock), .reset(reset), .io_ready(io_ready), .io_valid(io_valid),
      |    .io_laneValid(io_laneValid), .io_chunkMode(io_chunkMode),
      |    .io_localRow_0(io_localRow_0), .io_localRow_3(io_localRow_3),
      |    .io_localRow_4(io_localRow_4), .io_localRow_7(io_localRow_7),
      |    .io_rowLast_0(io_rowLast_0), .io_rowLast_3(io_rowLast_3),
      |    .io_rowLast_4(io_rowLast_4), .io_rowLast_7(io_rowLast_7)
      |  );
      |  always #5 clock = ~clock;
      |  initial begin
      |    io_ready = 1'b0;
      |    repeat (2) @(posedge clock);
      |    @(negedge clock); reset = 1'b0;
      |    @(posedge clock); #1;
      |    if (!io_valid || io_laneValid != 8'hff || io_chunkMode != 2'b01 ||
      |        io_localRow_0 != 0 || io_localRow_3 != 0 || io_localRow_4 != 1 ||
      |        io_localRow_7 != 1 || !io_rowLast_0 || !io_rowLast_3 || !io_rowLast_4 ||
      |        !io_rowLast_7)
      |      $fatal(1, "DPI ProductBeat 未选择 tail44 的 4+4 golden beat");
      |    @(negedge clock); io_ready = 1'b1;
      |    @(posedge clock); #1;
      |    if (io_valid) $fatal(1, "tail44 唯一 ProductBeat 消费后仍保持 valid");
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

  private def workspaceRoot: Path = {
    Iterator.iterate(Paths.get("").toAbsolutePath.normalize)(_.getParent)
      .takeWhile(_ != null)
      .find(path => Files.isDirectory(path.resolve("accelerator-sim/spmv")))
      .getOrElse(throw new IllegalStateException("找不到 ysyx-workbench 根目录"))
  }

  private def runFixture(fixture: Int, testbench: String): Unit = {
    val directory = Files.createTempDirectory("spmv-cuperflow-product-dpi-")
    try {
      ChiselStage.emitSystemVerilogFile(
        new SpmvCuperflowProductBeatDpiHarness(fixture),
        Array("--target-dir", directory.toString, "--split-verilog"),
        Array("--disable-annotation-unknown")
      )
      val testbenchPath = directory.resolve("SpmvCuperflowProductBeatDpiTb.sv")
      Files.writeString(testbenchPath, testbench, StandardCharsets.UTF_8)
      val sourceRoot = workspaceRoot.resolve("accelerator-sim/spmv/encoding/cuperflow")
      val outputDirectory = directory.resolve("verilator")
      runCommand(Seq(
        "verilator", "--binary", "--timing", "-Wno-fatal", "--top-module",
        "SpmvCuperflowProductBeatDpiTb", "--Mdir", outputDirectory.toString,
        "-f", directory.resolve("filelist.f").toString, testbenchPath.toString,
        sourceRoot.resolve("product_beat_dpi.cpp").toString,
        sourceRoot.resolve("product_beat_golden.cpp").toString,
        sourceRoot.resolve("cuperflow.cpp").toString
      ), directory)
      runCommand(Seq(outputDirectory.resolve("VSpmvCuperflowProductBeatDpiTb").toString), directory)
    } finally {
      deleteTree(directory)
    }
  }

  "Cuperflow ProductBeat DPI source" should "从 V0 package golden 生成稳定的 standalone L1/L2 输入" in {
    runFixture(SpmvCuperflowProductBeatDpiFixture.Full8, full8Testbench)
  }

  it should "选择 V0 tail fixture，使 L2 可绕过 FMUL 单独验证 chunk 语义" in {
    runFixture(SpmvCuperflowProductBeatDpiFixture.Tail44, tail44Testbench)
  }
}
