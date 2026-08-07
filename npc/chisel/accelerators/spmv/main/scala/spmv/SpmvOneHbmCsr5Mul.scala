package spmv

import chisel3._
import chisel3.experimental.IntParam
import chisel3.util._
import npc.{SpmvCsr5MulConfig, SpmvXMode}

final class SpmvCellConfig extends Bundle {
  val blockRows = UInt(14.W)
  val blockCols = UInt(14.W)
  val aAddress = UInt(64.W)
  val aBeats = UInt(32.W)
  val xAddress = UInt(64.W)
  val xBeats = UInt(32.W)
  val xCrc32 = UInt(32.W)
  val expectedPackets = UInt(32.W)
  val expectedProductBeats = UInt(32.W)
  val expectedProducts = UInt(32.W)
  val outstandingLimit = UInt(2.W)
}

final class SpmvProductLane extends Bundle {
  val valid = Bool()
  val rowStart = Bool()
  val rowEnd = Bool()
  val localRow = UInt(13.W)
  val product = UInt(32.W)
  val exceptionFlags = UInt(5.W)
}

final class SpmvProductBeat extends Bundle {
  val globalTileId = UInt(32.W)
  val blockTileId = UInt(32.W)
  val blockRowId = UInt(16.W)
  val blockColId = UInt(16.W)
  val blockRowBase = UInt(32.W)
  val step = UInt(5.W)
  val tileLast = Bool()
  val lanes = Vec(8, new SpmvProductLane)
}

final class SpmvHbmBeat extends Bundle {
  val data = UInt(512.W)
  val error = Bool()
  val last = Bool()
}

private final class HbmBurstDescriptor(config: SpmvCsr5MulConfig) extends Bundle {
  val sourceX = Bool()
  val beats = UInt(7.W)
  val id = UInt(config.axiIdWidth.W)
}

/** 两个请求源共享同一组 outstanding credit，并按 AR 接收顺序校验和分流 R beat。 */
final class SharedHbmReadScheduler(config: SpmvCsr5MulConfig) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val aAddress = Input(UInt(64.W))
    val aBeats = Input(UInt(32.W))
    val xAddress = Input(UInt(64.W))
    val xBeats = Input(UInt(32.W))
    val outstandingLimit = Input(UInt(2.W))
    val axi = new SpmvAxiReadMasterIO(config.axiAddrWidth, config.axiDataWidth, config.axiIdWidth)
    val aOutput = Decoupled(new SpmvHbmBeat)
    val xOutput = Decoupled(new SpmvHbmBeat)
    val aDone = Output(Bool())
    val xDone = Output(Bool())
    val idle = Output(Bool())
    val aBeatCount = Output(UInt(32.W))
    val xBeatCount = Output(UInt(32.W))
    val aBurstCount = Output(UInt(32.W))
    val xBurstCount = Output(UInt(32.W))
    val pcDataCycles = Output(UInt(64.W))
    val pcIdleCycles = Output(UInt(64.W))
    val error = Output(Bool())
  })

  require(config.outstandingBursts == 2)
  require(config.inputFifoDepth >= 2 * config.maxBurstBeats)

  private val aQueue = Module(new Queue(new SpmvHbmBeat, config.inputFifoDepth))
  private val xQueue = Module(new Queue(new SpmvHbmBeat, config.inputFifoDepth))
  private val descriptors = Module(new Queue(new HbmBurstDescriptor(config),
    config.outstandingBursts))
  private val running = RegInit(false.B)
  private val started = RegInit(false.B)
  private val preferA = RegInit(true.B)
  private val aIssued = RegInit(0.U(32.W))
  private val xIssued = RegInit(0.U(32.W))
  private val aReceived = RegInit(0.U(32.W))
  private val xReceived = RegInit(0.U(32.W))
  private val aReserved = RegInit(0.U(9.W))
  private val xReserved = RegInit(0.U(9.W))
  private val outstanding = RegInit(0.U(2.W))
  private val responseIndex = RegInit(0.U(7.W))
  private val aBursts = RegInit(0.U(32.W))
  private val xBursts = RegInit(0.U(32.W))
  private val dataCycles = RegInit(0.U(64.W))
  private val idleCycles = RegInit(0.U(64.W))
  private val error = RegInit(false.B)

  private def nextBurst(base: UInt, issued: UInt, total: UInt): UInt = {
    val address = base + (issued << 6)
    val remaining = total - issued
    val boundaryBeats = (4096.U(13.W) - Cat(0.U(1.W), address(11, 0))) >> 6
    val bounded = Mux(remaining > config.maxBurstBeats.U, config.maxBurstBeats.U, remaining)
    Mux(bounded > boundaryBeats, boundaryBeats, bounded)(6, 0)
  }

  private val aNextBeats = nextBurst(io.aAddress, aIssued, io.aBeats)
  private val xNextBeats = nextBurst(io.xAddress, xIssued, io.xBeats)
  private val aCapacityUsed = aQueue.io.count.pad(9) + aReserved
  private val xCapacityUsed = xQueue.io.count.pad(9) + xReserved
  private val aEligible = aIssued < io.aBeats &&
    aCapacityUsed + aNextBeats <= config.inputFifoDepth.U
  private val xEligible = xIssued < io.xBeats &&
    xCapacityUsed + xNextBeats <= config.inputFifoDepth.U
  private val chooseA = aEligible && (preferA || !xEligible)
  private val chooseX = xEligible && (!preferA || !aEligible)
  private val mayIssue = running && outstanding < io.outstandingLimit && descriptors.io.enq.ready
  private val selectedX = chooseX
  private val selectedAddress = Mux(selectedX,
    io.xAddress + (xIssued << 6), io.aAddress + (aIssued << 6))
  private val selectedBeats = Mux(selectedX, xNextBeats, aNextBeats)

  io.axi.ar.valid := mayIssue && (chooseA || chooseX)
  io.axi.ar.bits.id := Mux(selectedX, 1.U, 0.U)
  io.axi.ar.bits.addr := selectedAddress
  io.axi.ar.bits.len := selectedBeats - 1.U
  io.axi.ar.bits.size := 6.U
  io.axi.ar.bits.burst := 1.U
  io.axi.ar.bits.lock := false.B
  io.axi.ar.bits.cache := 0.U
  io.axi.ar.bits.prot := 0.U
  io.axi.ar.bits.qos := 0.U

  descriptors.io.enq.valid := io.axi.ar.valid
  descriptors.io.enq.bits.sourceX := selectedX
  descriptors.io.enq.bits.beats := selectedBeats
  descriptors.io.enq.bits.id := Mux(selectedX, 1.U, 0.U)

  private val descriptorValid = descriptors.io.deq.valid
  private val responseTargetsX = descriptors.io.deq.bits.sourceX
  private val expectedResponseLast = responseIndex === descriptors.io.deq.bits.beats - 1.U
  private val targetReady = Mux(responseTargetsX, xQueue.io.enq.ready, aQueue.io.enq.ready)
  io.axi.r.ready := Mux(descriptorValid, targetReady, true.B)
  private val routedResponseFire = io.axi.r.fire && descriptorValid

  aQueue.io.enq.valid := io.axi.r.valid && descriptorValid && !responseTargetsX
  xQueue.io.enq.valid := io.axi.r.valid && descriptorValid && responseTargetsX
  for (queue <- Seq(aQueue, xQueue)) {
    queue.io.enq.bits.data := io.axi.r.bits.data
    queue.io.enq.bits.error := io.axi.r.bits.resp =/= 0.U ||
      io.axi.r.bits.id =/= descriptors.io.deq.bits.id ||
      io.axi.r.bits.last =/= expectedResponseLast
  }
  aQueue.io.enq.bits.last := aReceived === io.aBeats - 1.U
  xQueue.io.enq.bits.last := xReceived === io.xBeats - 1.U
  descriptors.io.deq.ready := routedResponseFire && expectedResponseLast

  io.aOutput <> aQueue.io.deq
  io.xOutput <> xQueue.io.deq
  io.aDone := started && aReceived === io.aBeats
  io.xDone := started && xReceived === io.xBeats
  io.idle := !running && descriptors.io.count === 0.U
  io.aBeatCount := aReceived
  io.xBeatCount := xReceived
  io.aBurstCount := aBursts
  io.xBurstCount := xBursts
  io.pcDataCycles := dataCycles
  io.pcIdleCycles := idleCycles
  io.error := error

  private val arFire = io.axi.ar.fire
  private val responseBurstDone = routedResponseFire && expectedResponseLast
  private val aReserveIncrease = Mux(arFire && !selectedX, selectedBeats.pad(9), 0.U)
  private val xReserveIncrease = Mux(arFire && selectedX, selectedBeats.pad(9), 0.U)
  private val aReserveDecrease = Mux(routedResponseFire && !responseTargetsX, 1.U, 0.U)
  private val xReserveDecrease = Mux(routedResponseFire && responseTargetsX, 1.U, 0.U)

  when(io.start) {
    assert(aQueue.io.count === 0.U && xQueue.io.count === 0.U && descriptors.io.count === 0.U)
    running := true.B
    started := true.B
    preferA := true.B
    aIssued := 0.U
    xIssued := 0.U
    aReceived := 0.U
    xReceived := 0.U
    aReserved := 0.U
    xReserved := 0.U
    outstanding := 0.U
    responseIndex := 0.U
    aBursts := 0.U
    xBursts := 0.U
    dataCycles := 0.U
    idleCycles := 0.U
    error := false.B
  }.otherwise {
    when(running) {
      when(routedResponseFire) { dataCycles := dataCycles + 1.U }
        .otherwise { idleCycles := idleCycles + 1.U }
    }
    when(io.axi.r.valid && !descriptorValid) { error := true.B }
    when(arFire) {
      preferA := selectedX
      when(selectedX) {
        xIssued := xIssued + selectedBeats
        xBursts := xBursts + 1.U
      }.otherwise {
        aIssued := aIssued + selectedBeats
        aBursts := aBursts + 1.U
      }
    }
    when(routedResponseFire) {
      when(io.axi.r.bits.resp =/= 0.U || io.axi.r.bits.id =/= descriptors.io.deq.bits.id ||
        io.axi.r.bits.last =/= expectedResponseLast) {
        error := true.B
      }
      when(responseTargetsX) { xReceived := xReceived + 1.U }
        .otherwise { aReceived := aReceived + 1.U }
      responseIndex := Mux(expectedResponseLast, 0.U, responseIndex + 1.U)
    }
    aReserved := aReserved + aReserveIncrease - aReserveDecrease
    xReserved := xReserved + xReserveIncrease - xReserveDecrease
    switch(Cat(arFire, responseBurstDone)) {
      is("b10".U) { outstanding := outstanding + 1.U }
      is("b01".U) { outstanding := outstanding - 1.U }
    }
    when(routedResponseFire && responseTargetsX && xReceived + 1.U === io.xBeats &&
      aReceived === io.aBeats) {
      running := false.B
    }
    when(routedResponseFire && !responseTargetsX && aReceived + 1.U === io.aBeats &&
      xReceived === io.xBeats) {
      running := false.B
    }
  }
}

