package npc.ip.memory

import chisel3._

/** 厂商无关的内存事务载荷；协议 wrapper 负责把它映射到 AXI/APB。 */
final class MemoryTransaction(addrWidth: Int, dataWidth: Int) extends Bundle {
  val addr = UInt(addrWidth.W)
  val write = Bool()
  val data = UInt(dataWidth.W)
  val size = UInt(4.W)
  val strb = UInt((dataWidth / 8).W)
}

final class MemoryResponse(dataWidth: Int) extends Bundle {
  val data = UInt(dataWidth.W)
  val ok = Bool()
}

/** 指令语义级的内存故障，不携带 AXI/APB 专有字段。 */
final class MemoryFault(addrWidth: Int) extends Bundle {
  val valid = Bool()
  val addr = UInt(addrWidth.W)
  val write = Bool()
  val len = UInt(4.W)
  val reason = UInt(3.W)
}

object MemoryFaultReason {
  val misaligned = 0.U(3.W)
  val crossBeat = 1.U(3.W)
  val readResponse = 2.U(3.W)
  val writeResponse = 3.U(3.W)
  val dpiOutOfRange = 4.U(3.W)
  val dpiInvalidRequest = 5.U(3.W)
}
