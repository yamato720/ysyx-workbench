package scpu

import chisel3._
import chisel3.util._
import scpu.protocol.{AxiLiteMasterIO, MemoryFault, MemoryFaultReason}

/** MEM 阶段的存储字节通道格式化。 */
object AxiLiteWstrb {
  def genStrb(accessType: UInt, byteOffset: UInt, dataWidth: Int = 64): UInt = {
    val strbWidth = dataWidth / 8
    val doublewordBytes = math.min(8, strbWidth)
    val baseMask = MuxLookup(accessType(1, 0), 1.U(strbWidth.W))(Seq(
      "b00".U -> 1.U(strbWidth.W),
      "b01".U -> 3.U(strbWidth.W),
      "b10".U -> 15.U(strbWidth.W),
      "b11".U -> ((BigInt(1) << doublewordBytes) - 1).U(strbWidth.W)
    ))
    (baseMask << byteOffset)(strbWidth - 1, 0)
  }

  def alignData(wdata: UInt, byteOffset: UInt, dataWidth: Int = 64): UInt =
    (wdata << (byteOffset << 3))(dataWidth - 1, 0)
}

/** MEM 阶段的加载字节提取及有符号/无符号扩展。 */
object AxiLiteLoadUnpack {
  private def extend(value: UInt, valueWidth: Int, dataWidth: Int, signed: Boolean): UInt = {
    if (dataWidth <= valueWidth) value(dataWidth - 1, 0)
    else if (signed) Cat(Fill(dataWidth - valueWidth, value(valueWidth - 1)), value)
    else Cat(0.U((dataWidth - valueWidth).W), value)
  }

  def unpack(busData: UInt, byteOffset: UInt, accessType: UInt, dataWidth: Int = 64): UInt = {
    require(dataWidth >= 32 && (dataWidth & (dataWidth - 1)) == 0,
      s"AXI-Lite dataWidth must be a power of two and at least 32, got $dataWidth")

    val shifted = (busData >> (byteOffset << 3))(dataWidth - 1, 0)
    val lb = extend(shifted(7, 0), 8, dataWidth, signed = true)
    val lh = extend(shifted(15, 0), 16, dataWidth, signed = true)
    val lw = extend(shifted(31, 0), 32, dataWidth, signed = true)
    val lbu = extend(shifted(7, 0), 8, dataWidth, signed = false)
    val lhu = extend(shifted(15, 0), 16, dataWidth, signed = false)
    val lwu = extend(shifted(31, 0), 32, dataWidth, signed = false)
    val ld = if (dataWidth >= 64) shifted(63, 0) else shifted

    MuxLookup(accessType, shifted)(Seq(
      "b000".U -> lb,
      "b001".U -> lh,
      "b010".U -> lw,
      "b011".U -> ld,
      "b100".U -> lbu,
      "b101".U -> lhu,
      "b110".U -> lwu
    ))
  }
}

/** MEM 阶段 AXI 主机；每次只允许一笔按序加载或存储。
  *
  * 主存事务始终是一个 XLEN 宽的对齐 beat；MMIO 则保留指令的原始地址和实际长度。
  */