/** Verilator 专用双 outstanding HBM 模型；延迟并行倒计时，R burst 按 AR 顺序返回。 */
private final class SpmvHbmDpiReadSlave(config: SpmvCsr5MulConfig) extends BlackBox(Map(
  "HBM_BASE" -> IntParam(BigInt(config.hbmBase)),
  "HBM_BYTES" -> IntParam(BigInt(config.hbmBytes)),
  "LATENCY_MIN" -> IntParam(config.hbmFirstBeatLatencyMin),
  "LATENCY_MAX" -> IntParam(config.hbmFirstBeatLatencyMax),
  "TIMING_SEED" -> IntParam(BigInt(config.hbmTimingSeed))
)) with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val arValid = Input(Bool())
    val arReady = Output(Bool())
    val arId = Input(UInt(config.axiIdWidth.W))
    val arAddr = Input(UInt(64.W))
    val arLen = Input(UInt(8.W))
    val arSize = Input(UInt(3.W))
    val arBurst = Input(UInt(2.W))
    val rValid = Output(Bool())
    val rReady = Input(Bool())
    val rId = Output(UInt(config.axiIdWidth.W))
    val rData = Output(UInt(512.W))
    val rResp = Output(UInt(2.W))
    val rLast = Output(Bool())
  })

  override def desiredName: String = "SpmvHbmDpiReadSlave"

  setInline("SpmvHbmDpiReadSlave.sv",
    s"""module SpmvHbmDpiReadSlave #(parameter longint unsigned HBM_BASE = 64'h80000000,
      |  parameter longint unsigned HBM_BYTES = 64'h08000000,
      |  parameter integer LATENCY_MIN = 73, parameter integer LATENCY_MAX = 81,
      |  parameter longint unsigned TIMING_SEED = 64'h13579bdf) (
      |  input logic clock, input logic reset,
      |  input logic arValid, output logic arReady, input logic [${config.axiIdWidth - 1}:0] arId,
      |  input logic [63:0] arAddr, input logic [7:0] arLen,
      |  input logic [2:0] arSize, input logic [1:0] arBurst,
      |  output logic rValid, input logic rReady, output logic [${config.axiIdWidth - 1}:0] rId,
      |  output logic [511:0] rData, output logic [1:0] rResp, output logic rLast);
      |
      |  import "DPI-C" function void spmv_hbm_read512(
      |    input longint unsigned address, output bit [511:0] data, output bit error);
      |  import "DPI-C" function bit spmv_hbm_no_jitter();
      |
      |  logic requestValid [0:1];
      |  logic requestError [0:1];
      |  logic [63:0] requestAddress [0:1];
      |  logic [8:0] requestBeats [0:1];
      |  logic [${config.axiIdWidth - 1}:0] requestId [0:1];
      |  logic [7:0] requestDelay [0:1];
      |  logic headIndex;
      |  logic tailIndex;
      |  logic [1:0] requestCount;
      |  logic [31:0] randomState;
      |  logic [31:0] nextRandomState;
      |  logic noJitter;
      |  bit [511:0] fetchedData;
      |  bit fetchedError;
      |  logic [63:0] requestBytes;
      |  logic requestInvalid;
      |  logic pushRequest;
      |  logic popRequest;
      |  localparam integer FIXED_LATENCY = (LATENCY_MIN + LATENCY_MAX) / 2;
      |  integer slot;
      |
      |  always_comb begin
      |    requestBytes = ({56'b0, arLen} + 64'd1) << 6;
      |    requestInvalid = (arSize != 3'd6) || (arBurst != 2'd1) || arAddr[5:0] != 0 ||
      |      (arLen >= 8'd64) || (({1'b0, arAddr[11:0]} + requestBytes[12:0]) > 13'd4096) ||
      |      (arAddr < HBM_BASE) || (arAddr + requestBytes < arAddr) ||
      |      (arAddr + requestBytes > HBM_BASE + HBM_BYTES);
      |    arReady = requestCount < 2;
      |    pushRequest = arValid && arReady;
      |    popRequest = rValid && rReady && rLast;
      |    nextRandomState = randomState ^ (randomState << 13);
      |    nextRandomState = nextRandomState ^ (nextRandomState >> 17);
      |    nextRandomState = nextRandomState ^ (nextRandomState << 5);
      |  end
      |
      |  always_ff @(posedge clock) begin
      |    if (reset) begin
      |      for (slot = 0; slot < 2; slot = slot + 1) begin
      |        requestValid[slot] <= 1'b0;
      |        requestError[slot] <= 1'b0;
      |        requestAddress[slot] <= 64'b0;
      |        requestBeats[slot] <= 9'b0;
      |        requestId[slot] <= '0;
      |        requestDelay[slot] <= 8'b0;
      |      end
      |      headIndex <= 1'b0;
      |      tailIndex <= 1'b0;
      |      requestCount <= 2'b0;
      |      randomState <= TIMING_SEED[31:0];
      |      noJitter <= spmv_hbm_no_jitter();
      |      rValid <= 1'b0;
      |      rId <= '0;
      |      rData <= '0;
      |      rResp <= 2'b00;
      |      rLast <= 1'b0;
      |    end else begin
      |      for (slot = 0; slot < 2; slot = slot + 1) begin
      |        if (requestValid[slot] && requestDelay[slot] != 0)
      |          requestDelay[slot] <= requestDelay[slot] - 1'b1;
      |      end
      |      if (pushRequest) begin
      |        requestValid[tailIndex] <= 1'b1;
      |        requestError[tailIndex] <= requestInvalid;
      |        requestAddress[tailIndex] <= arAddr;
      |        requestBeats[tailIndex] <= {1'b0, arLen} + 9'd1;
      |        requestId[tailIndex] <= arId;
      |        // AR 握手拍计入首拍延迟，后续每个排队请求独立倒计时。
      |        requestDelay[tailIndex] <= noJitter ? FIXED_LATENCY - 1 :
      |          LATENCY_MIN - 1 + (randomState % (LATENCY_MAX - LATENCY_MIN + 1));
      |        tailIndex <= tailIndex + 1'b1;
      |        randomState <= nextRandomState;
      |      end
      |
      |      if (rValid) begin
      |        if (rReady) begin
      |          if (rLast) begin
      |            rValid <= 1'b0;
      |            requestValid[headIndex] <= 1'b0;
      |            headIndex <= headIndex + 1'b1;
      |          end else begin
      |            fetchedData = '0;
      |            fetchedError = 1'b0;
      |            if (!requestError[headIndex])
      |              spmv_hbm_read512(requestAddress[headIndex] + 64, fetchedData, fetchedError);
      |            requestAddress[headIndex] <= requestAddress[headIndex] + 64;
      |            requestBeats[headIndex] <= requestBeats[headIndex] - 1'b1;
      |            rId <= requestId[headIndex];
      |            rData <= fetchedData;
      |            rResp <= (requestError[headIndex] || fetchedError) ? 2'b10 : 2'b00;
      |            rLast <= requestBeats[headIndex] == 9'd2;
      |          end
      |        end
      |      end else if (requestCount != 0 && requestDelay[headIndex] == 0) begin
      |        fetchedData = '0;
      |        fetchedError = 1'b0;
      |        if (!requestError[headIndex])
      |          spmv_hbm_read512(requestAddress[headIndex], fetchedData, fetchedError);
      |        rValid <= 1'b1;
      |        rId <= requestId[headIndex];
      |        rData <= fetchedData;
      |        rResp <= (requestError[headIndex] || fetchedError) ? 2'b10 : 2'b00;
      |        rLast <= requestBeats[headIndex] == 9'd1;
      |      end
      |
      |      case ({pushRequest, popRequest})
      |        2'b10: requestCount <= requestCount + 1'b1;
      |        2'b01: requestCount <= requestCount - 1'b1;
      |        default: requestCount <= requestCount;
      |      endcase
      |    end
      |  end
      |endmodule
      |""".stripMargin)
}

