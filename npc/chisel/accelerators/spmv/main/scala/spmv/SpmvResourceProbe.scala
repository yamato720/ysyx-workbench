package spmv

import chisel3._
import chisel3.util._
import chisel3.util.HasBlackBoxInline
import npc.{SpmvAcceleratorConfig, SpmvOnChipStorage}

final class SpmvAxiReadAddress(addrWidth: Int, idWidth: Int) extends Bundle {
  val id = UInt(idWidth.W)
  val addr = UInt(addrWidth.W)
  val len = UInt(8.W)
  val size = UInt(3.W)
  val burst = UInt(2.W)
  val lock = Bool()
  val cache = UInt(4.W)
  val prot = UInt(3.W)
  val qos = UInt(4.W)
}

final class SpmvAxiReadData(dataWidth: Int, idWidth: Int) extends Bundle {
  val id = UInt(idWidth.W)
  val data = UInt(dataWidth.W)
  val resp = UInt(2.W)
  val last = Bool()
}

final class SpmvAxiReadMasterIO(addrWidth: Int, dataWidth: Int, idWidth: Int) extends Bundle {
  val ar = Irrevocable(new SpmvAxiReadAddress(addrWidth, idWidth))
  val r = Flipped(Irrevocable(new SpmvAxiReadData(dataWidth, idWidth)))
}

private final class SpmvUramMemory(depth: Int, width: Int) extends BlackBox(Map(
  "DEPTH" -> depth,
  "WIDTH" -> width
)) with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val aWriteEnable = Input(Bool())
    val aWriteAddress = Input(UInt(math.max(1, log2Ceil(depth)).W))
    val aWriteData = Input(UInt(width.W))
    val aReadEnable = Input(Bool())
    val aReadAddress = Input(UInt(math.max(1, log2Ceil(depth)).W))
    val aReadData = Output(UInt(width.W))
    val bWriteEnable = Input(Bool())
    val bWriteAddress = Input(UInt(math.max(1, log2Ceil(depth)).W))
    val bWriteData = Input(UInt(width.W))
    val bReadEnable = Input(Bool())
    val bReadAddress = Input(UInt(math.max(1, log2Ceil(depth)).W))
    val bReadData = Output(UInt(width.W))
  })

  override def desiredName: String = "SpmvUramMemory"

  setInline("SpmvUramMemory.sv",
    """module SpmvUramMemory #(
      |  parameter integer DEPTH = 2048,
      |  parameter integer WIDTH = 64,
      |  parameter integer ADDR_WIDTH = (DEPTH <= 1) ? 1 : $clog2(DEPTH)
      |) (
      |  input  wire                  clock,
      |  input  wire                  aWriteEnable,
      |  input  wire [ADDR_WIDTH-1:0] aWriteAddress,
      |  input  wire [WIDTH-1:0]      aWriteData,
      |  input  wire                  aReadEnable,
      |  input  wire [ADDR_WIDTH-1:0] aReadAddress,
      |  output reg  [WIDTH-1:0]      aReadData,
      |  input  wire                  bWriteEnable,
      |  input  wire [ADDR_WIDTH-1:0] bWriteAddress,
      |  input  wire [WIDTH-1:0]      bWriteData,
      |  input  wire                  bReadEnable,
      |  input  wire [ADDR_WIDTH-1:0] bReadAddress,
      |  output reg  [WIDTH-1:0]      bReadData
      |);
      |`ifdef VERILATOR
      |  // 仿真使用行为模型；综合分支直接实例化 U55C 的 URAM288 原语。
      |  reg [WIDTH-1:0] memory [0:DEPTH-1];
      |  always @(posedge clock) begin
      |    if (aWriteEnable) memory[aWriteAddress] <= aWriteData;
      |    if (aReadEnable) aReadData <= memory[aReadAddress];
      |    if (bWriteEnable) memory[bWriteAddress] <= bWriteData;
      |    if (bReadEnable) bReadData <= memory[bReadAddress];
      |  end
      |`else
      |  wire [71:0] aDout;
      |  wire [71:0] bDout;
      |  URAM288_BASE #(.BWE_MODE_A("PARITY_INTERLEAVED"), .BWE_MODE_B("PARITY_INTERLEAVED"),
      |    .EN_ECC_RD_A("FALSE"), .EN_ECC_RD_B("FALSE"), .EN_ECC_WR_A("FALSE"),
      |    .EN_ECC_WR_B("FALSE"), .OREG_A("FALSE"), .OREG_B("FALSE")) uram (
      |    .ADDR_A({12'b0, aWriteAddress}), .ADDR_B({12'b0, bWriteAddress}),
      |    .BWE_A({9{aWriteEnable}}), .BWE_B({9{bWriteEnable}}), .CLK(clock),
      |    .DIN_A({8'b0, aWriteData}), .DIN_B({8'b0, bWriteData}),
      |    .EN_A(aWriteEnable | aReadEnable), .EN_B(bWriteEnable | bReadEnable),
      |    .INJECT_DBITERR_A(1'b0), .INJECT_DBITERR_B(1'b0),
      |    .INJECT_SBITERR_A(1'b0), .INJECT_SBITERR_B(1'b0),
      |    .OREG_CE_A(aReadEnable), .OREG_CE_B(bReadEnable),
      |    .OREG_ECC_CE_A(1'b0), .OREG_ECC_CE_B(1'b0),
      |    .RDB_WR_A(aWriteEnable), .RDB_WR_B(bWriteEnable),
      |    .RST_A(1'b0), .RST_B(1'b0), .SLEEP(1'b0),
      |    .DOUT_A(aDout), .DOUT_B(bDout), .DBITERR_A(), .DBITERR_B(),
      |    .SBITERR_A(), .SBITERR_B());
      |  assign aReadData = aDout[WIDTH-1:0];
      |  assign bReadData = bDout[WIDTH-1:0];
      |`endif
      |endmodule
      |""".stripMargin)
}

