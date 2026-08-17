package accelerators.spmv.input

import accelerators.spmv.SpmvInputConfig
import chisel3._
import chisel3.util._
import npc.ip.memory.{OnChipMemoryPrimitive, OnChipTrueDualPortMemory}

/** 窗口化 FP64 local_X。
  *
  * 完整 X 仍在双路 HBM。这里缓存与 Cuper A 分片同宽的 8192 列窗口：4 份副本，
  * 按 8 个 FP64 写 lane 切 bank。每个 (replica, bank) 是一颗公共真双口片上 RAM，
  * 对应 FPGA 上的 1R+1W URAM。预装和重叠期都可以每拍写满 8 个 FP64；重叠时每份
  * 副本只保留一个 A 读，避免同一 bank 上出现第二读。Cuper 的 8 个 slot 固定按
  * `p / 2` 映射到四份副本。
  */
final class SpmvLocalX(config: SpmvInputConfig) extends Module {
  private val windowSize = config.xWindowSize
  private val replicaCount = config.xReplicaCount
  private val bankCount = config.xBankCount
  private val bankDepth = config.xBankDepth
  private val elementWidth = config.xElementWidth
  private val columnWidth = log2Ceil(windowSize)
  private val bankWidth = log2Ceil(bankCount)
  private val readPortCount = SpmvCuperDecode.lanesPerBeat
  require(readPortCount % replicaCount == 0,
    s"Cuper 读 lane 数必须可均分 local_X 副本，实际为 $readPortCount/$replicaCount")
  private val readsPerReplica = readPortCount / replicaCount

  val io = IO(new Bundle {
    val writeValid = Input(Bool())
    val writeColumn = Input(UInt(columnWidth.W))
    val writeElements = Input(Vec(bankCount, UInt(elementWidth.W)))
    val writeMask = Input(Vec(bankCount, Bool()))
    /** Cuper lane p 固定连到 replica p / readsPerReplica。 */
    val readEnable = Input(Vec(readPortCount, Bool()))
    val readColumn = Input(Vec(readPortCount, UInt(columnWidth.W)))
    val readData = Output(Vec(readPortCount, UInt(elementWidth.W)))
    val filled = Output(Bool())
  })

  private val memories = Seq.fill(replicaCount) {
    Seq.fill(bankCount)(Module(new OnChipTrueDualPortMemory(
      bankDepth, elementWidth, OnChipMemoryPrimitive.UltraRam)))
  }

  private val writeAddress = io.writeColumn >> bankWidth
  private val readBanks = io.readColumn.map(_(bankWidth - 1, 0))
  private val readAddresses = io.readColumn.map(_ >> bankWidth)

  require(readsPerReplica == 2, "当前真双口 bank 只为每副本的两个 Cuper lane 接线")

  for (replica <- 0 until replicaCount) {
    val lane0 = replica * readsPerReplica
    val lane1 = lane0 + 1
    val enable0 = io.readEnable(lane0)
    val enable1 = io.readEnable(lane1)
    when(io.writeValid) {
      assert(PopCount(VecInit(enable0, enable1).asUInt) <= 1.U,
        "local_X overlap permits one read per replica")
    }

    for (bank <- 0 until bankCount) {
      val memory = memories(replica)(bank)
      val hit0 = enable0 && readBanks(lane0) === bank.U
      val hit1 = enable1 && readBanks(lane1) === bank.U
      val writing = io.writeValid && io.writeMask(bank)
      when(writing) {
        assert(!(hit0 && hit1), "local_X write cycle cannot dual-read one bank")
      }
      memory.io.a.enable := writing || (!io.writeValid && hit0)
      memory.io.a.write := writing
      memory.io.a.address := Mux(writing, writeAddress, readAddresses(lane0))
      memory.io.a.wdata := io.writeElements(bank)
      memory.io.b.enable := Mux(io.writeValid, hit0 || hit1, hit1)
      memory.io.b.write := false.B
      memory.io.b.address := Mux(io.writeValid && hit0, readAddresses(lane0), readAddresses(lane1))
      memory.io.b.wdata := 0.U
    }

    val issuedEnable0 = RegNext(enable0, false.B)
    val issuedEnable1 = RegNext(enable1, false.B)
    val issuedWrite = RegNext(io.writeValid, false.B)
    val issuedBank0 = RegEnable(readBanks(lane0), 0.U(bankWidth.W), enable0)
    val issuedBank1 = RegEnable(readBanks(lane1), 0.U(bankWidth.W), enable1)
    val data0FromA = WireDefault(0.U(elementWidth.W))
    val data0FromB = WireDefault(0.U(elementWidth.W))
    val data1 = WireDefault(0.U(elementWidth.W))
    for (bank <- 0 until bankCount) {
      when(issuedBank0 === bank.U) {
        data0FromA := memories(replica)(bank).io.a.rdata
        data0FromB := memories(replica)(bank).io.b.rdata
      }
      when(issuedBank1 === bank.U) {
        data1 := memories(replica)(bank).io.b.rdata
      }
    }
    io.readData(lane0) := Mux(issuedEnable0,
      Mux(issuedWrite, data0FromB, data0FromA), 0.U(elementWidth.W))
    io.readData(lane1) := Mux(issuedEnable1, data1, 0.U(elementWidth.W))
  }

  private val written = RegInit(0.U(log2Ceil(windowSize + 1).W))
  when(io.writeValid) {
    val next = written +& PopCount(io.writeMask)
    written := Mux(next >= windowSize.U, windowSize.U, next)
  }
  io.filled := written === windowSize.U
}