final class DecodedLane extends Bundle {
  val valid = Bool()
  val rowStart = Bool()
  val rowEnd = Bool()
  val localRow = UInt(13.W)
  val localCol = UInt(13.W)
  val a = UInt(32.W)
}

final class DecodedBeat extends Bundle {
  val globalTileId = UInt(32.W)
  val blockTileId = UInt(32.W)
  val blockRowId = UInt(16.W)
  val blockColId = UInt(16.W)
  val blockRowBase = UInt(32.W)
  val step = UInt(5.W)
  val tileLast = Bool()
  val tag = UInt(32.W)
  val lanes = Vec(8, new DecodedLane)
}

private object SpmvCrc32 {
  def beat(previous: UInt, data: UInt): UInt = {
    var value = previous
    for (byte <- 0 until 64) {
      value = value ^ data(byte * 8 + 7, byte * 8)
      for (_ <- 0 until 8) {
        value = Mux(value(0), (value >> 1) ^ "hedb88320".U(32.W), value >> 1)
      }
    }
    value
  }
}

/** 解析 Metadata v2 和 payload，并在 packet 边界完成计数、lane summary 与 CRC 校验。 */
final class Csr5PacketDecoder(config: SpmvCsr5MulConfig) extends Module {
  val io = IO(new Bundle {
    val clear = Input(Bool())
    val blockRows = Input(UInt(14.W))
    val blockCols = Input(UInt(14.W))
    val streamFinished = Input(Bool())
    val input = Flipped(Decoupled(new SpmvHbmBeat))
    val output = Decoupled(new DecodedBeat)
    val idle = Output(Bool())
    val packetCount = Output(UInt(32.W))
    val errorMask = Output(UInt(13.W))
  })

  private val waitMetadata :: readPayload :: Nil = Enum(2)
  private val state = RegInit(waitMetadata)
  private val remaining = RegInit(0.U(5.W))
  private val step = RegInit(0.U(5.W))
  private val expectedValid = RegInit(0.U(16.W))
  private val seenValid = RegInit(0.U(16.W))
  private val expectedCrc = RegInit(0.U(32.W))
  private val crc = RegInit("hffffffff".U(32.W))
  private val summaries = Reg(Vec(8, UInt(32.W)))
  private val laneCounts = RegInit(VecInit(Seq.fill(8)(0.U(5.W))))
  private val laneSegments = RegInit(VecInit(Seq.fill(8)(0.U(5.W))))
  private val laneFirstRows = Reg(Vec(8, UInt(13.W)))
  private val laneLastRows = Reg(Vec(8, UInt(13.W)))
  private val laneHeadContinues = Reg(Vec(8, Bool()))
  private val laneTailContinues = Reg(Vec(8, Bool()))
  private val packetCount = RegInit(0.U(32.W))
  private val tag = RegInit(0.U(32.W))
  private val contextGlobalTileId = Reg(UInt(32.W))
  private val contextBlockTileId = Reg(UInt(32.W))
  private val contextBlockRowId = Reg(UInt(16.W))
  private val contextBlockColId = Reg(UInt(16.W))
  private val contextBlockRowBase = Reg(UInt(32.W))
  private val errorEvents = WireInit(VecInit(Seq.fill(13)(false.B)))