/** 单个 HBM pseudo-channel 的加载、URAM bank 写入和并行读 checksum 状态机。 */
final class SpmvResourceProbeLane(config: SpmvAcceleratorConfig) extends Module {
  require(config.storage == SpmvOnChipStorage.UltraRam)

  val io = IO(new Bundle {
    val start = Input(Bool())
    val baseAddress = Input(UInt(config.axiAddrWidth.W))
    val axi = new SpmvAxiReadMasterIO(config.axiAddrWidth, config.axiDataWidth, config.axiIdWidth)
    val checksum = Output(UInt(config.elementWidth.W))
    val done = Output(Bool())
    val error = Output(Bool())
  })

  private val idle :: requestBurst :: receiveBeat :: scanCache :: finished :: Nil = Enum(5)
  private val beatIndexWidth = math.max(1, log2Ceil(config.beatsPerPc))
  private val burstIndexWidth = math.max(1, log2Ceil(config.burstsPerPc))
  private val elementIndexWidth = math.max(1, log2Ceil(config.elementsPerBeat))
  private val cacheCountWidth = math.max(1, log2Ceil(config.elementsPerPc + 1))
  private val scanCountWidth = math.max(1, log2Ceil(config.scanGroups + 1))
  private val cacheAddressWidth = math.max(1, log2Ceil(config.uramBankDepth))
  private val baseAlignmentBits = log2Ceil(config.baseAlignmentBytes)

  val state = RegInit(idle)
  val beatIndex = RegInit(0.U(beatIndexWidth.W))
  val burstIndex = RegInit(0.U(burstIndexWidth.W))
  val serializerIndex = RegInit(0.U(elementIndexWidth.W))
  val writeCount = RegInit(0.U(cacheCountWidth.W))
  val beatBuffer = Reg(UInt(config.axiDataWidth.W))
  val beatBufferValid = RegInit(false.B)
  val scanIssueCount = RegInit(0.U(scanCountWidth.W))
  val scanReturnCount = RegInit(0.U(scanCountWidth.W))
  val checksum = RegInit(0.U(config.elementWidth.W))
  val done = RegInit(false.B)
  val error = RegInit(false.B)

