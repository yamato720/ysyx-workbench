package scpu

import chisel3._
import scpu.protocol.{ArithmeticAssistPort, ArithmeticAssistRequest, ArithmeticAssistResponse}

/**
  * 一个按序标量算术回退端点。
  *
  * 每个端点只保留一个未完成事务；后端会在任一端点忙时停止派发，因此 mailbox 不会
  * 收到可重排的请求。domain 与 fallbackReason 是 ABI v3 的一部分，不依赖操作码重叠。
  */
class HostFallbackOperator(
  width: Int,
  tagWidth: Int,
  domain: ArithmeticRouteDomain,
  reason: OperatorFallbackReason
) extends Module {
  require(width == 32 || width == 64)
  require(reason != OperatorFallbackReason.None, "Host fallback requires an explicit reason")

  val io = IO(new Bundle {
    val arithmetic = new ArithmeticOperatorIO(width, tagWidth)
    val assist = new ArithmeticAssistPort(width)
  })

  val pending = RegInit(false.B)
  val requestSent = RegInit(false.B)
  val nextSequence = RegInit(1.U(32.W))
  val sequence = Reg(UInt(32.W))
  val request = Reg(new ArithmeticRequest(width, tagWidth))

  io.arithmetic.req.ready := !pending
  when(io.arithmetic.req.fire) {
    request := io.arithmetic.req.bits
    sequence := nextSequence
    nextSequence := nextSequence + 1.U
    pending := true.B
    requestSent := false.B
  }

  io.assist.request.valid := pending && !requestSent
  io.assist.request.bits.sequence := sequence
  io.assist.request.bits.pc := request.pc
  io.assist.request.bits.instruction := request.instruction
  io.assist.request.bits.operandA := request.operandA
  io.assist.request.bits.operandB := request.operandB
  io.assist.request.bits.operandC := request.operandC
  io.assist.request.bits.fcsr := request.fcsr
  io.assist.request.bits.operation := request.operation
  io.assist.request.bits.roundingMode := request.roundingMode
  io.assist.request.bits.domain := domain.id.U
  io.assist.request.bits.fallbackReason := reason.id.U
  when(io.assist.request.fire) { requestSent := true.B }

  val matchingResponse = io.assist.response.bits.sequence === sequence &&
    io.assist.response.bits.domain === domain.id.U
  io.arithmetic.resp.valid := pending && requestSent && io.assist.response.valid && matchingResponse
  io.arithmetic.resp.bits.result := io.assist.response.bits.result
  io.arithmetic.resp.bits.exceptionFlags := io.assist.response.bits.exceptionFlags
  io.arithmetic.resp.bits.illegal := io.assist.response.bits.illegal
  io.arithmetic.resp.bits.tag := request.tag

  // 过期或错误域的响应必须被消费，但绝不能完成当前架构槽位。
  io.assist.response.ready := pending && requestSent && (!matchingResponse || io.arithmetic.resp.ready)
  when(io.arithmetic.resp.fire) {
    pending := false.B
    requestSent := false.B
  }
  io.assist.busy := pending
}