  io.input.ready := Mux(state === waitMetadata, true.B, io.output.ready)
  io.output.valid := state === readPayload && io.input.valid
  io.output.bits.globalTileId := contextGlobalTileId
  io.output.bits.blockTileId := contextBlockTileId
  io.output.bits.blockRowId := contextBlockRowId
  io.output.bits.blockColId := contextBlockColId
  io.output.bits.blockRowBase := contextBlockRowBase
  io.output.bits.step := step
  io.output.bits.tileLast := remaining === 1.U
  io.output.bits.tag := tag
  for (lane <- 0 until 8) {
    val record = io.input.bits.data(lane * 64 + 63, lane * 64)
    val coord = record(63, 32)
    io.output.bits.lanes(lane).valid := coord(31)
    io.output.bits.lanes(lane).rowStart := coord(30)
    io.output.bits.lanes(lane).rowEnd := coord(29)
    io.output.bits.lanes(lane).localRow := coord(28, 16)
    io.output.bits.lanes(lane).localCol := coord(12, 0)
    io.output.bits.lanes(lane).a := record(31, 0)
  }
  io.idle := state === waitMetadata
  io.packetCount := packetCount
  io.errorMask := errorEvents.asUInt

  when(io.clear) {
    state := waitMetadata
    remaining := 0.U
    step := 0.U
    expectedValid := 0.U
    seenValid := 0.U
    expectedCrc := 0.U
    crc := "hffffffff".U
    laneCounts.foreach(_ := 0.U)
    laneSegments.foreach(_ := 0.U)
    packetCount := 0.U
    tag := 0.U
  }.otherwise {
    when(io.streamFinished && state === readPayload && !io.input.valid) {
      errorEvents(3) := true.B
      state := waitMetadata
    }
    when(io.input.fire && io.input.bits.error) { errorEvents(2) := true.B }

    when(io.input.fire && state === waitMetadata) {
      val metadata = io.input.bits.data
      val version = metadata(263, 256)
      val unitId = metadata(271, 264)
      val flags = metadata(279, 272)
      val payloadBeats = metadata(287, 280)
      val validCount = metadata(303, 288)
      val full = flags(0)
      val tail = flags(1)
      val transposed = flags(2)
      val metadataInvalid = version =/= 2.U || unitId =/= config.unitId.U ||
        !(full ^ tail) || flags(7, 3).orR || payloadBeats === 0.U || payloadBeats > 16.U ||
        validCount === 0.U || validCount > 128.U || metadata(511, 496).orR ||
        (full && (!transposed || payloadBeats =/= 16.U || validCount =/= 128.U)) ||
        (tail && (transposed || payloadBeats =/= ((validCount + 7.U) >> 3)))
      when(metadataInvalid) { errorEvents(4) := true.B }
      for (lane <- 0 until 8) {
        val summary = metadata(lane * 32 + 31, lane * 32)
        val laneValid = summary(31)
        val validSteps = summary(23, 16)
        summaries(lane) := summary
        laneCounts(lane) := 0.U
        laneSegments(lane) := 0.U
        laneFirstRows(lane) := 0.U
        laneLastRows(lane) := 0.U
        laneHeadContinues(lane) := false.B
        laneTailContinues(lane) := false.B
        when(summary(15, 13).orR || validSteps > 16.U || laneValid =/= validSteps.orR ||
          (laneValid && summary(12, 0) >= io.blockRows)) {
          errorEvents(5) := true.B
        }
      }
      remaining := payloadBeats(4, 0)
      step := 0.U
      expectedValid := validCount
      seenValid := 0.U
      expectedCrc := metadata(495, 464)
      crc := "hffffffff".U
      contextBlockRowId := metadata(319, 304)
      contextBlockColId := metadata(335, 320)
      contextGlobalTileId := metadata(367, 336)
      contextBlockTileId := metadata(399, 368)
      contextBlockRowBase := metadata(431, 400)
      state := readPayload
      when(io.input.bits.last) { errorEvents(3) := true.B }
    }

    when(io.output.fire) {
      val nextCrc = SpmvCrc32.beat(crc, io.input.bits.data)
      val beatValid = PopCount(io.output.bits.lanes.map(_.valid))
      crc := nextCrc
      seenValid := seenValid + beatValid
      tag := tag + 1.U
      for (lane <- 0 until 8) {
        val decoded = io.output.bits.lanes(lane)
        val record = io.input.bits.data(lane * 64 + 63, lane * 64)
        when(decoded.valid) {
          when(decoded.localRow >= io.blockRows || decoded.localCol >= io.blockCols ||
            decoded.localRow >= config.maxBlockRows.U || decoded.localCol >= config.maxBlockCols.U ||
            record(47, 45).orR) {
            errorEvents(6) := true.B
          }
          when(laneCounts(lane) === 0.U) {
            laneFirstRows(lane) := decoded.localRow
            laneHeadContinues(lane) := !decoded.rowStart
            laneSegments(lane) := 1.U
          }.elsewhen(laneLastRows(lane) =/= decoded.localRow) {
            laneSegments(lane) := laneSegments(lane) + 1.U
          }
          laneLastRows(lane) := decoded.localRow
          laneTailContinues(lane) := !decoded.rowEnd
          laneCounts(lane) := laneCounts(lane) + 1.U
        }.elsewhen(record.orR) {
          errorEvents(6) := true.B
        }
      }

      when(remaining === 1.U) {
        val finalValid = seenValid + beatValid
        when(finalValid =/= expectedValid) { errorEvents(7) := true.B }
        when((nextCrc ^ "hffffffff".U) =/= expectedCrc) { errorEvents(8) := true.B }
        for (lane <- 0 until 8) {
          val decoded = io.output.bits.lanes(lane)
          val count = laneCounts(lane) + decoded.valid
          val firstRow = Mux(laneCounts(lane) === 0.U && decoded.valid,
            decoded.localRow, laneFirstRows(lane))
          val headContinues = Mux(laneCounts(lane) === 0.U && decoded.valid,
            !decoded.rowStart, laneHeadContinues(lane))
          val tailContinues = Mux(decoded.valid, !decoded.rowEnd, laneTailContinues(lane))
          val segments = Mux(laneCounts(lane) === 0.U && decoded.valid, 1.U,
            laneSegments(lane) +
              (decoded.valid && laneCounts(lane) =/= 0.U && laneLastRows(lane) =/= decoded.localRow))
          val summary = summaries(lane)
          when(summary(31) =/= count.orR || summary(30) =/= headContinues ||
            summary(29) =/= tailContinues || summary(28, 24) =/= segments ||
            summary(23, 16) =/= count || (count.orR && summary(12, 0) =/= firstRow)) {
            errorEvents(5) := true.B
          }
        }
        packetCount := packetCount + 1.U
        state := waitMetadata
      }.otherwise {
        when(io.input.bits.last) {
          errorEvents(3) := true.B
          state := waitMetadata
        }.otherwise {
          remaining := remaining - 1.U
          step := step + 1.U
        }
      }
    }
  }
}

final class MulIssue extends Bundle {
  val decoded = new DecodedBeat
  val x = Vec(8, UInt(32.W))
}

final class PairedXGroup extends Bundle {
  val tag = UInt(32.W)
  val last = Bool()
  val values = Vec(8, UInt(32.W))
}

/** 一个 X beat 依次产生低、高两个 256-bit group，奇数组尾同时检查高半拍零填充。 */
final class PairedXUnpacker extends Module {
  val io = IO(new Bundle {
    val clear = Input(Bool())
    val expectedBeats = Input(UInt(32.W))
    val expectedGroups = Input(UInt(32.W))
    val expectedCrc = Input(UInt(32.W))
    val input = Flipped(Decoupled(new SpmvHbmBeat))
    val output = Decoupled(new PairedXGroup)
    val done = Output(Bool())
    val groupCount = Output(UInt(32.W))
    val loadError = Output(Bool())
    val crcError = Output(Bool())
  })

