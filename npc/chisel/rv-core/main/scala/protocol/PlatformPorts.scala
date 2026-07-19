package scpu.protocol

import chisel3._
import chisel3.util._
import scpu.ArithmeticOperation

/** 平台为无法在核内完成的标量算术提供的请求。该协议不绑定 FPGA、DPI 或总线实现。 */
class ArithmeticAssistRequest(width: Int) extends Bundle {
  val sequence = UInt(32.W)
  val pc = UInt(width.W)
  val instruction = UInt(32.W)
  val operandA = UInt(width.W)
  val operandB = UInt(width.W)
  val operandC = UInt(width.W)
  val fcsr = UInt(8.W)
  val operation = UInt(ArithmeticOperation.width.W)
  val roundingMode = UInt(3.W)
}

/** 平台回传的算术结果；sequence 防止过期响应错误完成当前架构槽位。 */
class ArithmeticAssistResponse(width: Int) extends Bundle {
  val sequence = UInt(32.W)
  val result = UInt(width.W)
  val exceptionFlags = UInt(5.W)
  val illegal = Bool()
}

/** 面向宿主服务、协处理器或板级邮箱的中立算术辅助端口。 */
class ArithmeticAssistPort(width: Int) extends Bundle {
  val request = Decoupled(new ArithmeticAssistRequest(width))
  val response = Flipped(Decoupled(new ArithmeticAssistResponse(width)))
  val busy = Output(Bool())
}

/** 平台调试器使用的派发门控。核心只认识许可和实际派发，不依赖具体调试协议。 */
class NpcDispatchControlPort extends Bundle {
  val dispatchPermit = Input(Bool())
  val dispatchFire = Output(Bool())
}

/** 为自定义指令协处理器预留的 RoCC-like 命令。它不依赖 Rocket Chip 的类型。 */
class NpcAcceleratorCommand(width: Int) extends Bundle {
  val instruction = UInt(32.W)
  val pc = UInt(width.W)
  val rs1 = UInt(width.W)
  val rs2 = UInt(width.W)
  val rd = UInt(5.W)
  val funct7 = UInt(7.W)
}

/** RoCC-like 协处理器的按序响应。异常语义由后续 custom 指令执行单元定义。 */
class NpcAcceleratorResponse(width: Int) extends Bundle {
  val rd = UInt(5.W)
  val data = UInt(width.W)
  val illegal = Bool()
}

/** 中立协处理器端口定义，供后续 custom-0..3 执行单元及板级/仿真适配器共用。 */
class NpcAcceleratorPort(width: Int) extends Bundle {
  val command = Decoupled(new NpcAcceleratorCommand(width))
  val response = Flipped(Decoupled(new NpcAcceleratorResponse(width)))
  val busy = Input(Bool())
  val interrupt = Input(Bool())
}