  private val caches = Seq.tabulate(config.uramBanksPerPc) { bank =>
    val cache = Module(new SpmvUramMemory(config.uramBankDepth, config.elementWidth))
    cache.suggestName(f"xCacheUramBank${bank}%02d")
    cache.io.clock := clock
    cache.io.aWriteEnable := false.B
    cache.io.aWriteAddress := 0.U
    cache.io.aWriteData := 0.U
    cache.io.bWriteEnable := false.B
    cache.io.bWriteAddress := 0.U
    cache.io.bWriteData := 0.U
    cache.io.aReadEnable := false.B
    cache.io.aReadAddress := 0.U
    cache.io.bReadEnable := false.B
    cache.io.bReadAddress := 0.U
    cache
  }

  io.axi.ar.valid := state === requestBurst
  io.axi.ar.bits.id := 0.U
  io.axi.ar.bits.addr := io.baseAddress + burstIndex * (config.burstBeats * config.bytesPerBeat).U
  io.axi.ar.bits.len := (config.burstBeats - 1).U
  io.axi.ar.bits.size := log2Ceil(config.bytesPerBeat).U
  io.axi.ar.bits.burst := 1.U
  io.axi.ar.bits.lock := false.B
  io.axi.ar.bits.cache := 0.U
  io.axi.ar.bits.prot := 0.U
  io.axi.ar.bits.qos := 0.U
  io.axi.r.ready := state === receiveBeat && !beatBufferValid

  io.checksum := checksum
  io.done := done
  io.error := error

  val lastBeatInBurst = if (config.burstBeats == 1) true.B else
    beatIndex(log2Ceil(config.burstBeats) - 1, 0) === (config.burstBeats - 1).U

  when((state === idle || state === finished) && io.start) {
    beatIndex := 0.U
    burstIndex := 0.U
    serializerIndex := 0.U
    writeCount := 0.U
    beatBufferValid := false.B
    scanIssueCount := 0.U
    scanReturnCount := 0.U
    checksum := 0.U
    done := false.B
    error := false.B
    when(io.baseAddress(baseAlignmentBits - 1, 0).orR) {
      error := true.B
      done := true.B
      state := finished
    }.otherwise {
      state := requestBurst
    }
  }

  when(state === requestBurst && io.axi.ar.fire) {
    state := receiveBeat
  }

  when(state === receiveBeat && io.axi.r.fire) {
    beatBuffer := io.axi.r.bits.data
    beatBufferValid := true.B
    serializerIndex := 0.U
    when(io.axi.r.bits.resp =/= 0.U || io.axi.r.bits.last =/= lastBeatInBurst) {
      error := true.B
    }
  }

  when(state === receiveBeat && beatBufferValid) {
    val elements = beatBuffer.asTypeOf(Vec(config.elementsPerBeat, UInt(config.elementWidth.W)))
    // 每个写槽根据全局序号选择 bank 和端口；八路配置一拍覆盖四个 bank 的两端口。
    for (slot <- 0 until config.writeElementsPerCycle) {
      val elementIndex = writeCount + slot.U
      val bankIndex = elementIndex % config.uramBanksPerPc.U
      val bankRow = (elementIndex / config.uramBanksPerPc.U)(cacheAddressWidth - 1, 0)
      val usePortB = (elementIndex / config.uramBanksPerPc.U) % 2.U === 1.U
      for (bank <- 0 until config.uramBanksPerPc) {
        when(bankIndex === bank.U && !usePortB) {
          caches(bank).io.aWriteEnable := true.B
          caches(bank).io.aWriteAddress := bankRow
          caches(bank).io.aWriteData := elements(serializerIndex + slot.U)
        }
        when(bankIndex === bank.U && usePortB) {
          caches(bank).io.bWriteEnable := true.B
          caches(bank).io.bWriteAddress := bankRow
          caches(bank).io.bWriteData := elements(serializerIndex + slot.U)
        }
      }
    }
    writeCount := writeCount + config.writeElementsPerCycle.U
    when(serializerIndex === (config.elementsPerBeat - config.writeElementsPerCycle).U) {
      beatBufferValid := false.B
      when(beatIndex === (config.beatsPerPc - 1).U) {
        scanIssueCount := 0.U
        scanReturnCount := 0.U
        checksum := 0.U
        state := scanCache
      }.elsewhen(lastBeatInBurst) {
        beatIndex := beatIndex + 1.U
        burstIndex := burstIndex + 1.U
        state := requestBurst
      }.otherwise {
        beatIndex := beatIndex + 1.U
      }
      }.otherwise {
        serializerIndex := serializerIndex + config.writeElementsPerCycle.U
      }
  }