  private val beatValid = RegInit(false.B)
  private val beatData = Reg(UInt(512.W))
  private val highHalf = RegInit(false.B)
  private val rawBeatCount = RegInit(0.U(32.W))
  private val groupCount = RegInit(0.U(32.W))
  private val crc = RegInit("hffffffff".U(32.W))
  private val crcChecked = RegInit(false.B)
  private val loadError = RegInit(false.B)
  private val crcError = RegInit(false.B)

  io.input.ready := !beatValid && rawBeatCount < io.expectedBeats
  io.output.valid := beatValid && groupCount < io.expectedGroups
  io.output.bits.tag := groupCount
  io.output.bits.last := groupCount === io.expectedGroups - 1.U
  private val selected = Mux(highHalf, beatData(511, 256), beatData(255, 0))
  for (lane <- 0 until 8) {
    io.output.bits.values(lane) := selected(lane * 32 + 31, lane * 32)
  }
  io.done := crcChecked && groupCount === io.expectedGroups && !beatValid
  io.groupCount := groupCount
  io.loadError := loadError
  io.crcError := crcError

  when(io.clear) {
    beatValid := false.B
    highHalf := false.B
    rawBeatCount := 0.U
    groupCount := 0.U
    crc := "hffffffff".U
    crcChecked := false.B
    loadError := false.B
    crcError := false.B
  }.otherwise {
    when(io.input.fire) {
      val nextCrc = SpmvCrc32.beat(crc, io.input.bits.data)
      beatValid := true.B
      beatData := io.input.bits.data
      highHalf := false.B
      rawBeatCount := rawBeatCount + 1.U
      crc := nextCrc
      when(io.input.bits.error || io.input.bits.last =/= (rawBeatCount === io.expectedBeats - 1.U)) {
        loadError := true.B
      }
      when(rawBeatCount === io.expectedBeats - 1.U) {
        crcChecked := true.B
        when((nextCrc ^ "hffffffff".U) =/= io.expectedCrc) { crcError := true.B }
      }
    }
    when(io.output.fire) {
      val finalGroup = groupCount === io.expectedGroups - 1.U
      when(finalGroup) {
        when(!highHalf && beatData(511, 256).orR) { loadError := true.B }
        beatValid := false.B
        highHalf := false.B
      }.elsewhen(highHalf) {
        beatValid := false.B
        highHalf := false.B
      }.otherwise {
        highHalf := true.B
      }
      groupCount := groupCount + 1.U
    }
  }
}

/** decoder 顺序就是 X group 的协议序号；无效 lane 必须由 host 以零填充。 */
final class PairedXJoin extends Module {
  val io = IO(new Bundle {
    val clear = Input(Bool())
    val expectedBeats = Input(UInt(32.W))
    val decoded = Flipped(Decoupled(new DecodedBeat))
    val pairedX = Flipped(Decoupled(new PairedXGroup))
    val output = Decoupled(new MulIssue)
    val empty = Output(Bool())
    val count = Output(UInt(32.W))
    val error = Output(Bool())
  })

  private val count = RegInit(0.U(32.W))
  private val error = RegInit(false.B)
  io.output.valid := io.decoded.valid && io.pairedX.valid
  io.output.bits.decoded := io.decoded.bits
  for (lane <- 0 until 8) {
    io.output.bits.x(lane) := io.pairedX.bits.values(lane)
  }
  io.decoded.ready := io.output.ready && io.pairedX.valid
  io.pairedX.ready := io.output.ready && io.decoded.valid
  io.empty := !io.decoded.valid && !io.pairedX.valid
  io.count := count
  io.error := error

  when(io.clear) {
    count := 0.U
    error := false.B
  }.elsewhen(io.output.fire) {
    val expectedLast = count === io.expectedBeats - 1.U
    when(io.expectedBeats === 0.U || io.decoded.bits.tag =/= count ||
      io.pairedX.bits.tag =/= count || io.pairedX.bits.last =/= expectedLast) {
      error := true.B
    }
    for (lane <- 0 until 8) {
      when(!io.decoded.bits.lanes(lane).valid && io.pairedX.bits.values(lane).orR) {
        error := true.B
      }
    }
    count := count + 1.U
  }
}

final class WideXLoad extends Bundle {
  val wordIndex = UInt(9.W)
  val data = UInt(512.W)
}

/** cached 构造每拍写入 16 个 FP32，并对原始 beat、尾部填充和总数做统一校验。 */
final class CachedXLoader extends Module {
  val io = IO(new Bundle {
    val clear = Input(Bool())
    val blockCols = Input(UInt(14.W))
    val expectedBeats = Input(UInt(32.W))
    val expectedCrc = Input(UInt(32.W))
    val input = Flipped(Decoupled(new SpmvHbmBeat))
    val load = Valid(new WideXLoad)
    val done = Output(Bool())
    val loadError = Output(Bool())
    val crcError = Output(Bool())
  })

  private val beatCount = RegInit(0.U(32.W))
  private val crc = RegInit("hffffffff".U(32.W))
  private val done = RegInit(false.B)
  private val loadError = RegInit(false.B)
  private val crcError = RegInit(false.B)

  io.input.ready := !done && beatCount < io.expectedBeats
  io.load.valid := io.input.fire
  io.load.bits.wordIndex := beatCount(8, 0)
  io.load.bits.data := io.input.bits.data
  io.done := done
  io.loadError := loadError
  io.crcError := crcError

  when(io.clear) {
    beatCount := 0.U
    crc := "hffffffff".U
    done := false.B
    loadError := false.B
    crcError := false.B
  }.elsewhen(io.input.fire) {
    val nextCrc = SpmvCrc32.beat(crc, io.input.bits.data)
    val expectedLast = beatCount === io.expectedBeats - 1.U
    beatCount := beatCount + 1.U
    crc := nextCrc
    when(io.input.bits.error || io.input.bits.last =/= expectedLast) { loadError := true.B }
    for (element <- 0 until 16) {
      val globalIndex = (beatCount << 4) + element.U
      when(globalIndex >= io.blockCols && io.input.bits.data(element * 32 + 31, element * 32).orR) {
        loadError := true.B
      }
    }
    when(expectedLast) {
      done := true.B
      when((nextCrc ^ "hffffffff".U) =/= io.expectedCrc) { crcError := true.B }
    }
  }
}

/** 四份 512x512-bit 同步存储各提供两个宽读口，再由 word 内索引选择 FP32。 */
final class WideXCache8R(config: SpmvCsr5MulConfig) extends Module {
  val io = IO(new Bundle {
    val clear = Input(Bool())
    val load = Flipped(Valid(new WideXLoad))
    val request = Flipped(Decoupled(new DecodedBeat))
    val response = Decoupled(new MulIssue)
    val empty = Output(Bool())
  })

  require(config.xReplicas == 4 && config.xCacheWords == 512)
  private val memories = Seq.fill(4)(SyncReadMem(config.xCacheWords, UInt(512.W)))
  when(io.load.valid) { memories.foreach(_.write(io.load.bits.wordIndex, io.load.bits.data)) }

  private val readPending = RegInit(false.B)
  private val readContext = Reg(new DecodedBeat)
  private val resultQueue = Module(new Queue(new MulIssue, 2, pipe = true, flow = false))
  private val occupiedResults = resultQueue.io.count +& readPending.asUInt
  private val releasedResults = occupiedResults - resultQueue.io.deq.fire.asUInt
  io.request.ready := releasedResults < 2.U
  private val requestFire = io.request.fire
  private val readWords = Wire(Vec(8, UInt(512.W)))
  for (lane <- 0 until 8) {
    readWords(lane) := memories(lane / 2).read(
      io.request.bits.lanes(lane).localCol(12, 4),
      requestFire && io.request.bits.lanes(lane).valid)
  }

