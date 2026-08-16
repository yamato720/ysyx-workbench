package npc

import chisel3._
import chisel3.util._

private class UramSyncMemory(depth: Int, width: Int) extends BlackBox(Map(
  "DEPTH" -> depth,
  "WIDTH" -> width
)) with HasBlackBoxInline {
  private val addressWidth = math.max(1, log2Ceil(depth))
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val readEnable = Input(Bool())
    val readAddress = Input(UInt(addressWidth.W))
    val readData = Output(UInt(width.W))
    val writeEnable = Input(Bool())
    val writeAddress = Input(UInt(addressWidth.W))
    val writeData = Input(UInt(width.W))
  })

  setInline("NpcCacheUram.sv",
    """module UramSyncMemory #(
      |  parameter integer DEPTH = 64,
      |  parameter integer WIDTH = 128,
      |  parameter integer ADDRESS_WIDTH = (DEPTH <= 1) ? 1 : $clog2(DEPTH)
      |) (
      |  input  wire                     clock,
      |  input  wire                     readEnable,
      |  input  wire [ADDRESS_WIDTH-1:0] readAddress,
      |  output reg  [WIDTH-1:0]         readData,
      |  input  wire                     writeEnable,
      |  input  wire [ADDRESS_WIDTH-1:0] writeAddress,
      |  input  wire [WIDTH-1:0]         writeData
      |);
      |  (* ram_style = "ultra" *) reg [WIDTH-1:0] memory [0:DEPTH-1];
      |  always @(posedge clock) begin
      |    if (readEnable) readData <= memory[readAddress];
      |    if (writeEnable) memory[writeAddress] <= writeData;
      |  end
      |endmodule
      |""".stripMargin)
}

