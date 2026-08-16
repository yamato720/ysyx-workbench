package npc

import chisel3._
import chisel3.util._

/**
  * 小型条件分支方向预测器。
  *
  * 首次遇到某条件分支 PC 时沿用 BTFNT（Backward Taken, Forward Not Taken：后向目标
  * 预测跳转，前向目标预测不跳转）；解析后的结果写入两位饱和计数器。JALR
  * 则只在同 PC 已解析过目标时预测，避免未经训练的间接跳转越过顺序流。预测查询与解析
  * 更新可在同一周期发生，查询使用时钟边界前的状态，新的方向和目标从下一次取指起生效。
  */
class BranchPredictor(addrWidth: Int, entries: Int, returnEntries: Int) extends Module {
  require(entries > 0 && (entries & (entries - 1)) == 0,
    s"branch predictor entries must be a positive power of two, got $entries")

  private val indexBits = log2Ceil(entries)
  require(addrWidth >= indexBits + 2,
    s"branch predictor address width $addrWidth cannot index $entries entries")
  require(returnEntries > 0 && (returnEntries & (returnEntries - 1)) == 0,
    s"return stack entries must be a positive power of two, got $returnEntries")

  val io = IO(new Bundle {
    val queryValid = Input(Bool())
    val queryPc = Input(UInt(addrWidth.W))
    val queryConditional = Input(Bool())
    val queryJalr = Input(Bool())
    val queryReturn = Input(Bool())
    val queryStaticTaken = Input(Bool())
    val predictTaken = Output(Bool())
    val predictJalrValid = Output(Bool())
    val predictJalrTarget = Output(UInt(addrWidth.W))
    val predictReturnValid = Output(Bool())
    val predictReturnTarget = Output(UInt(addrWidth.W))

    val resolveValid = Input(Bool())
    val resolvePc = Input(UInt(addrWidth.W))
    val resolveConditional = Input(Bool())
    val resolveJalr = Input(Bool())
    val resolveCall = Input(Bool())
    val resolveReturn = Input(Bool())
    val resolveTaken = Input(Bool())
    val resolveTarget = Input(UInt(addrWidth.W))
  })

  def index(pc: UInt): UInt = pc(indexBits + 1, 2)

  val entryValid = RegInit(VecInit(Seq.fill(entries)(false.B)))
  // `10` 是弱 taken：训练前仍由 queryStaticTaken 决定，首个解析结果再建立方向历史。
  val counters = RegInit(VecInit(Seq.fill(entries)("b10".U(2.W))))
  val queryIndex = index(io.queryPc)
  val resolveIndex = index(io.resolvePc)
  val learnedTaken = counters(queryIndex)(1)
  val targetValid = RegInit(VecInit(Seq.fill(entries)(false.B)))
  val targetTags = Reg(Vec(entries, UInt(addrWidth.W)))
  val targetValues = Reg(Vec(entries, UInt(addrWidth.W)))
  private val returnIndexBits = math.max(1, log2Ceil(returnEntries))
  private val returnCountWidth = log2Ceil(returnEntries + 1)
  val returnStack = Reg(Vec(returnEntries, UInt(addrWidth.W)))
  val returnTop = RegInit(0.U(returnIndexBits.W))
  val returnCount = RegInit(0.U(returnCountWidth.W))
  val returnReadIndex = returnTop - 1.U

  io.predictTaken := io.queryValid && io.queryConditional &&
    Mux(entryValid(queryIndex), learnedTaken, io.queryStaticTaken)
  // 已识别的 return 只使用 RAS。它的实际目标由调用深度决定，拿单一 BTB 历史回退
  // 会在多个调用点复用同一 ret 指令时稳定地误预测。
  io.predictJalrValid := io.queryValid && io.queryJalr && !io.queryReturn && targetValid(queryIndex) &&
    targetTags(queryIndex) === io.queryPc
  io.predictJalrTarget := targetValues(queryIndex)
  io.predictReturnValid := io.queryValid && io.queryReturn && returnCount =/= 0.U
  io.predictReturnTarget := returnStack(returnReadIndex)

  when(io.resolveValid && io.resolveConditional) {
    entryValid(resolveIndex) := true.B
    when(io.resolveTaken) {
      when(counters(resolveIndex) =/= "b11".U) {
        counters(resolveIndex) := counters(resolveIndex) + 1.U
      }
    }.otherwise {
      when(counters(resolveIndex) =/= 0.U) {
        counters(resolveIndex) := counters(resolveIndex) - 1.U
      }
    }
  }
  // 目标表只记录已在 EX 得到实际地址的 JALR。直接 JAL 和条件分支的目标由当前指令
  // 立即数计算，不需要占用表项。
  when(io.resolveValid && io.resolveJalr) {
    targetValid(resolveIndex) := true.B
    targetTags(resolveIndex) := io.resolvePc
    targetValues(resolveIndex) := io.resolveTarget
  }
  // 调用在 EX 解析后才压栈，因而错误路径的 JAL/JALR 从不会污染 RAS。返回在同一边界
  // 弹栈；其预测发生在更早的取指周期，读到的始终是当前已提交调用链的栈顶。
  when(io.resolveValid && io.resolveCall) {
    returnStack(returnTop) := io.resolvePc + 4.U
    returnTop := returnTop + 1.U
    when(returnCount =/= returnEntries.U) {
      returnCount := returnCount + 1.U
    }
  }.elsewhen(io.resolveValid && io.resolveReturn && returnCount =/= 0.U) {
    returnTop := returnTop - 1.U
    returnCount := returnCount - 1.U
  }
}