  when(io.clear) {
    readPending := false.B
  }.otherwise {
    readPending := requestFire
    when(requestFire) { readContext := io.request.bits }
  }
  // 同步宽读预占一个结果位置，下一拍的八路选择值不会被下游反压覆盖。
  resultQueue.io.enq.valid := readPending
  resultQueue.io.enq.bits.decoded := readContext
  for (lane <- 0 until 8) {
    val elements = readWords(lane).asTypeOf(Vec(16, UInt(32.W)))
    resultQueue.io.enq.bits.x(lane) := Mux(readContext.lanes(lane).valid,
      elements(readContext.lanes(lane).localCol(3, 0)), 0.U)
  }
  when(readPending) { assert(resultQueue.io.enq.ready) }
  io.response <> resultQueue.io.deq
  io.empty := !readPending && resultQueue.io.count === 0.U
}

private final class SpmvF32MulDpi extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val valid = Input(Bool())
    val a = Input(UInt(32.W))
    val x = Input(UInt(32.W))
    val result = Output(UInt(32.W))
    val flags = Output(UInt(5.W))
  })

  override def desiredName: String = "SpmvF32MulDpi"
  setInline("SpmvF32MulDpi.sv",
    """module SpmvF32MulDpi(input logic valid, input logic [31:0] a, input logic [31:0] x,
      |  output logic [31:0] result, output logic [4:0] flags);
      |  import "DPI-C" function void spmv_f32_mul(input int unsigned a_bits,
      |    input int unsigned x_bits, output int unsigned result_bits, output int unsigned flags);
      |  int unsigned dpiResult;
      |  int unsigned dpiFlags;
      |  always_comb begin
      |    result = '0;
      |    flags = '0;
      |    dpiResult = '0;
      |    dpiFlags = '0;
      |    if (valid) begin
      |      spmv_f32_mul(a, x, dpiResult, dpiFlags);
      |      result = dpiResult;
      |      flags = dpiFlags[4:0];
      |    end
      |  end
      |endmodule
      |""".stripMargin)
}

/** 八个 multiplier 共用一个推进条件，反压时整组流水和全部 sideband 同步保持。 */
final class Fp32MulArray8(config: SpmvCsr5MulConfig) extends Module {
  val io = IO(new Bundle {
    val clear = Input(Bool())
    val input = Flipped(Decoupled(new MulIssue))
    val output = Decoupled(new SpmvProductBeat)
    val empty = Output(Bool())
    val error = Output(Bool())
  })

  private val slotsValid = RegInit(VecInit(Seq.fill(config.multiplierLatency)(false.B)))
  private val slots = Reg(Vec(config.multiplierLatency, new SpmvProductBeat))
  private val tags = Reg(Vec(config.multiplierLatency, UInt(32.W)))
  private val outputQueue = Module(new Queue(new SpmvProductBeat, config.productFifoDepth,
    pipe = true, flow = false))
  private val advance = !slotsValid.last || outputQueue.io.enq.ready
  private val multipliers = Seq.fill(8)(Module(new SpmvF32MulDpi))
  private val expectedIssueTag = RegInit(0.U(32.W))
  private val expectedOutputTag = RegInit(0.U(32.W))
  private val error = RegInit(false.B)

  io.input.ready := advance
  outputQueue.io.enq.valid := slotsValid.last
  outputQueue.io.enq.bits := slots.last
  io.output <> outputQueue.io.deq
  io.empty := !slotsValid.asUInt.orR && outputQueue.io.count === 0.U
  io.error := error

  for (lane <- 0 until 8) {
    multipliers(lane).io.valid := io.input.fire && io.input.bits.decoded.lanes(lane).valid
    multipliers(lane).io.a := io.input.bits.decoded.lanes(lane).a
    multipliers(lane).io.x := io.input.bits.x(lane)
  }

  when(io.clear) {
    slotsValid.foreach(_ := false.B)
    expectedIssueTag := 0.U
    expectedOutputTag := 0.U
    error := false.B
  }.otherwise {
    when(io.input.fire) {
      when(io.input.bits.decoded.tag =/= expectedIssueTag) { error := true.B }
      expectedIssueTag := expectedIssueTag + 1.U
    }
    when(outputQueue.io.enq.fire) {
      when(tags.last =/= expectedOutputTag) { error := true.B }
      for (lane <- 0 until 8) {
        when(!slots.last.lanes(lane).valid &&
          (slots.last.lanes(lane).product.orR || slots.last.lanes(lane).exceptionFlags.orR)) {
          error := true.B
        }
      }
      expectedOutputTag := expectedOutputTag + 1.U
    }
    when(advance) {
      for (index <- config.multiplierLatency - 1 to 1 by -1) {
        slotsValid(index) := slotsValid(index - 1)
        when(slotsValid(index - 1)) {
          slots(index) := slots(index - 1)
          tags(index) := tags(index - 1)
        }
      }
      slotsValid(0) := io.input.fire
      when(io.input.fire) {
        val source = io.input.bits.decoded
        slots(0).globalTileId := source.globalTileId
        slots(0).blockTileId := source.blockTileId
        slots(0).blockRowId := source.blockRowId
        slots(0).blockColId := source.blockColId
        slots(0).blockRowBase := source.blockRowBase
        slots(0).step := source.step
        slots(0).tileLast := source.tileLast
        tags(0) := source.tag
        for (lane <- 0 until 8) {
          slots(0).lanes(lane).valid := source.lanes(lane).valid
          slots(0).lanes(lane).rowStart := source.lanes(lane).rowStart
          slots(0).lanes(lane).rowEnd := source.lanes(lane).rowEnd
          slots(0).lanes(lane).localRow := source.lanes(lane).localRow
          slots(0).lanes(lane).product := multipliers(lane).io.result
          slots(0).lanes(lane).exceptionFlags := multipliers(lane).io.flags
        }
      }
    }
  }
}

/** 单 HBM cell：A/X 请求共享 credit，X 路径由 Scala Config 静态选择。 */
final class OneHbmCsr5MulCell(parameters: SpmvCsr5MulConfig) extends Module {
  val io = IO(new Bundle {
    val config = Flipped(Decoupled(new SpmvCellConfig))
    val start = Input(Bool())
    val axi = new SpmvAxiReadMasterIO(parameters.axiAddrWidth, parameters.axiDataWidth,
      parameters.axiIdWidth)
    val product = Decoupled(new SpmvProductBeat)
    val done = Output(Bool())
    val errorMask = Output(UInt(13.W))
    val aBeatCount = Output(UInt(32.W))
    val xBeatCount = Output(UInt(32.W))
    val aBurstCount = Output(UInt(32.W))
    val xBurstCount = Output(UInt(32.W))
    val packetCount = Output(UInt(32.W))
    val xGroupCount = Output(UInt(32.W))
    val productBeatCount = Output(UInt(32.W))
    val productCount = Output(UInt(32.W))
    val pcDataCycles = Output(UInt(64.W))
    val pcIdleCycles = Output(UInt(64.W))
    val joinWaitACycles = Output(UInt(64.W))
    val joinWaitXCycles = Output(UInt(64.W))
    val cacheLoadCycles = Output(UInt(64.W))
    val firstProductLatency = Output(UInt(64.W))
    val runCycles = Output(UInt(64.W))
    val stallCycles = Output(UInt(64.W))
    val fpFlags = Output(UInt(5.W))
  })

