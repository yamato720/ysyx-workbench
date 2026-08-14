package accelerators.spmv.input

import accelerators.spmv.SpmvInputConfig
import chisel3._
import chisel3.util._

/** 窗口化 FP64 local_X。
  *
  * 完整 X 仍在 HBM。这里缓存与 Cuper A 分片同宽的 8192 列窗口：4 份副本，
  * 按写宽度切 bank。双路 X 每拍最多写入 16 个 FP64，因此 bank 数为 16。
  * 填充由 X 广播完成；Cuper 的 8 个 slot 固定按 `p / 2` 映射到四份副本，
  * 每份副本提供两个读端口，从而支持一个 512-bit A beat 的 8 路并行乘法。
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
    Seq.fill(bankCount)(Mem(bankDepth, UInt(elementWidth.W)))
  }

  private val writeAddress = io.writeColumn >> bankWidth
  for (replica <- 0 until replicaCount) {
    for (bank <- 0 until bankCount) {
      when(io.writeValid && io.writeMask(bank)) {
        memories(replica)(bank).write(writeAddress, io.writeElements(bank))
      }
    }
  }

  // 每个 replica 只实例化它应承担的两个 read port；不要把 8 个 lane 都接到每一个
  // bank，否则会把 Cuper 的 4x8192 双读副本误展开成 8-read-port RAM。
  for (replica <- 0 until replicaCount) {
    for (port <- 0 until readsPerReplica) {
      val lane = replica * readsPerReplica + port
      val readBank = io.readColumn(lane)(bankWidth - 1, 0)
      val readAddress = io.readColumn(lane) >> bankWidth
      val readValue = WireDefault(0.U(elementWidth.W))
      for (bank <- 0 until bankCount) {
        when(readBank === bank.U) {
          readValue := memories(replica)(bank).read(readAddress)
        }
      }
      io.readData(lane) := RegEnable(readValue, 0.U(elementWidth.W), io.readEnable(lane))
    }
  }

  private val written = RegInit(0.U(log2Ceil(windowSize + 1).W))
  when(io.writeValid) {
    val next = written +& PopCount(io.writeMask)
    written := Mux(next >= windowSize.U, windowSize.U, next)
  }
  io.filled := written === windowSize.U
}
