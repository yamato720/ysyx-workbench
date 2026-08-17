package npc.ip.memory

import chisel3._
import chisel3.util._
import chisel3.util.HasBlackBoxInline

/** 真双口片上 RAM 的单端口合同：每拍该口只能读或写一次。 */
final class OnChipMemoryPortIO(val addrWidth: Int, val dataWidth: Int) extends Bundle {
  val enable = Input(Bool())
  val write = Input(Bool())
  val address = Input(UInt(addrWidth.W))
  val wdata = Input(UInt(dataWidth.W))
  /** 同步读，下一拍有效。 */
  val rdata = Output(UInt(dataWidth.W))
}

/** 片上 RAM 的物理原语选择。HBM/DDR 不走此合同。 */
sealed trait OnChipMemoryPrimitive {
  def profileName: String
}

object OnChipMemoryPrimitive {
  case object Auto extends OnChipMemoryPrimitive {
    override val profileName: String = "auto"
  }
  case object BlockRam extends OnChipMemoryPrimitive {
    override val profileName: String = "block"
  }
  case object UltraRam extends OnChipMemoryPrimitive {
    override val profileName: String = "ultra"
  }
}

/** 公共真双口片上 RAM。
  *
  * 仿真走行为模型；综合走 XPM `xpm_memory_tdpram`，由 `primitive` 映射到
  * BRAM 或 URAM。两口共用时钟，读延迟固定 1 拍。同址读写时读口看到新写值，
  * 供 FPGA 预设计对照 XPM `write_first`。
  */
final class OnChipTrueDualPortMemory(
  val depth: Int,
  val dataWidth: Int,
  val primitive: OnChipMemoryPrimitive = OnChipMemoryPrimitive.UltraRam
) extends Module {
  require(depth >= 2 && (depth & (depth - 1)) == 0,
    s"片上 RAM 深度必须是不小于 2 的二次幂，实际为 $depth")
  require(dataWidth >= 8 && (dataWidth & (dataWidth - 1)) == 0,
    s"片上 RAM 数据位宽必须是至少 8 bit 的二次幂，实际为 $dataWidth")

  private val addrWidth = log2Ceil(depth)
  val io = IO(new Bundle {
    val a = new OnChipMemoryPortIO(addrWidth, dataWidth)
    val b = new OnChipMemoryPortIO(addrWidth, dataWidth)
  })

  private val impl = Module(new OnChipTrueDualPortMemoryBlackBox(
    depth, dataWidth, addrWidth, primitive.profileName))
  impl.io.clock := clock
  impl.io.a_enable := io.a.enable
  impl.io.a_write := io.a.write
  impl.io.a_address := io.a.address
  impl.io.a_wdata := io.a.wdata
  io.a.rdata := impl.io.a_rdata
  impl.io.b_enable := io.b.enable
  impl.io.b_write := io.b.write
  impl.io.b_address := io.b.address
  impl.io.b_wdata := io.b.wdata
  io.b.rdata := impl.io.b_rdata
}