  private val idle :: ready :: run :: drain :: finished :: Nil = Enum(5)
  private val state = RegInit(idle)
  private val blockRows = Reg(UInt(14.W))
  private val blockCols = Reg(UInt(14.W))
  private val aAddress = Reg(UInt(64.W))
  private val aBeats = Reg(UInt(32.W))
  private val xAddress = Reg(UInt(64.W))
  private val xBeats = Reg(UInt(32.W))
  private val xCrc32 = Reg(UInt(32.W))
  private val outstandingLimit = Reg(UInt(2.W))
  private val expectedPackets = Reg(UInt(32.W))
  private val expectedProductBeats = Reg(UInt(32.W))
  private val expectedProducts = Reg(UInt(32.W))
  private val errors = RegInit(0.U(13.W))
  private val productBeatCount = RegInit(0.U(32.W))
  private val productCount = RegInit(0.U(32.W))
  private val joinWaitA = RegInit(0.U(64.W))
  private val joinWaitX = RegInit(0.U(64.W))
  private val cacheLoadCycles = RegInit(0.U(64.W))
  private val firstProductLatency = RegInit(0.U(64.W))
  private val firstProductSeen = RegInit(false.B)
  private val runCycles = RegInit(0.U(64.W))
  private val stallCycles = RegInit(0.U(64.W))
  private val fpFlags = RegInit(0.U(5.W))

  private val hbm = Module(new SharedHbmReadScheduler(parameters))
  private val decoder = Module(new Csr5PacketDecoder(parameters))
  private val multipliers = Module(new Fp32MulArray8(parameters))
  private val issue = Wire(Decoupled(new MulIssue))
  private val xDone = WireDefault(false.B)
  private val xFrontendEmpty = WireDefault(false.B)
  private val xLoadError = WireDefault(false.B)
  private val xCrcError = WireDefault(false.B)
  private val xGroupCount = WireDefault(0.U(32.W))
  private val issueEmpty = WireDefault(false.B)
  private val pairedDecodedValid = WireDefault(false.B)
  private val pairedXValid = WireDefault(false.B)
  private val joinError = WireDefault(false.B)

  hbm.io.start := state === ready && io.start
  hbm.io.aAddress := aAddress
  hbm.io.aBeats := aBeats
  hbm.io.xAddress := xAddress
  hbm.io.xBeats := xBeats
  hbm.io.outstandingLimit := outstandingLimit
  io.axi <> hbm.io.axi

  decoder.io.blockRows := blockRows
  decoder.io.blockCols := blockCols
  decoder.io.streamFinished := hbm.io.aDone && !hbm.io.aOutput.valid
  decoder.io.input.bits := hbm.io.aOutput.bits
  decoder.io.input.valid := false.B
  hbm.io.aOutput.ready := false.B
  hbm.io.xOutput.ready := false.B
  decoder.io.output.ready := false.B
  issue.valid := false.B
  issue.bits := 0.U.asTypeOf(new MulIssue)

  parameters.xMode match {
    case SpmvXMode.Paired =>
      val unpacker = Module(new PairedXUnpacker)
      val join = Module(new PairedXJoin)
      unpacker.io.clear := io.config.fire
      unpacker.io.expectedBeats := xBeats
      unpacker.io.expectedGroups := expectedProductBeats
      unpacker.io.expectedCrc := xCrc32
      unpacker.io.input <> hbm.io.xOutput
      join.io.clear := io.config.fire
      join.io.expectedBeats := expectedProductBeats
      join.io.decoded <> decoder.io.output
      join.io.pairedX <> unpacker.io.output
      issue <> join.io.output
      decoder.io.input.valid := hbm.io.aOutput.valid
      hbm.io.aOutput.ready := decoder.io.input.ready
      xDone := unpacker.io.done
      xFrontendEmpty := unpacker.io.done
      xLoadError := unpacker.io.loadError
      xCrcError := unpacker.io.crcError
      xGroupCount := unpacker.io.groupCount
      issueEmpty := join.io.empty
      pairedDecodedValid := join.io.decoded.valid
      pairedXValid := join.io.pairedX.valid
      joinError := join.io.error
    case SpmvXMode.Cached =>
      val loader = Module(new CachedXLoader)
      val cache = Module(new WideXCache8R(parameters))
      loader.io.clear := io.config.fire
      loader.io.blockCols := blockCols
      loader.io.expectedBeats := xBeats
      loader.io.expectedCrc := xCrc32
      loader.io.input <> hbm.io.xOutput
      cache.io.clear := io.config.fire
      cache.io.load <> loader.io.load
      cache.io.request <> decoder.io.output
      issue <> cache.io.response
      decoder.io.input.valid := hbm.io.aOutput.valid && loader.io.done
      hbm.io.aOutput.ready := decoder.io.input.ready && loader.io.done
      xDone := loader.io.done
      xFrontendEmpty := loader.io.done
      xLoadError := loader.io.loadError
      xCrcError := loader.io.crcError
      issueEmpty := cache.io.empty
  }

  multipliers.io.input <> issue
  io.product <> multipliers.io.output
  private val clear = io.config.fire
  decoder.io.clear := clear
  multipliers.io.clear := clear

  io.config.ready := state === idle || state === finished
  io.done := state === finished
  io.errorMask := errors
  io.aBeatCount := hbm.io.aBeatCount
  io.xBeatCount := hbm.io.xBeatCount
  io.aBurstCount := hbm.io.aBurstCount
  io.xBurstCount := hbm.io.xBurstCount
  io.packetCount := decoder.io.packetCount
  io.xGroupCount := xGroupCount
  io.productBeatCount := productBeatCount
  io.productCount := productCount
  io.pcDataCycles := hbm.io.pcDataCycles
  io.pcIdleCycles := hbm.io.pcIdleCycles
  io.joinWaitACycles := joinWaitA
  io.joinWaitXCycles := joinWaitX
  io.cacheLoadCycles := cacheLoadCycles
  io.firstProductLatency := firstProductLatency
  io.runCycles := runCycles
  io.stallCycles := stallCycles
  io.fpFlags := fpFlags

  private val drained = state === drain && !hbm.io.aOutput.valid && !hbm.io.xOutput.valid &&
    decoder.io.idle && xDone && xFrontendEmpty && issueEmpty && multipliers.io.empty &&
    !io.product.valid
  private val accountingError = drained && (hbm.io.aBeatCount =/= aBeats ||
    hbm.io.xBeatCount =/= xBeats || decoder.io.packetCount =/= expectedPackets ||
    productBeatCount =/= expectedProductBeats || productCount =/= expectedProducts ||
    (parameters.xMode == SpmvXMode.Paired).B && xGroupCount =/= expectedProductBeats)
  private val liveErrors = WireInit(VecInit(Seq.fill(13)(false.B)))
  for (index <- 0 until 13) {
    liveErrors(index) := decoder.io.errorMask(index)
  }
  liveErrors(0) := io.start && state =/= ready
  liveErrors(1) := xLoadError
  liveErrors(2) := decoder.io.errorMask(2) || hbm.io.error
  liveErrors(9) := xCrcError
  liveErrors(10) := joinError
  liveErrors(11) := multipliers.io.error
  liveErrors(12) := accountingError

  if (parameters.xMode == SpmvXMode.Paired) {
    when((state === run || state === drain) && pairedXValid && !pairedDecodedValid) {
      joinWaitA := joinWaitA + 1.U
    }
    when((state === run || state === drain) && pairedDecodedValid && !pairedXValid) {
      joinWaitX := joinWaitX + 1.U
    }
  }
  if (parameters.xMode == SpmvXMode.Cached) {
    when((state === run || state === drain) && !xDone) {
      cacheLoadCycles := cacheLoadCycles + 1.U
    }
  }

