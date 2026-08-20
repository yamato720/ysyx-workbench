package accelerators.spmv.inputmul.cuperflow

import accelerators.spmv.SpmvCuperflowConfig
import accelerators.spmv.inputmul.common.{SpmvCuperDecode, SpmvProduct}
import chisel3._
import chisel3.util._

/** FMUL 与后续局部累加之间冻结的原子事务。
  *
  * 一个 A beat 的八个 FMUL response 不能各自交给下游：在 response 背压下，它们会在
  * 不同周期出现，下游也无法从单条 response 重建 beat 的 chunk 边界。本 Bundle 因而
  * 保留原始 A beat 的全部 L1 sideband，并只在八条必需 response 都到齐时握手。
  */
final class SpmvCuperflowProductBeat(config: SpmvCuperflowConfig) extends Bundle {
  val pc = UInt(math.max(1, log2Ceil(config.hbmPcCount)).W)
  /** GROUP_MAP 的 sliceGroup / PC，供 V2 按 wave 汇聚 contributor。 */
  val wave = UInt(16.W)
  val batch = UInt(16.W)
  /** 单 PC、单次 start 内的 A beat 顺序号；背压不会改变它。 */
  val beatSeq = UInt(32.W)
  val laneValid = UInt(SpmvCuperDecode.lanesPerBeat.W)
  val localRow = Vec(SpmvCuperDecode.lanesPerBeat, UInt(SpmvCuperDecode.rowBits.W))
  val rowLast = Vec(SpmvCuperDecode.lanesPerBeat, Bool())
  val chunkMode = UInt(2.W)
  val product = Vec(SpmvCuperDecode.lanesPerBeat, UInt(config.xElementWidth.W))
}

/** 把同一个 A beat 的八路 FMUL response 重新收敛为一个 ProductBeat。
  *
  * context FIFO 在 A beat 被乘法器接受的同拍记录 sideband；每个 lane 有独立 response
  * FIFO，因此 downstream backpressure 只会向 FMUL 的标准 req/resp 合同反压，不会丢失
  * beat 身份或改变 payload。该模块没有浮点运算，可直接复用于 standalone L2 测试入口。
  */
private[cuperflow] final class SpmvCuperflowProductBeatJoin(
  config: SpmvCuperflowConfig,
  pc: Int
) extends Module {
  require(pc >= 0 && pc < config.hbmPcCount,
    s"Cuperflow ProductBeat PC 编号越界：$pc/${config.hbmPcCount}")

  private val laneCount = SpmvCuperDecode.lanesPerBeat
  private val fifoDepth = config.fp64MultiplyLatency + config.fp64MultiplyResponseFifoDepth + 2

  val io = IO(new Bundle {
    /** 与 FMUL A 输入同拍 fire 的 context；只有有效 lane 才期待 response。 */
    val accept = Flipped(Decoupled(new SpmvCuperflowProductBeat(config)))
    val product = Flipped(Vec(laneCount, Decoupled(new SpmvProduct(config.mulConfig))))
    val out = Decoupled(new SpmvCuperflowProductBeat(config))
    /** 每次新 job 开始时复位序号和协议错误；FIFO 必须已经 drain。 */
    val clear = Input(Bool())
    /** 所有已接受 context 都已向下游握手时为真。 */
    val idle = Output(Bool())
    val error = Output(Bool())
  })

  private val contexts = Module(new Queue(new SpmvCuperflowProductBeat(config), fifoDepth))
  private val responses = Seq.fill(laneCount)(
    Module(new Queue(new SpmvProduct(config.mulConfig), fifoDepth))
  )
  private val error = RegInit(false.B)
  private val pending = RegInit(0.U(log2Ceil(fifoDepth + 1).W))

  contexts.io.enq <> io.accept
  for (lane <- 0 until laneCount) {
    responses(lane).io.enq <> io.product(lane)
  }

  private val context = contexts.io.deq.bits
  private val responseReady = VecInit((0 until laneCount).map { lane =>
    !context.laneValid(lane) || responses(lane).io.deq.valid
  }).asUInt.andR
  io.out.valid := contexts.io.deq.valid && responseReady
  contexts.io.deq.ready := io.out.ready && responseReady

  io.out.bits := 0.U.asTypeOf(new SpmvCuperflowProductBeat(config))
  io.out.bits.pc := context.pc
  io.out.bits.wave := context.wave
  io.out.bits.batch := context.batch
  io.out.bits.beatSeq := context.beatSeq
  io.out.bits.laneValid := context.laneValid
  io.out.bits.localRow := context.localRow
  io.out.bits.rowLast := context.rowLast
  io.out.bits.chunkMode := context.chunkMode

  private val responseMatches = (0 until laneCount).map { lane =>
    val response = responses(lane).io.deq.bits
    val consume = contexts.io.deq.valid && responseReady && io.out.ready && context.laneValid(lane)
    responses(lane).io.deq.ready := consume
    io.out.bits.product(lane) := Mux(context.laneValid(lane), response.product, 0.U)
    !context.laneValid(lane) || (
      response.batch === context.batch &&
        response.row(SpmvCuperDecode.rowBits - 1, 0) === context.localRow(lane) &&
        response.rowLast === context.rowLast(lane) &&
        response.chunkMode === context.chunkMode &&
        response.lane === lane.U
    )
  }

  when(io.clear) {
    error := false.B
  }.elsewhen(io.out.fire && !responseMatches.reduce(_ && _)) {
    error := true.B
  }
  when(io.clear) {
    pending := 0.U
  }.otherwise {
    when(io.accept.fire && !io.out.fire) {
      pending := pending + 1.U
    }.elsewhen(!io.accept.fire && io.out.fire) {
      pending := pending - 1.U
    }
  }
  io.idle := pending === 0.U
  io.error := error
}
