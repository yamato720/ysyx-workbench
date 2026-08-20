package accelerators.spmv.inputmul.cuperflow

import _root_.circt.stage.ChiselStage
import accelerators.spmv.SpmvCuperflowConfig
import accelerators.spmv.inputmul.common.SpmvProduct
import chisel3._
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import org.scalatest.flatspec.AnyFlatSpec
import scala.io.Source
import scala.jdk.CollectionConverters._

/** 只为 ProductBeat contract test 服务的平坦接口，避免测试本身依赖 Bundle 展平顺序。 */
private[cuperflow] final class SpmvCuperflowProductBeatJoinHarness(
  config: SpmvCuperflowConfig
) extends Module {
  private val laneCount = 8
  private val join = Module(new SpmvCuperflowProductBeatJoin(config, pc = 0))

  val io = IO(new Bundle {
    val clear = Input(Bool())
    val acceptValid = Input(Bool())
    val acceptReady = Output(Bool())
    val wave = Input(UInt(16.W))
    val batch = Input(UInt(16.W))
    val beatSeq = Input(UInt(32.W))
    val localRow = Input(UInt(13.W))
    val rowLast = Input(Bool())
    val laneValid = Input(UInt(laneCount.W))
    val chunkMode = Input(UInt(2.W))
    val responseValid = Input(Vec(laneCount, Bool()))
    val responseReady = Output(Vec(laneCount, Bool()))
    val responseProduct = Input(Vec(laneCount, UInt(64.W)))
    val outValid = Output(Bool())
    val outReady = Input(Bool())
    val outWave = Output(UInt(16.W))
    val outBatch = Output(UInt(16.W))
    val outBeatSeq = Output(UInt(32.W))
    val outLaneValid = Output(UInt(laneCount.W))
    val outLocalRow = Output(Vec(laneCount, UInt(13.W)))
    val outRowLast = Output(Vec(laneCount, Bool()))
    val outChunkMode = Output(UInt(2.W))
    val outProduct = Output(Vec(laneCount, UInt(64.W)))
    val idle = Output(Bool())
    val error = Output(Bool())
  })

  join.io.clear := io.clear
  join.io.accept.valid := io.acceptValid
  join.io.accept.bits := 0.U.asTypeOf(new SpmvCuperflowProductBeat(config))
  join.io.accept.bits.pc := 0.U
  join.io.accept.bits.wave := io.wave
  join.io.accept.bits.batch := io.batch
  join.io.accept.bits.beatSeq := io.beatSeq
  join.io.accept.bits.laneValid := io.laneValid
  join.io.accept.bits.chunkMode := io.chunkMode
  for (lane <- 0 until laneCount) {
    join.io.accept.bits.localRow(lane) := io.localRow
    join.io.accept.bits.rowLast(lane) := io.rowLast
  }
  io.acceptReady := join.io.accept.ready

  for (lane <- 0 until laneCount) {
    join.io.product(lane).valid := io.responseValid(lane)
    join.io.product(lane).bits := 0.U.asTypeOf(new SpmvProduct(config.mulConfig))
    join.io.product(lane).bits.product := io.responseProduct(lane)
    join.io.product(lane).bits.row := io.localRow
    join.io.product(lane).bits.rowLast := io.rowLast
    join.io.product(lane).bits.chunkMode := io.chunkMode
    join.io.product(lane).bits.batch := io.batch
    join.io.product(lane).bits.lane := lane.U
    io.responseReady(lane) := join.io.product(lane).ready
  }

  join.io.out.ready := io.outReady
  io.outValid := join.io.out.valid
  io.outWave := join.io.out.bits.wave
  io.outBatch := join.io.out.bits.batch
  io.outBeatSeq := join.io.out.bits.beatSeq
  io.outLaneValid := join.io.out.bits.laneValid
  io.outLocalRow := join.io.out.bits.localRow
  io.outRowLast := join.io.out.bits.rowLast
  io.outChunkMode := join.io.out.bits.chunkMode
  io.outProduct := join.io.out.bits.product
  io.idle := join.io.idle
  io.error := join.io.error
}

class SpmvCuperflowProductBeatJoinTest extends AnyFlatSpec {
  private val config = SpmvCuperflowConfig(
    hbmPcCount = 1,
    hbmBase = 0,
    hbmBytes = 8192,
    xRegionBytes = 4096
  )

