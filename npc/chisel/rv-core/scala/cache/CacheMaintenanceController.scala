package npc

import chisel3._
import chisel3.util._

/**
  * 在顺序后端上串行协调 FENCE、FENCE.I 和 FPGA 完成 drain 操作。
  *
  * 所有维护请求都由 `state` 解码为电平信号。目标 cache 需要多少个周期，
  * 请求就保持多少个周期；只有观察到对应的 `Done` 输入为高时，控制器才进入下一阶段。
  */
class CacheMaintenanceController(
  hasInstructionCache: Boolean,
  hasDataCache: Boolean,
  hasUnifiedL2: Boolean = false
) extends Module {
  val io = IO(new Bundle {
    val fencePending = Input(Bool())
    val fenceInvalidatesInstruction = Input(Bool())
    val fenceAccepted = Input(Bool())
    val backendBusy = Input(Bool())
    val externalDrainRequest = Input(Bool())

    val dcacheFlush = Output(Bool())
    val dcacheFlushDone = Input(Bool())
    val l2Flush = Output(Bool())
    val l2FlushDone = Input(Bool())
    val icacheInvalidate = Output(Bool())
    val icacheInvalidateDone = Input(Bool())
    val dispatchPermit = Output(Bool())
    val externalDrained = Output(Bool())
  })

  // 每个状态对应一个按时钟推进的阶段。D$ 始终先于 L2 drain，I$ 最后失效，
  // 从而保证 FENCE.I 完成后不会残留更年轻的预取指令。
  val Seq(sIdle, sWaitOlder, sFlushData, sFlushL2, sInvalidateInstruction,
    sReleaseFence, sExternalDone) = Enum(7)
  val state = RegInit(sIdle)
  // 旧指令 drain 期间，输入条件可能提前撤销，因此在接收请求的时钟边沿
  // 锁存操作类型以及是否需要执行 FENCE.I 的要求。
  val externalOperation = RegInit(false.B)
  val fenceInvalidatesInstruction = RegInit(false.B)

  // cache 维护请求在对应状态的整个持续期间保持有效，避免把多周期的
  // writeback/invalidate 操作错误地变成单周期脉冲。
  io.dcacheFlush := state === sFlushData
  io.l2Flush := state === sFlushL2
  io.icacheInvalidate := state === sInvalidateInstruction
  // sReleaseFence 阶段重新允许 fence 进入后端；等待该指令真正完成 dispatch
  // 握手后，维护事务才回到空闲状态。
  io.dispatchPermit := state === sIdle && !io.fencePending && !io.externalDrainRequest ||
    state === sReleaseFence
  io.externalDrained := state === sExternalDone

  def releaseOrInvalidateInstruction(): Unit = {
    if (hasInstructionCache) {
      // 普通 FENCE 保留 I$ 和已经预取的指令。只有 FENCE.I 在数据可见性
      // 确认安全后，才额外插入 I$ 失效阶段。
      when(!externalOperation && fenceInvalidatesInstruction) {
        state := sInvalidateInstruction
      }.otherwise {
        state := Mux(externalOperation, sExternalDone, sReleaseFence)
      }
    } else {
      state := Mux(externalOperation, sExternalDone, sReleaseFence)
    }
  }

  def afterOlderDrained(): Unit = {
    // backendBusy 覆盖维护操作开始前仍在执行的请求。必须等它清零后再维护 cache，
    // 否则旧访存可能与 fence 或完成边界发生错误的重排序。
    if (hasDataCache) state := sFlushData
    else flushL2OrRelease()
  }

  def flushL2OrRelease(): Unit = {
    // 单核 FENCE/FENCE.I 在 D$ 已经写入共享 L2 后即可建立可见顺序：后续取指和
    // 数据访问都会经过同一 L2。只有 host 观察完成或复位边界时，才需要继续写回
    // 外部主存；把本地屏障也扩展到 HBM 会平白增加一次完整写延迟。
    if (hasUnifiedL2) {
      when(externalOperation) { state := sFlushL2 }
        .otherwise { releaseOrInvalidateInstruction() }
    } else {
      releaseOrInvalidateInstruction()
    }
  }

  switch(state) {
    is(sIdle) {
      // 外部完成 drain 优先于同周期可见的 fence。两类请求都先经过同一个
      // “等待旧指令完成”屏障，但只有 fence 最终会重新放行到后端。
      when(io.externalDrainRequest) {
        externalOperation := true.B
        fenceInvalidatesInstruction := false.B
        state := sWaitOlder
      }.elsewhen(io.fencePending) {
        externalOperation := false.B
        fenceInvalidatesInstruction := io.fenceInvalidatesInstruction
        state := sWaitOlder
      }
    }
    is(sWaitOlder) {
      when(!externalOperation && !io.fencePending) {
        // 等待旧指令期间，如果旧指令发生 redirect，推测得到的 fence 可能已被冲刷，
        // 此时放弃本次维护并回到空闲状态。
        state := sIdle
      }.elsewhen(!io.backendBusy) {
        // 本周期完成状态转移后，由底层 cache 控制器逐行执行实际的
        // writeback 或失效操作。
        afterOlderDrained()
      }
    }
    is(sFlushData) {
      // D$ 需要多个周期遍历所有 set/way 并写回 dirty line，
      // 因此一直保持 dcacheFlush，直到遍历到达 Done。
      when(io.dcacheFlushDone) {
        flushL2OrRelease()
      }
    }
    is(sFlushL2) {
      // 只有外部完成/复位 drain 才从共享 L2 写回主存，且始终在 D$ 完成之后。
      when(io.l2FlushDone) {
        releaseOrInvalidateInstruction()
      }
    }
    is(sInvalidateInstruction) {
      // I$ 失效同样是多周期操作。只有失效控制器完成整个 cache array 遍历后，
      // 才能释放 fence。
      when(io.icacheInvalidateDone) {
        state := Mux(externalOperation, sExternalDone, sReleaseFence)
      }
    }
    is(sReleaseFence) {
      // 此状态下 dispatchPermit 为高；必须等待 fence 实际完成 dispatch 握手，
      // 才能结束本次维护事务。
      when(io.fenceAccepted) { state := sIdle }
    }
    is(sExternalDone) {
      // 这是电平式完成应答。保持完成状态，直到 mailbox 撤销 drain 请求，
      // 然后才接受下一次维护操作。
      when(!io.externalDrainRequest) { state := sIdle }
    }
  }
}