/** valid/dirty 显式复位；Auto 存储中的 tag 与 line 载荷推导为同步 SRAM。 */
class CacheArray(cache: CacheConfig, addrWidth: Int, dataWidth: Int, hasDirty: Boolean,
                 useValidityEpoch: Boolean = false, indexBitOffset: Int = 0) extends Module {
  private val geometry = cache.geometry
  private val sets = geometry.sets
  private val ways = geometry.ways
  private val beats = geometry.lineBytes / (dataWidth / 8)
  private val lineWidth = beats * dataWidth
  private val setWidth = math.max(1, log2Ceil(sets))
  private val wayWidth = math.max(1, log2Ceil(ways))
  require(indexBitOffset >= 0 && geometry.offsetBits + indexBitOffset + geometry.indexBits < addrWidth,
    s"cache index bit offset $indexBitOffset is invalid for address width $addrWidth")
  private val tagWidth = geometry.tagBits(addrWidth) - indexBitOffset

  val io = IO(new Bundle {
    val readEnable = Input(Bool())
    val readSet = Input(UInt(setWidth.W))
    val readLines = Output(Vec(ways, UInt(lineWidth.W)))
    val readMeta = Output(Vec(ways, new CacheTagMeta(tagWidth)))

    val dataWriteEnable = Input(Bool())
    val dataWriteSet = Input(UInt(setWidth.W))
    val dataWriteWay = Input(UInt(wayWidth.W))
    val dataWriteLine = Input(UInt(lineWidth.W))

    val metaWriteEnable = Input(Bool())
    val metaWriteSet = Input(UInt(setWidth.W))
    val metaWriteWay = Input(UInt(wayWidth.W))
    val metaWrite = Input(new CacheTagMeta(tagWidth))
    val validEpoch = Input(UInt(CacheValidityEpoch.width.W))
  })

  val validBits = RegInit(VecInit(Seq.fill(sets)(VecInit(Seq.fill(ways)(false.B)))))
  // 只有 I$ 分配代际存储。D$/L2 不承受 FENCE.I 的失效语义，避免引入无用状态位。
  val validEpochBits = if (useValidityEpoch) Some(RegInit(VecInit(Seq.fill(sets)(VecInit(
    Seq.fill(ways)(0.U(CacheValidityEpoch.width.W))))))) else None
  val dirtyBits = if (hasDirty) {
    Some(RegInit(VecInit(Seq.fill(sets)(VecInit(Seq.fill(ways)(false.B))))))
  } else None
  // 同步 tag/data RAM 的输出属于上一拍的读地址。valid/dirty 也使用同一锁存地址，
  // 这样连续 S0 请求不会把下一笔的元数据与上一笔的 tag/data 拼在一起。
  val readSetReg = RegInit(0.U(setWidth.W))
  when(io.readEnable) { readSetReg := io.readSet }
  val readValid = if (sets == 1) validBits(0) else validBits(readSetReg)
  val readValidEpoch = validEpochBits.map { bits =>
    if (sets == 1) bits(0) else bits(readSetReg)
  }
  val readDirty = dirtyBits.map(bits => if (sets == 1) bits(0) else bits(readSetReg))
    .getOrElse(VecInit(Seq.fill(ways)(false.B)))
  val readTags = Wire(Vec(ways, UInt(tagWidth.W)))

  if (cache.storage == CacheStorage.Registers) {
    val tags = Reg(Vec(sets, Vec(ways, UInt(tagWidth.W))))
    readTags := (if (sets == 1) tags(0) else tags(readSetReg))
    when(io.metaWriteEnable) {
      for (set <- 0 until sets; way <- 0 until ways) {
        when((if (sets == 1) true.B else io.metaWriteSet === set.U) &&
          (if (ways == 1) true.B else io.metaWriteWay === way.U)) {
          tags(set)(way) := io.metaWrite.tag
        }
      }
    }
  } else {
    val tagMemories = Seq.tabulate(ways) { way =>
      val memory = SyncReadMem(sets, UInt(tagWidth.W))
      memory.suggestName(s"cache_way_${way}_tag")
      memory
    }
    val tagReadSet = if (sets == 1) 0.U else io.readSet
    readTags := VecInit(tagMemories.map(_.read(tagReadSet, io.readEnable)))
    for (way <- 0 until ways) {
      when(io.metaWriteEnable && (if (ways == 1) true.B else io.metaWriteWay === way.U)) {
        tagMemories(way).write(io.metaWriteSet, io.metaWrite.tag)
      }
    }
  }

  for (way <- 0 until ways) {
    io.readMeta(way).valid := (if (useValidityEpoch) {
      readValid(way) && readValidEpoch.get(way) === io.validEpoch
    } else readValid(way))
    io.readMeta(way).dirty := readDirty(way)
    io.readMeta(way).tag := readTags(way)
  }

  if (cache.storage == CacheStorage.Registers) {
    val lines = RegInit(VecInit(Seq.fill(sets)(VecInit(Seq.fill(ways)(0.U(lineWidth.W))))))
    io.readLines := (if (sets == 1) lines(0) else lines(readSetReg))
    when(io.dataWriteEnable) {
      for (set <- 0 until sets; way <- 0 until ways) {
        when((if (sets == 1) true.B else io.dataWriteSet === set.U) &&
          (if (ways == 1) true.B else io.dataWriteWay === way.U)) {
          lines(set)(way) := io.dataWriteLine
        }
      }
    }
  } else if (cache.storage == CacheStorage.Uram) {
    val memories = Seq.tabulate(ways) { way =>
      val memory = Module(new UramSyncMemory(sets, lineWidth))
      memory.suggestName(s"cache_way_${way}_uram")
      memory.io.clock := clock
      memory.io.readEnable := io.readEnable
      memory.io.readAddress := (if (sets == 1) 0.U else io.readSet)
      memory.io.writeEnable := io.dataWriteEnable &&
        (if (ways == 1) true.B else io.dataWriteWay === way.U)
      memory.io.writeAddress := (if (sets == 1) 0.U else io.dataWriteSet)
      memory.io.writeData := io.dataWriteLine
      memory
    }
    io.readLines := VecInit(memories.map(_.io.readData))
  } else {
    val memories = Seq.tabulate(ways) { way =>
      val memory = SyncReadMem(sets, UInt(lineWidth.W))
      memory.suggestName(s"cache_way_${way}_data")
      memory
    }
    val memoryReadSet = if (sets == 1) 0.U else io.readSet
    io.readLines := VecInit(memories.map(_.read(memoryReadSet, io.readEnable)))
    for (way <- 0 until ways) {
      when(io.dataWriteEnable && io.dataWriteWay === way.U) {
        memories(way).write(io.dataWriteSet, io.dataWriteLine)
      }
    }
  }

  when(io.metaWriteEnable) {
    for (set <- 0 until sets; way <- 0 until ways) {
      when((if (sets == 1) true.B else io.metaWriteSet === set.U) &&
        (if (ways == 1) true.B else io.metaWriteWay === way.U)) {
        validBits(set)(way) := io.metaWrite.valid
        validEpochBits.foreach { bits =>
          when(io.metaWrite.valid) { bits(set)(way) := io.validEpoch }
        }
        dirtyBits.foreach(bits => bits(set)(way) := io.metaWrite.dirty)
      }
    }
  }
}