  private val testbench =
    """module SpmvCuperflowProductBeatJoinTb;
      |  logic clock = 1'b0;
      |  logic reset = 1'b1;
      |  logic io_clear;
      |  logic io_acceptValid;
      |  wire io_acceptReady;
      |  logic [15:0] io_wave;
      |  logic [15:0] io_batch;
      |  logic [31:0] io_beatSeq;
      |  logic [12:0] io_localRow;
      |  logic io_rowLast;
      |  logic [7:0] io_laneValid;
      |  logic [1:0] io_chunkMode;
      |  logic io_responseValid_0, io_responseValid_1, io_responseValid_2, io_responseValid_3;
      |  logic io_responseValid_4, io_responseValid_5, io_responseValid_6, io_responseValid_7;
      |  wire io_responseReady_0, io_responseReady_1, io_responseReady_2, io_responseReady_3;
      |  wire io_responseReady_4, io_responseReady_5, io_responseReady_6, io_responseReady_7;
      |  logic [63:0] io_responseProduct_0, io_responseProduct_1, io_responseProduct_2, io_responseProduct_3;
      |  logic [63:0] io_responseProduct_4, io_responseProduct_5, io_responseProduct_6, io_responseProduct_7;
      |  wire io_outValid;
      |  logic io_outReady;
      |  wire [15:0] io_outWave, io_outBatch;
      |  wire [31:0] io_outBeatSeq;
      |  wire [7:0] io_outLaneValid;
      |  wire [12:0] io_outLocalRow_0, io_outLocalRow_1, io_outLocalRow_2, io_outLocalRow_3;
      |  wire [12:0] io_outLocalRow_4, io_outLocalRow_5, io_outLocalRow_6, io_outLocalRow_7;
      |  wire io_outRowLast_0, io_outRowLast_1, io_outRowLast_2, io_outRowLast_3;
      |  wire io_outRowLast_4, io_outRowLast_5, io_outRowLast_6, io_outRowLast_7;
      |  wire [1:0] io_outChunkMode;
      |  wire [63:0] io_outProduct_0, io_outProduct_1, io_outProduct_2, io_outProduct_3;
      |  wire [63:0] io_outProduct_4, io_outProduct_5, io_outProduct_6, io_outProduct_7;
      |  wire io_idle, io_error;
      |
      |  SpmvCuperflowProductBeatJoinHarness dut (.*);
      |  always #5 clock = ~clock;
      |
      |  function automatic [63:0] expected_product(input integer lane);
      |    expected_product = 64'h1000000000000000 + lane;
      |  endfunction
      |
      |  task automatic set_response_valid(input integer lane, input bit value);
      |    begin
      |      case (lane)
      |        0: io_responseValid_0 = value; 1: io_responseValid_1 = value;
      |        2: io_responseValid_2 = value; 3: io_responseValid_3 = value;
      |        4: io_responseValid_4 = value; 5: io_responseValid_5 = value;
      |        6: io_responseValid_6 = value; 7: io_responseValid_7 = value;
      |      endcase
      |    end
      |  endtask
      |
      |  task automatic set_response_product(input integer lane, input [63:0] value);
      |    begin
      |      case (lane)
      |        0: io_responseProduct_0 = value; 1: io_responseProduct_1 = value;
      |        2: io_responseProduct_2 = value; 3: io_responseProduct_3 = value;
      |        4: io_responseProduct_4 = value; 5: io_responseProduct_5 = value;
      |        6: io_responseProduct_6 = value; 7: io_responseProduct_7 = value;
      |      endcase
      |    end
      |  endtask
      |
      |  task automatic assert_response_ready(input integer lane);
      |    begin
      |      case (lane)
      |        0: if (!io_responseReady_0) $fatal(1, "lane 0 response unexpectedly stalled");
      |        1: if (!io_responseReady_1) $fatal(1, "lane 1 response unexpectedly stalled");
      |        2: if (!io_responseReady_2) $fatal(1, "lane 2 response unexpectedly stalled");
      |        3: if (!io_responseReady_3) $fatal(1, "lane 3 response unexpectedly stalled");
      |        4: if (!io_responseReady_4) $fatal(1, "lane 4 response unexpectedly stalled");
      |        5: if (!io_responseReady_5) $fatal(1, "lane 5 response unexpectedly stalled");
      |        6: if (!io_responseReady_6) $fatal(1, "lane 6 response unexpectedly stalled");
      |        7: if (!io_responseReady_7) $fatal(1, "lane 7 response unexpectedly stalled");
      |      endcase
      |    end
      |  endtask
      |
      |  task automatic send_response(input integer lane);
      |    begin
      |      @(negedge clock);
      |      set_response_product(lane, expected_product(lane));
      |      set_response_valid(lane, 1'b1);
      |      assert_response_ready(lane);
      |      @(posedge clock);
      |      @(negedge clock);
      |      set_response_valid(lane, 1'b0);
      |    end
      |  endtask
      |
      |  task automatic check_output;
      |    begin
      |      if (!io_outValid || io_outWave != 16'd3 || io_outBatch != 16'd9 ||
      |          io_outBeatSeq != 32'd17 || io_outLaneValid != 8'hff ||
      |          io_outChunkMode != 2'b00)
      |        $fatal(1, "ProductBeat context changed or is incomplete");
      |      if (io_outLocalRow_0 != 13'd31 || io_outLocalRow_1 != 13'd31 ||
      |          io_outLocalRow_2 != 13'd31 || io_outLocalRow_3 != 13'd31 ||
      |          io_outLocalRow_4 != 13'd31 || io_outLocalRow_5 != 13'd31 ||
      |          io_outLocalRow_6 != 13'd31 || io_outLocalRow_7 != 13'd31 ||
      |          !io_outRowLast_0 || !io_outRowLast_1 || !io_outRowLast_2 || !io_outRowLast_3 ||
      |          !io_outRowLast_4 || !io_outRowLast_5 || !io_outRowLast_6 || !io_outRowLast_7)
      |        $fatal(1, "ProductBeat sideband changed under backpressure");
      |      if (io_outProduct_0 != expected_product(0) || io_outProduct_1 != expected_product(1) ||
      |          io_outProduct_2 != expected_product(2) || io_outProduct_3 != expected_product(3) ||
      |          io_outProduct_4 != expected_product(4) || io_outProduct_5 != expected_product(5) ||
      |          io_outProduct_6 != expected_product(6) || io_outProduct_7 != expected_product(7))
      |        $fatal(1, "ProductBeat lane product order is incorrect");
      |    end
      |  endtask
      |
      |  initial begin
      |    io_clear = 1'b0; io_acceptValid = 1'b0; io_wave = 16'd3; io_batch = 16'd9;
      |    io_beatSeq = 32'd17; io_localRow = 13'd31; io_rowLast = 1'b1;
      |    io_laneValid = 8'hff; io_chunkMode = 2'b00; io_outReady = 1'b0;
      |    io_responseValid_0 = 0; io_responseValid_1 = 0; io_responseValid_2 = 0; io_responseValid_3 = 0;
      |    io_responseValid_4 = 0; io_responseValid_5 = 0; io_responseValid_6 = 0; io_responseValid_7 = 0;
      |    io_responseProduct_0 = 0; io_responseProduct_1 = 0; io_responseProduct_2 = 0; io_responseProduct_3 = 0;
      |    io_responseProduct_4 = 0; io_responseProduct_5 = 0; io_responseProduct_6 = 0; io_responseProduct_7 = 0;
      |    repeat (2) @(posedge clock);
      |    @(negedge clock); reset = 1'b0;
      |
      |    io_acceptValid = 1'b1;
      |    if (!io_acceptReady) $fatal(1, "join did not accept the ProductBeat context");
      |    @(posedge clock);
      |    @(negedge clock); io_acceptValid = 1'b0;
      |
      |    send_response(5); send_response(0); send_response(7); send_response(2);
      |    send_response(4); send_response(1); send_response(6); send_response(3);
      |    check_output();
      |    repeat (3) begin @(posedge clock); check_output(); end
      |
      |    @(negedge clock); io_outReady = 1'b1;
      |    @(posedge clock);
      |    @(negedge clock); io_outReady = 1'b0;
      |    if (io_outValid || !io_idle || io_error)
      |      $fatal(1, "join did not retire exactly one ProductBeat");
      |    $display("Cuperflow ProductBeat join contract passed");
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

  "Cuperflow ProductBeat join" should "保持 beat 原子性，并把不同 lane 的响应和下游背压隔离" in {
    val directory = Files.createTempDirectory("spmv-cuperflow-product-join-")
    try {
      ChiselStage.emitSystemVerilogFile(
        new SpmvCuperflowProductBeatJoinHarness(config),
        Array("--target-dir", directory.toString, "--split-verilog"),
        Array("--disable-annotation-unknown")
      )
      val testbenchPath = directory.resolve("SpmvCuperflowProductBeatJoinTb.sv")
      Files.writeString(testbenchPath, testbench, StandardCharsets.UTF_8)
      val outputDirectory = directory.resolve("verilator")
      runCommand(Seq(
        "verilator", "--binary", "--timing", "-Wno-fatal", "--top-module",
        "SpmvCuperflowProductBeatJoinTb", "--Mdir", outputDirectory.toString,
        "-f", directory.resolve("filelist.f").toString, testbenchPath.toString
      ), directory)
      runCommand(Seq(outputDirectory.resolve("VSpmvCuperflowProductBeatJoinTb").toString), directory)
    } finally {
      deleteTree(directory)
    }
  }
}
