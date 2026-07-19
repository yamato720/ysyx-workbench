package scpu

import chisel3._
import chisel3.util._
import scpu.protocol.AxiLiteMasterIO

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

/** MEM 阶段 AXI 主机；每次只允许一笔按序加载或存储。 */
class LSUAXIAdapter(addrWidth: Int = 32, dataWidth: Int = 64) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val addr = Input(UInt(addrWidth.W))
    val wdata = Input(UInt(dataWidth.W))
    val accessType = Input(UInt(3.W))
    val memRead = Input(Bool())
    val memWrite = Input(Bool())
    val rdata = Output(UInt(dataWidth.W))
    val busy = Output(Bool())
    val axi = new AxiLiteMasterIO(addrWidth, dataWidth)
  })

  val sIdle :: sReadAr :: sReadR :: sWrite :: sWriteB :: Nil = Enum(5)
  val state = RegInit(sIdle)
  val addrReg = RegInit(0.U(addrWidth.W))
  val wdataReg = RegInit(0.U(dataWidth.W))
  val accessReg = RegInit(0.U(3.W))
  val rdataReg = RegInit(0.U(dataWidth.W))
  val awDone = RegInit(false.B)
  val wDone = RegInit(false.B)

  val strbWidth = dataWidth / 8
  val byteOffset = addrReg(log2Ceil(strbWidth) - 1, 0)
  val wstrb = AxiLiteWstrb.genStrb(accessReg, byteOffset, dataWidth)
  val alignedData = AxiLiteWstrb.alignData(wdataReg, byteOffset, dataWidth)
  val accessSize = MuxLookup(accessReg(1, 0), 2.U(3.W))(Seq(
    "b00".U -> 0.U, "b01".U -> 1.U, "b10".U -> 2.U, "b11".U -> 3.U
  ))

  io.axi.aw.valid := false.B
  io.axi.aw.bits.addr := addrReg
  io.axi.aw.bits.size := accessSize
  io.axi.aw.bits.prot := Cat(0.U(1.W), accessReg(1, 0))
  io.axi.w.valid := false.B
  io.axi.w.bits.data := alignedData
  io.axi.w.bits.strb := wstrb
  io.axi.b.ready := false.B
  io.axi.ar.valid := false.B
  io.axi.ar.bits.addr := addrReg
  io.axi.ar.bits.size := accessSize
  io.axi.ar.bits.prot := Cat(0.U(1.W), accessReg(1, 0))
  io.axi.r.ready := false.B
  io.rdata := rdataReg
  io.busy := state =/= sIdle

  switch(state) {
    is(sIdle) {
      when(io.start) {
        addrReg := io.addr
        wdataReg := io.wdata
        accessReg := io.accessType
        when(io.memRead) {
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
        rdataReg := AxiLiteLoadUnpack.unpack(io.axi.r.bits.data, byteOffset, accessReg, dataWidth)
        state := sIdle
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
      when(io.axi.b.fire) { state := sIdle }
    }
  }
}
