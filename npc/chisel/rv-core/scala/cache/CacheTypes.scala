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

/** I$ 快速失效使用的有效代际；耗尽时回退到一次物理扫描以避免旧 line 重现。 */
private[npc] object CacheValidityEpoch {
  val width = 4
  val maximum = (1 << width) - 1
}

/** Board runtime requests only a D$ drain; FENCE.I is generated inside the core. */
class NpcCacheMaintenancePort extends Bundle {
  val drainRequest = Input(Bool())
  val drained = Output(Bool())
}

private[npc] object CacheAddress {
  /**
    * `indexBitOffset` 个最低原始 index 位已由上游 bank 选择固定时，bank 内 set 从其后的
    * 连续位开始取。tag 同步跳过这些位，保留原 cache 的物理 tag/set 映射。
    */
  def set(addr: UInt, geometry: CacheGeometry, indexBitOffset: Int = 0): UInt =
    if (geometry.indexBits == 0) 0.U(1.W)
    else addr(geometry.offsetBits + indexBitOffset + geometry.indexBits - 1,
      geometry.offsetBits + indexBitOffset)

  def tag(addr: UInt, geometry: CacheGeometry, addrWidth: Int, indexBitOffset: Int = 0): UInt =
    addr(addrWidth - 1, geometry.offsetBits + indexBitOffset + geometry.indexBits)

  /** 从 bank 内 tag/set 与固定的低 index bank 值恢复完整 cache line 基址。 */
  def lineBaseFromTagAndSet(
    tag: UInt,
    set: UInt,
    geometry: CacheGeometry,
    addrWidth: Int,
    indexBitOffset: Int = 0,
    indexLowValue: Int = 0
  ): UInt = {
    require(indexBitOffset >= 0,
      s"cache index bit offset must be non-negative, got $indexBitOffset")
    require(geometry.offsetBits + indexBitOffset + geometry.indexBits < addrWidth,
      s"cache tag must retain at least one address bit (offset=${geometry.offsetBits}, " +
        s"index=${geometry.indexBits}, skipped=$indexBitOffset, width=$addrWidth)")
    require(indexLowValue >= 0 && indexLowValue < (1 << indexBitOffset),
      s"cache index low value $indexLowValue does not fit $indexBitOffset bits")
    val lowIndex = if (indexBitOffset == 0) Seq.empty else Seq(indexLowValue.U(indexBitOffset.W))
    val setBits = if (geometry.indexBits == 0) Seq.empty else Seq(set)
    Cat(Seq(tag) ++ setBits ++ lowIndex :+ 0.U(geometry.offsetBits.W))
  }

  def lineBase(addr: UInt, geometry: CacheGeometry, addrWidth: Int): UInt =
    Cat(addr(addrWidth - 1, geometry.offsetBits), 0.U(geometry.offsetBits.W))

  def beat(addr: UInt, geometry: CacheGeometry, beatBytes: Int): UInt = {
    val beatBits = Integer.numberOfTrailingZeros(geometry.lineBytes / beatBytes)
    if (beatBits == 0) 0.U(1.W)
    else addr(geometry.offsetBits - 1, Integer.numberOfTrailingZeros(beatBytes))
  }
}