  val scanReadEnable = state === scanCache && scanIssueCount < config.scanGroups.U
  if (config.readElementsPerCycle == config.uramBanksPerPc * 2) {
    for (cache <- caches) {
      cache.io.aReadEnable := scanReadEnable
      cache.io.aReadAddress := (scanIssueCount * 2.U)(cacheAddressWidth - 1, 0)
      cache.io.bReadEnable := scanReadEnable
      cache.io.bReadAddress := (scanIssueCount * 2.U + 1.U)(cacheAddressWidth - 1, 0)
    }
    val cacheReadValid = RegNext(scanReadEnable, false.B)
    val parallelData = caches.map(cache => cache.io.aReadData ^ cache.io.bReadData).reduce(_ ^ _)
    when(state === scanCache) {
      when(scanReadEnable) {
        scanIssueCount := scanIssueCount + 1.U
      }
      when(cacheReadValid) {
        checksum := checksum ^ parallelData
        scanReturnCount := scanReturnCount + 1.U
        when(scanReturnCount === (config.scanGroups - 1).U) {
          done := true.B
          state := finished
        }
      }
    }
  } else {
    val cache = caches.head
    cache.io.aReadEnable := scanReadEnable
    cache.io.aReadAddress := scanIssueCount(cacheAddressWidth - 1, 0)
    val cacheReadValid = RegNext(scanReadEnable, false.B)
    when(state === scanCache) {
      when(scanReadEnable) {
        scanIssueCount := scanIssueCount + 1.U
      }
      when(cacheReadValid) {
        checksum := checksum ^ cache.io.aReadData
        scanReturnCount := scanReturnCount + 1.U
        when(scanReturnCount === (config.scanGroups - 1).U) {
          done := true.B
          state := finished
        }
      }
    }
  }
}

/** 并行实例化全部 PC，并把每路 checksum 纳入可观察的聚合结果。 */
final class SpmvResourceProbeTop(config: SpmvAcceleratorConfig) extends Module {
  override def desiredName: String = "SpmvResourceProbeTop"

  val io = IO(new Bundle {
    val start = Input(Bool())
    val baseAddresses = Input(Vec(config.hbmPcCount, UInt(config.axiAddrWidth.W)))
    val axi = Vec(config.hbmPcCount,
      new SpmvAxiReadMasterIO(config.axiAddrWidth, config.axiDataWidth, config.axiIdWidth))
    val aggregateChecksum = Output(UInt(config.elementWidth.W))
    val doneMask = Output(UInt(config.hbmPcCount.W))
    val errorMask = Output(UInt(config.hbmPcCount.W))
  })

  val lanes = Seq.tabulate(config.hbmPcCount) { index =>
    val lane = Module(new SpmvResourceProbeLane(config))
    lane.suggestName(f"pc${index}%02d")
    lane.io.start := io.start
    lane.io.baseAddress := io.baseAddresses(index)
    io.axi(index) <> lane.io.axi
    lane
  }
  io.aggregateChecksum := lanes.map(_.io.checksum).reduce(_ ^ _)
  io.doneMask := VecInit(lanes.map(_.io.done)).asUInt
  io.errorMask := VecInit(lanes.map(_.io.error)).asUInt
}
