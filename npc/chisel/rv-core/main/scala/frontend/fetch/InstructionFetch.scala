package scpu

import chisel3._
import chisel3.util._
import scpu.protocol.AxiLiteMasterIO

/** 带单项响应缓冲的 IF 阶段 AXI 读主机。 */
class IFetchAXIAdapter(addrWidth: Int = 32, dataWidth: Int = 64) extends Module {
  val io = IO(new Bundle {
    val pc = Input(UInt(addrWidth.W))
    val inst = Output(UInt(32.W))
    val responseValid = Output(Bool())
    val responseReady = Input(Bool())
    val busy = Output(Bool())
    val axi = new AxiLiteMasterIO(addrWidth, dataWidth)
  })

  val sIdle :: sArWait :: sRWait :: Nil = Enum(3)
  val state = RegInit(sIdle)
  val instReg = RegInit(0x00000013.U(32.W))
  val cachedPc = RegInit(~0.U(addrWidth.W))
  val responsePending = RegInit(false.B)
  val requestPc = RegInit(0.U(addrWidth.W))

  val needFetch = io.pc =/= cachedPc
  val responseData = WireDefault(instReg)
  when(state === sRWait && io.axi.r.valid) {
    val byteOffset = requestPc(log2Ceil(dataWidth / 8) - 1, 0)
    responseData := (io.axi.r.bits.data >> (byteOffset << 3))(31, 0)
  }
  val directResponseValid = state === sRWait && io.axi.r.valid && io.pc === requestPc
  io.inst := Mux(directResponseValid, responseData, instReg)
  io.responseValid := (responsePending && io.pc === cachedPc) || directResponseValid
  io.busy := needFetch || state =/= sIdle || responsePending

  io.axi.aw.valid := false.B
  io.axi.aw.bits.addr := 0.U
  io.axi.aw.bits.size := 0.U
  io.axi.aw.bits.prot := 0.U
  io.axi.w.valid := false.B
  io.axi.w.bits.data := 0.U
  io.axi.w.bits.strb := 0.U
  io.axi.b.ready := false.B
  io.axi.ar.valid := false.B
  io.axi.ar.bits.addr := io.pc
  io.axi.ar.bits.size := 2.U
  io.axi.ar.bits.prot := "b100".U
  io.axi.r.ready := false.B

  when(!reset.asBool) {
    when(responsePending && (io.responseReady || io.pc =/= cachedPc)) {
      responsePending := false.B
    }

    switch(state) {
      is(sIdle) {
        when(needFetch) {
          requestPc := io.pc
          io.axi.ar.valid := true.B
          io.axi.ar.bits.addr := io.pc
          when(io.axi.ar.fire) {
            state := sRWait
          }.otherwise {
            state := sArWait
          }
        }
      }
      is(sArWait) {
        io.axi.ar.valid := true.B
        io.axi.ar.bits.addr := requestPc
        when(io.axi.ar.fire) {
          state := sRWait
        }
      }
      is(sRWait) {
        io.axi.r.ready := !responsePending
        when(io.axi.r.fire) {
          when(io.pc === requestPc) {
            instReg := responseData
            cachedPc := requestPc
            responsePending := !io.responseReady
          }
          state := sIdle
        }
      }
    }
  }
}