class LSUAXIAdapter(
  addrWidth: Int = 32,
  dataWidth: Int = 64,
  mainMemoryBase: Long = 0x80000000L,
  mainMemorySize: Long = 0x08000000L
) extends Module {
  require(dataWidth == 32 || dataWidth == 64, s"LSU only supports RV32/RV64 bus widths, got $dataWidth")

  val io = IO(new Bundle {
    val start = Input(Bool())
    val addr = Input(UInt(addrWidth.W))
    val wdata = Input(UInt(dataWidth.W))
    val accessType = Input(UInt(3.W))
    val memRead = Input(Bool())
    val memWrite = Input(Bool())
    val rdata = Output(UInt(dataWidth.W))
    val busy = Output(Bool())
    val fault = Output(new MemoryFault(addrWidth))
    val axi = new AxiLiteMasterIO(addrWidth, dataWidth)
  })

  val sIdle :: sReadAr :: sReadR :: sWrite :: sWriteB :: sFault :: Nil = Enum(6)
  val state = RegInit(sIdle)
  val addrReg = RegInit(0.U(addrWidth.W))
  val wdataReg = RegInit(0.U(dataWidth.W))
  val accessReg = RegInit(0.U(3.W))
  val mainMemoryReg = RegInit(false.B)
  val rdataReg = RegInit(0.U(dataWidth.W))
  val awDone = RegInit(false.B)
  val wDone = RegInit(false.B)
  val faultAddrReg = RegInit(0.U(addrWidth.W))
  val faultWriteReg = RegInit(false.B)
  val faultLenReg = RegInit(0.U(4.W))
  val faultReasonReg = RegInit(0.U(3.W))

  val strbWidth = dataWidth / 8
  val beatOffsetBits = log2Ceil(strbWidth)
  val byteOffset = addrReg(log2Ceil(strbWidth) - 1, 0)
  val wstrb = AxiLiteWstrb.genStrb(accessReg, byteOffset, dataWidth)
  val alignedData = AxiLiteWstrb.alignData(wdataReg, byteOffset, dataWidth)
  val narrowAccessSize = MuxLookup(accessReg(1, 0), 2.U(3.W))(Seq(
    "b00".U -> 0.U, "b01".U -> 1.U, "b10".U -> 2.U, "b11".U -> 3.U
  ))
  val mainMemoryAccessSize = log2Ceil(strbWidth).U(3.W)
  val beatAddr = Cat(addrReg(addrWidth - 1, beatOffsetBits), 0.U(beatOffsetBits.W))
  val requestAddr = Mux(mainMemoryReg, beatAddr, addrReg)
  val requestSize = Mux(mainMemoryReg, mainMemoryAccessSize, narrowAccessSize)

  def accessBytes(accessType: UInt): UInt = MuxLookup(accessType(1, 0), 1.U(4.W))(Seq(
    "b00".U -> 1.U(4.W), "b01".U -> 2.U(4.W), "b10".U -> 4.U(4.W), "b11".U -> 8.U(4.W)
  ))

  def naturallyMisaligned(addr: UInt, accessType: UInt): Bool = MuxLookup(accessType(1, 0), false.B)(Seq(
    "b00".U -> false.B,
    "b01".U -> addr(0),
    "b10".U -> addr(1, 0).orR,
    "b11".U -> addr(2, 0).orR
  ))

  def crossesBeat(addr: UInt, accessType: UInt): Bool = {
    val offset = addr(beatOffsetBits - 1, 0)
    (offset +& accessBytes(accessType)) > strbWidth.U
  }

  def isMainMemory(addr: UInt): Bool =
    addr >= mainMemoryBase.U(addrWidth.W) && addr < (mainMemoryBase + mainMemorySize).U(addrWidth.W)

  def latchFault(addr: UInt, write: Bool, len: UInt, reason: UInt): Unit = {
    faultAddrReg := addr
    faultWriteReg := write
    faultLenReg := len
    faultReasonReg := reason
    state := sFault
  }

  io.axi.aw.valid := false.B
  io.axi.aw.bits.addr := requestAddr
  io.axi.aw.bits.size := requestSize
  io.axi.aw.bits.prot := Cat(0.U(1.W), accessReg(1, 0))
  io.axi.w.valid := false.B
  io.axi.w.bits.data := alignedData
  io.axi.w.bits.strb := wstrb
  io.axi.b.ready := false.B
  io.axi.ar.valid := false.B
  io.axi.ar.bits.addr := requestAddr
  io.axi.ar.bits.size := requestSize
  io.axi.ar.bits.prot := Cat(0.U(1.W), accessReg(1, 0))
  io.axi.r.ready := false.B
  io.rdata := rdataReg
  io.busy := state =/= sIdle
  io.fault.valid := state === sFault
  io.fault.addr := faultAddrReg
  io.fault.write := faultWriteReg
  io.fault.len := faultLenReg
  io.fault.reason := faultReasonReg

  switch(state) {
    is(sIdle) {
      when(io.start) {
        addrReg := io.addr
        wdataReg := io.wdata
        accessReg := io.accessType
        mainMemoryReg := isMainMemory(io.addr)
        val len = accessBytes(io.accessType)
        when(naturallyMisaligned(io.addr, io.accessType)) {
          latchFault(io.addr, io.memWrite, len, MemoryFaultReason.misaligned)
        }.elsewhen(crossesBeat(io.addr, io.accessType)) {
          latchFault(io.addr, io.memWrite, len, MemoryFaultReason.crossBeat)
        }.elsewhen(io.memRead) {
          state := sReadAr
        }.elsewhen(io.memWrite) {
          awDone := false.B
          wDone := false.B
          state := sWrite
        }
      }
    }
    is(sReadAr) {
      io.axi.ar.valid := true.B
      when(io.axi.ar.fire) { state := sReadR }
    }
    is(sReadR) {
      io.axi.r.ready := true.B
      when(io.axi.r.fire) {
        when(io.axi.r.bits.resp =/= 0.U) {
          latchFault(addrReg, false.B, accessBytes(accessReg), MemoryFaultReason.readResponse)
        }.otherwise {
          rdataReg := AxiLiteLoadUnpack.unpack(io.axi.r.bits.data, byteOffset, accessReg, dataWidth)
          state := sIdle
        }
      }
    }
    is(sWrite) {
      when(!awDone) {
        io.axi.aw.valid := true.B
        when(io.axi.aw.fire) { awDone := true.B }
      }
      when(!wDone) {
        io.axi.w.valid := true.B
        when(io.axi.w.fire) { wDone := true.B }
      }
      when((awDone || io.axi.aw.fire) && (wDone || io.axi.w.fire)) {
        state := sWriteB
      }
    }
    is(sWriteB) {
      io.axi.b.ready := true.B
      when(io.axi.b.fire) {
        when(io.axi.b.bits.resp =/= 0.U) {
          latchFault(addrReg, true.B, accessBytes(accessReg), MemoryFaultReason.writeResponse)
        }.otherwise {
          state := sIdle
        }
      }
    }

    is(sFault) {}
  }
}