  when(liveErrors.asUInt.orR) { errors := errors | liveErrors.asUInt }
  when(state === run || state === drain) { runCycles := runCycles + 1.U }
  when(io.product.valid && !io.product.ready) { stallCycles := stallCycles + 1.U }
  when(!firstProductSeen && io.product.valid) {
    firstProductSeen := true.B
    firstProductLatency := runCycles
  }

  when(io.config.fire) {
    val aEnd = io.config.bits.aAddress + (io.config.bits.aBeats << 6)
    val xEnd = io.config.bits.xAddress + (io.config.bits.xBeats << 6)
    val addressInvalid = io.config.bits.aAddress(5, 0).orR || io.config.bits.xAddress(5, 0).orR ||
      io.config.bits.aAddress < parameters.hbmBase.U ||
      io.config.bits.xAddress < parameters.hbmBase.U ||
      io.config.bits.aBeats === 0.U || io.config.bits.xBeats === 0.U ||
      aEnd < io.config.bits.aAddress || xEnd < io.config.bits.xAddress ||
      aEnd > (parameters.hbmBase + parameters.hbmBytes).U ||
      xEnd > (parameters.hbmBase + parameters.hbmBytes).U ||
      !(aEnd <= io.config.bits.xAddress || xEnd <= io.config.bits.aAddress)
    val controlInvalid = io.config.bits.blockRows === 0.U ||
      io.config.bits.blockRows > parameters.maxBlockRows.U ||
      io.config.bits.blockCols === 0.U || io.config.bits.blockCols > parameters.maxBlockCols.U ||
      addressInvalid || io.config.bits.expectedPackets === 0.U ||
      io.config.bits.expectedProductBeats === 0.U || io.config.bits.expectedProducts === 0.U ||
      io.config.bits.outstandingLimit === 0.U ||
      io.config.bits.outstandingLimit > parameters.outstandingBursts.U
    val expectedXBeats = parameters.xMode match {
      case SpmvXMode.Paired => (io.config.bits.expectedProductBeats + 1.U) >> 1
      case SpmvXMode.Cached => (io.config.bits.blockCols + 15.U) >> 4
    }
    val xDescriptionInvalid = io.config.bits.xBeats =/= expectedXBeats
    blockRows := io.config.bits.blockRows
    blockCols := io.config.bits.blockCols
    aAddress := io.config.bits.aAddress
    aBeats := io.config.bits.aBeats
    xAddress := io.config.bits.xAddress
    xBeats := io.config.bits.xBeats
    xCrc32 := io.config.bits.xCrc32
    outstandingLimit := io.config.bits.outstandingLimit
    expectedPackets := io.config.bits.expectedPackets
    expectedProductBeats := io.config.bits.expectedProductBeats
    expectedProducts := io.config.bits.expectedProducts
    errors := Mux(controlInvalid, 1.U(13.W), 0.U) |
      Mux(xDescriptionInvalid, 2.U(13.W), 0.U)
    productBeatCount := 0.U
    productCount := 0.U
    joinWaitA := 0.U
    joinWaitX := 0.U
    cacheLoadCycles := 0.U
    firstProductLatency := 0.U
    firstProductSeen := false.B
    runCycles := 0.U
    stallCycles := 0.U
    fpFlags := 0.U
    state := Mux(controlInvalid || xDescriptionInvalid, finished, ready)
  }

  when(state === ready && io.start) { state := run }
  when(state === run && hbm.io.aDone && hbm.io.xDone) { state := drain }
  when(io.product.fire) {
    val validProducts = PopCount(io.product.bits.lanes.map(_.valid))
    productBeatCount := productBeatCount + 1.U
    productCount := productCount + validProducts
    fpFlags := fpFlags |
      io.product.bits.lanes.map(lane => Mux(lane.valid, lane.exceptionFlags, 0.U)).reduce(_ | _)
  }
  when(drained) { state := finished }
}

/** 仿真顶层内部闭合 AXI 到 512-bit DPI HBM，不暴露 host 侧 X 流接口。 */
final class SpmvOneHbmCsr5MulSimulationTop(config: SpmvCsr5MulConfig) extends Module {
  override def desiredName: String = "SpmvOneHbmCsr5MulSimulationTop"

  val io = IO(new Bundle {
    val config = Flipped(Decoupled(new SpmvCellConfig))
    val start = Input(Bool())
    val product = Decoupled(new SpmvProductBeat)
    val done = Output(Bool())
    val errorMask = Output(UInt(13.W))
    val aBeatCount = Output(UInt(32.W))
    val xBeatCount = Output(UInt(32.W))
    val aBurstCount = Output(UInt(32.W))
    val xBurstCount = Output(UInt(32.W))
    val packetCount = Output(UInt(32.W))
    val xGroupCount = Output(UInt(32.W))
    val productBeatCount = Output(UInt(32.W))
    val productCount = Output(UInt(32.W))
    val pcDataCycles = Output(UInt(64.W))
    val pcIdleCycles = Output(UInt(64.W))
    val joinWaitACycles = Output(UInt(64.W))
    val joinWaitXCycles = Output(UInt(64.W))
    val cacheLoadCycles = Output(UInt(64.W))
    val firstProductLatency = Output(UInt(64.W))
    val runCycles = Output(UInt(64.W))
    val stallCycles = Output(UInt(64.W))
    val fpFlags = Output(UInt(5.W))
  })

  private val cell = Module(new OneHbmCsr5MulCell(config))
  private val hbm = Module(new SpmvHbmDpiReadSlave(config))

  cell.io.config <> io.config
  cell.io.start := io.start
  io.product <> cell.io.product
  io.done := cell.io.done
  io.errorMask := cell.io.errorMask
  io.aBeatCount := cell.io.aBeatCount
  io.xBeatCount := cell.io.xBeatCount
  io.aBurstCount := cell.io.aBurstCount
  io.xBurstCount := cell.io.xBurstCount
  io.packetCount := cell.io.packetCount
  io.xGroupCount := cell.io.xGroupCount
  io.productBeatCount := cell.io.productBeatCount
  io.productCount := cell.io.productCount
  io.pcDataCycles := cell.io.pcDataCycles
  io.pcIdleCycles := cell.io.pcIdleCycles
  io.joinWaitACycles := cell.io.joinWaitACycles
  io.joinWaitXCycles := cell.io.joinWaitXCycles
  io.cacheLoadCycles := cell.io.cacheLoadCycles
  io.firstProductLatency := cell.io.firstProductLatency
  io.runCycles := cell.io.runCycles
  io.stallCycles := cell.io.stallCycles
  io.fpFlags := cell.io.fpFlags

  hbm.io.clock := clock
  hbm.io.reset := reset.asBool
  hbm.io.arValid := cell.io.axi.ar.valid
  cell.io.axi.ar.ready := hbm.io.arReady
  hbm.io.arId := cell.io.axi.ar.bits.id
  hbm.io.arAddr := cell.io.axi.ar.bits.addr
  hbm.io.arLen := cell.io.axi.ar.bits.len
  hbm.io.arSize := cell.io.axi.ar.bits.size
  hbm.io.arBurst := cell.io.axi.ar.bits.burst
  cell.io.axi.r.valid := hbm.io.rValid
  hbm.io.rReady := cell.io.axi.r.ready
  cell.io.axi.r.bits.id := hbm.io.rId
  cell.io.axi.r.bits.data := hbm.io.rData
  cell.io.axi.r.bits.resp := hbm.io.rResp
  cell.io.axi.r.bits.last := hbm.io.rLast
}
