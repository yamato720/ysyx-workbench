package npc

import chisel3._
import chisel3.util.Cat

/** cache line 载荷。CPU 保持 XLEN 宽度，cache line-memory port 可以使用更宽的 beat。 */
class CacheLine(val beats: Int, val dataWidth: Int) extends Bundle {
  val data = Vec(beats, UInt(dataWidth.W))
}

/** Per-way tag metadata. I$ leaves dirty tied low; D$ owns it. */
class CacheTagMeta(val tagWidth: Int) extends Bundle {
  val valid = Bool()
  val dirty = Bool()
  val tag = UInt(tagWidth.W)
}

class CacheStatistics extends Bundle {
  val hits = UInt(64.W)
  val misses = UInt(64.W)
  val refills = UInt(64.W)
  val writebacks = UInt(64.W)
  val evictions = UInt(64.W)
}

/** Board runtime requests only a D$ drain; FENCE.I is generated inside the core. */
class NpcCacheMaintenancePort extends Bundle {
  val drainRequest = Input(Bool())
  val drained = Output(Bool())
}

private[npc] object CacheAddress {
  def set(addr: UInt, geometry: CacheGeometry): UInt =
    if (geometry.indexBits == 0) 0.U(1.W)
    else addr(geometry.offsetBits + geometry.indexBits - 1, geometry.offsetBits)

  def tag(addr: UInt, geometry: CacheGeometry, addrWidth: Int): UInt =
    addr(addrWidth - 1, geometry.offsetBits + geometry.indexBits)

  def lineBase(addr: UInt, geometry: CacheGeometry, addrWidth: Int): UInt =
    Cat(addr(addrWidth - 1, geometry.offsetBits), 0.U(geometry.offsetBits.W))

  def beat(addr: UInt, geometry: CacheGeometry, beatBytes: Int): UInt = {
    val beatBits = Integer.numberOfTrailingZeros(geometry.lineBytes / beatBytes)
    if (beatBits == 0) 0.U(1.W)
    else addr(geometry.offsetBits - 1, Integer.numberOfTrailingZeros(beatBytes))
  }
}
