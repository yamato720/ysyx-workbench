package accelerators.spmv.input

import accelerators.spmv.SpmvInputConfig
import chisel3._
import chisel3.util._

/** Cuper 控制面 map 的硬件解释器。
  *
  * host 在第一个 512-bit beat 写入 kind、batch 数、矩阵维度和 pointer 数；之后每个
  * beat 写入一个 pointer index 在全部 A HBM channel 上的累计 A beat 边界。原始 Cuper
  * Core 以相邻 pointer 的差值限制每个 batch 的 Matrix_A_Stream，本模块保留同一语义：
  * 每轮 `mulBatch` 只允许对应范围的 A 请求进入 PE。
  *
  * 控制面为每个 A HBM 保存一个 batch 边界。完整 thermal2 按 8192 列分窗需要约 150
  * 个窗口，因此保留 256 项容量；超过静态 map RAM 的作业仍明确报为协议错误而不是静默
  * 错算。
  */
object SpmvCuperMap {
  val mapKind = 1
  val maxBatchCount = 256
  val batchIndexWidth = log2Ceil(maxBatchCount)
}

final class SpmvCuperMap(config: SpmvInputConfig) extends Module {
  private val pointerCount = SpmvCuperMap.maxBatchCount + 1
  private val pointerIndexWidth = log2Ceil(pointerCount + 1)
  private val wordsPerBeat = config.axiDataWidth / 32
  require(wordsPerBeat >= config.aReaderCount,
    s"Cuper map beat 必须容纳每个 A HBM channel 的 pointer，实际为 $wordsPerBeat/${config.aReaderCount}")

  val io = IO(new Bundle {
    /** Ctrl reader 向消费者广播成功的同一拍，map 必须与观测到的 payload 完全一致。 */
    val fire = Input(Bool())
    val data = Input(UInt(config.axiDataWidth.W))
    val last = Input(Bool())
    val batchIndex = Input(UInt(SpmvCuperMap.batchIndexWidth.W))
    val loaded = Output(Bool())
    val batchValid = Output(Bool())
    val batchActive = Output(Vec(config.aReaderCount, Bool()))
    val batchBeatCount = Output(Vec(config.aReaderCount, UInt(32.W)))
    val error = Output(Bool())
  })

  private val words = io.data.asTypeOf(Vec(wordsPerBeat, UInt(32.W)))
  private val pointers = Reg(Vec(pointerCount, Vec(config.aReaderCount, UInt(32.W))))
  private val headerSeen = RegInit(false.B)
  private val pointerBeatsSeen = RegInit(0.U(pointerIndexWidth.W))
  private val declaredBatchCount = RegInit(0.U(32.W))
  private val declaredPointerCount = RegInit(0.U(pointerIndexWidth.W))
  private val loaded = RegInit(false.B)
  private val error = RegInit(false.B)

  when(io.fire) {
    when(!headerSeen) {
      val declaredBatches = words(1)
      val declaredPointers = words(5)
      val headerValid = words(0) === SpmvCuperMap.mapKind.U &&
        declaredBatches =/= 0.U &&
        declaredBatches <= SpmvCuperMap.maxBatchCount.U &&
        words(4) === config.aReaderCount.U &&
        declaredPointers === declaredBatches + 1.U &&
        declaredPointers <= pointerCount.U &&
        !io.last
      headerSeen := true.B
      pointerBeatsSeen := 0.U
      declaredBatchCount := declaredBatches
      declaredPointerCount := declaredPointers(pointerIndexWidth - 1, 0)
      loaded := false.B
      when(!headerValid) {
        error := true.B
      }
    }.otherwise {
      val pointerExpected = pointerBeatsSeen < declaredPointerCount
      val finalPointer = pointerBeatsSeen + 1.U === declaredPointerCount
      when(error || !pointerExpected || io.last =/= finalPointer) {
        error := true.B
      }.otherwise {
        for (channel <- 0 until config.aReaderCount) {
          pointers(pointerBeatsSeen)(channel) := words(channel)
        }
        pointerBeatsSeen := pointerBeatsSeen + 1.U
        when(finalPointer) {
          loaded := true.B
        }
      }
    }
  }

  private val batchValid = loaded && !error && io.batchIndex < declaredBatchCount
  private val currentBatchIndex = Cat(0.U(1.W), io.batchIndex)
  private val nextBatchIndex = io.batchIndex +& 1.U
  for (channel <- 0 until config.aReaderCount) {
    val begin = pointers(currentBatchIndex)(channel)
    val end = pointers(nextBatchIndex)(channel)
    io.batchBeatCount(channel) := Mux(batchValid, end - begin, 0.U)
    io.batchActive(channel) := batchValid && end =/= begin
  }
  io.loaded := loaded && !error
  io.batchValid := batchValid
  io.error := error
}
