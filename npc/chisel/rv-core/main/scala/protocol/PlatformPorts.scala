package npc.protocol

import chisel3._
import chisel3.util._
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