private final class OnChipTrueDualPortMemoryBlackBox(
  depth: Int,
  dataWidth: Int,
  addrWidth: Int,
  primitive: String
) extends BlackBox(Map(
  "DEPTH" -> depth,
  "WIDTH" -> dataWidth,
  "ADDR_WIDTH" -> addrWidth,
  "PRIMITIVE" -> primitive
)) with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val a_enable = Input(Bool())
    val a_write = Input(Bool())
    val a_address = Input(UInt(addrWidth.W))
    val a_wdata = Input(UInt(dataWidth.W))
    val a_rdata = Output(UInt(dataWidth.W))
    val b_enable = Input(Bool())
    val b_write = Input(Bool())
    val b_address = Input(UInt(addrWidth.W))
    val b_wdata = Input(UInt(dataWidth.W))
    val b_rdata = Output(UInt(dataWidth.W))
  })

  override def desiredName: String = "NpcOnChipTrueDualPortMemory"

  setInline("NpcOnChipTrueDualPortMemory.sv",
    """module NpcOnChipTrueDualPortMemory #(
      |  parameter integer DEPTH = 1024,
      |  parameter integer WIDTH = 64,
      |  parameter integer ADDR_WIDTH = 10,
      |  parameter         PRIMITIVE = "ultra"
      |) (
      |  input  wire                  clock,
      |  input  wire                  a_enable,
      |  input  wire                  a_write,
      |  input  wire [ADDR_WIDTH-1:0] a_address,
      |  input  wire [WIDTH-1:0]      a_wdata,
      |  output reg  [WIDTH-1:0]      a_rdata,
      |  input  wire                  b_enable,
      |  input  wire                  b_write,
      |  input  wire [ADDR_WIDTH-1:0] b_address,
      |  input  wire [WIDTH-1:0]      b_wdata,
      |  output reg  [WIDTH-1:0]      b_rdata
      |);
      |`ifdef VERILATOR
      |  reg [WIDTH-1:0] memory [0:DEPTH-1];
      |  wire a_rd = a_enable && !a_write;
      |  wire b_rd = b_enable && !b_write;
      |  wire a_wr = a_enable && a_write;
      |  wire b_wr = b_enable && b_write;
      |  always @(posedge clock) begin
      |    if (a_wr) memory[a_address] <= a_wdata;
      |    if (b_wr) memory[b_address] <= b_wdata;
      |    if (a_rd) begin
      |      if (b_wr && b_address == a_address) a_rdata <= b_wdata;
      |      else a_rdata <= memory[a_address];
      |    end
      |    if (b_rd) begin
      |      if (a_wr && a_address == b_address) b_rdata <= a_wdata;
      |      else b_rdata <= memory[b_address];
      |    end
      |  end
      |`else
      |  xpm_memory_tdpram #(
      |    .ADDR_WIDTH_A(ADDR_WIDTH),
      |    .ADDR_WIDTH_B(ADDR_WIDTH),
      |    .AUTO_SLEEP_TIME(0),
      |    .BYTE_WRITE_WIDTH_A(WIDTH),
      |    .BYTE_WRITE_WIDTH_B(WIDTH),
      |    .CLOCKING_MODE("common_clock"),
      |    .ECC_MODE("no_ecc"),
      |    .MEMORY_INIT_FILE("none"),
      |    .MEMORY_INIT_PARAM("0"),
      |    .MEMORY_OPTIMIZATION("true"),
      |    .MEMORY_PRIMITIVE(PRIMITIVE),
      |    .MEMORY_SIZE(DEPTH * WIDTH),
      |    .MESSAGE_CONTROL(0),
      |    .READ_DATA_WIDTH_A(WIDTH),
      |    .READ_DATA_WIDTH_B(WIDTH),
      |    .READ_LATENCY_A(1),
      |    .READ_LATENCY_B(1),
      |    .READ_RESET_VALUE_A("0"),
      |    .READ_RESET_VALUE_B("0"),
      |    .RST_MODE_A("SYNC"),
      |    .RST_MODE_B("SYNC"),
      |    .USE_EMBEDDED_CONSTRAINT(0),
      |    .USE_MEM_INIT(0),
      |    .WAKEUP_TIME("disable_sleep"),
      |    .WRITE_DATA_WIDTH_A(WIDTH),
      |    .WRITE_DATA_WIDTH_B(WIDTH),
      |    .WRITE_MODE_A("write_first"),
      |    .WRITE_MODE_B("write_first")
      |  ) xpm (
      |    .sleep(1'b0),
      |    .clka(clock),
      |    .ena(a_enable),
      |    .wea(a_write),
      |    .addra(a_address),
      |    .dina(a_wdata),
      |    .douta(a_rdata),
      |    .clkb(clock),
      |    .enb(b_enable),
      |    .web(b_write),
      |    .addrb(b_address),
      |    .dinb(b_wdata),
      |    .doutb(b_rdata),
      |    .regcea(1'b1),
      |    .regceb(1'b1),
      |    .rsta(1'b0),
      |    .rstb(1'b0),
      |    .injectsbiterra(1'b0),
      |    .injectdbiterra(1'b0),
      |    .injectsbiterrb(1'b0),
      |    .injectdbiterrb(1'b0),
      |    .sbiterra(),
      |    .dbiterra(),
      |    .sbiterrb(),
      |    .dbiterrb()
      |  );
      |`endif
      |endmodule
      |""".stripMargin)
}
