package scpu.fpga

import chisel3._
import chisel3.util._
import scpu.protocol._

/** 供 PS Linux 与 XRT 宿主访问的 AXI-Lite 寄存器邮箱。 */
class FpgaFallbackMailbox(width: Int) extends Module {
  require(width == 32 || width == 64)

  val io = IO(new Bundle {
    val axi = Flipped(new AxiLiteMasterIO(32, 32))
    val core = Flipped(new ArithmeticAssistPort(width))
    val runtime = Input(new FpgaRuntimeDebug(width))
    val putch = Flipped(Decoupled(UInt(8.W)))
    val coreReset = Output(Bool())
    val dispatchPermit = Output(Bool())
    val memoryHostBase = Output(UInt(64.W))
    val interrupt = Output(Bool())
  })

  val requestPending = RegInit(false.B)
  val responsePending = RegInit(false.B)
  val fallbackProtocolError = RegInit(false.B)
  val request = RegInit(0.U.asTypeOf(new ArithmeticAssistRequest(width)))
  val timeoutCycles = RegInit(0.U(32.W))
  val responseSequence = RegInit(0.U(32.W))
  val responseLow = RegInit(0.U(32.W))
  val responseHigh = RegInit(0.U(32.W))
  val responseFlags = RegInit(0.U(5.W))
  val responseIllegal = RegInit(false.B)
  val coreReset = RegInit(true.B)
  val commandSequence = RegInit(0.U(32.W))
  val putchPending = RegInit(false.B)
  val putchData = RegInit(0.U(8.W))
  val gprIndex = RegInit(0.U(5.W))
  val fprIndex = RegInit(0.U(5.W))
  val csrIndex = RegInit(0.U(3.W))
  val memoryHostBaseLow = RegInit(0.U(32.W))
  val memoryHostBaseHigh = RegInit(0.U(32.W))

  val debugController = Module(new FpgaDebugController(width))
  val debugCommand = WireDefault(0.U.asTypeOf(Valid(new FpgaDebugCommand)))
  val resetControl = WireDefault(0.U.asTypeOf(Valid(new FpgaResetControl)))
  val clearProtocolError = WireDefault(false.B)
  debugController.io.runtime := io.runtime
  debugController.io.coreReset := coreReset
  debugController.io.command := debugCommand
  debugController.io.resetControl := resetControl
  debugController.io.clearError := clearProtocolError
  val debug = debugController.io.status
  val protocolError = fallbackProtocolError || debug.protocolError

  io.coreReset := coreReset
  io.dispatchPermit := debugController.io.dispatchPermit
  io.memoryHostBase := Cat(memoryHostBaseHigh, memoryHostBaseLow)
  io.putch.ready := !putchPending
  when(io.putch.fire) {
    putchPending := true.B
    putchData := io.putch.bits
  }

  io.core.request.ready := !requestPending
  when(io.core.request.fire) {
    request := io.core.request.bits
    requestPending := true.B
    timeoutCycles := 0.U
  }.elsewhen(requestPending && !timeoutCycles.andR) {
    timeoutCycles := timeoutCycles + 1.U
  }

  io.core.response.valid := responsePending
  io.core.response.bits.sequence := responseSequence
  io.core.response.bits.result :=
    (if (width == 64) Cat(responseHigh, responseLow) else responseLow)
  io.core.response.bits.exceptionFlags := responseFlags
  io.core.response.bits.illegal := responseIllegal
  when(io.core.response.fire) {
    responsePending := false.B
    requestPending := false.B
  }

  io.interrupt := (requestPending && !responsePending) || putchPending || debug.terminalHalted

  val awValid = RegInit(false.B)
  val awAddress = Reg(UInt(32.W))
  val wValid = RegInit(false.B)
  val wData = Reg(UInt(32.W))
  val wStrb = Reg(UInt(4.W))
  val bValid = RegInit(false.B)
  val rValid = RegInit(false.B)
  val rData = Reg(UInt(32.W))

  io.axi.aw.ready := !awValid
  io.axi.w.ready := !wValid
  io.axi.b.valid := bValid
  io.axi.b.bits.resp := AxiLiteResp.OKAY
  io.axi.ar.ready := !rValid
  io.axi.r.valid := rValid
  io.axi.r.bits.data := rData
  io.axi.r.bits.resp := AxiLiteResp.OKAY

  when(io.axi.aw.fire) {
    awValid := true.B
    awAddress := io.axi.aw.bits.addr
  }
  when(io.axi.w.fire) {
    wValid := true.B
    wData := io.axi.w.bits.data
    wStrb := io.axi.w.bits.strb
  }
  when(io.axi.b.fire) { bValid := false.B }

  def writeMasked(previous: UInt, next: UInt, strobe: UInt): UInt =
    VecInit((0 until 4).map(index => Mux(strobe(index),
      next(8 * index + 7, 8 * index), previous(8 * index + 7, 8 * index)))).asUInt

  val writeCommit = awValid && wValid && !bValid
  when(writeCommit) {
    switch(awAddress(7, 0)) {
      is("h60".U) { responseSequence := writeMasked(responseSequence, wData, wStrb) }
      is("h64".U) { responseLow := writeMasked(responseLow, wData, wStrb) }
      is("h68".U) { responseHigh := writeMasked(responseHigh, wData, wStrb) }
      is("h6c".U) {
        when(wStrb(0)) {
          responseFlags := wData(4, 0)
          responseIllegal := wData(8)
        }
      }
      is("h70".U) {
        when(wData(1)) {
          fallbackProtocolError := false.B
          clearProtocolError := true.B
        }
        when(wData(0) && !responsePending) {
          when(requestPending && responseSequence === request.sequence) {
            responsePending := true.B
          }.otherwise {
            fallbackProtocolError := true.B
          }
        }
      }
      is("h40".U) { commandSequence := writeMasked(commandSequence, wData, wStrb) }
      is("h44".U) {
        when(wStrb(0)) {
          debugCommand.valid := true.B
          debugCommand.bits.sequence := commandSequence
          debugCommand.bits.operation := wData(2, 0)
        }
      }
      is("h80".U) {
        when(wStrb(0)) {
          coreReset := wData(0)
          resetControl.valid := true.B
          resetControl.bits.asserted := wData(0)
          resetControl.bits.run := wData(1)
          when(wData(0)) {
            requestPending := false.B
            responsePending := false.B
            fallbackProtocolError := false.B
            timeoutCycles := 0.U
            putchPending := false.B
          }
          when(wData(2)) { putchPending := false.B }
        }
      }
      is("h8c".U) {
        when(wStrb(0)) { gprIndex := wData(4, 0) }
        when(wStrb(1)) { fprIndex := wData(12, 8) }
      }
      is("h5c".U) { when(wStrb(0)) { csrIndex := wData(2, 0) } }
      is("hf0".U) { memoryHostBaseLow := writeMasked(memoryHostBaseLow, wData, wStrb) }
      is("hf4".U) { memoryHostBaseHigh := writeMasked(memoryHostBaseHigh, wData, wStrb) }
    }
    awValid := false.B
    wValid := false.B
    bValid := true.B
  }

  def low(value: UInt): UInt = value(31, 0)
  def high(value: UInt): UInt = if (width == 64) value(63, 32) else 0.U(32.W)
  val selectedCsr = MuxLookup(csrIndex, 0.U(width.W))(Seq(
    0.U -> io.runtime.mstatus,
    1.U -> io.runtime.mcause,
    2.U -> io.runtime.mepc,
    3.U -> io.runtime.mtvec,
    4.U -> io.runtime.fcsr,
    5.U -> io.runtime.nextArchitecturalPc
  ))
  def readRegister(address: UInt): UInt = MuxLookup(address(7, 0), 0.U(32.W))(Seq(
    "h00".U -> Cat(0.U(27.W), protocolError, io.core.busy, responsePending, requestPending),
    "h04".U -> request.sequence,
    "h08".U -> low(request.pc),
    "h0c".U -> high(request.pc),
    "h10".U -> request.instruction,
    "h14".U -> Cat(0.U(24.W), request.roundingMode, request.operation),
    "h18".U -> Cat(0.U(24.W), request.fcsr),
    "h20".U -> low(request.operandA),
    "h24".U -> high(request.operandA),
    "h28".U -> low(request.operandB),
    "h2c".U -> high(request.operandB),
    "h30".U -> low(request.operandC),
    "h34".U -> high(request.operandC),
    "h38".U -> timeoutCycles,
    "h3c".U -> 7.U(32.W),
    "h40".U -> commandSequence,
    "h48".U -> debug.completedSequence,
    "h4c".U -> Cat(0.U(26.W), protocolError, coreReset,
      debug.stepping, debug.halting, debug.stableHalted, debug.running),
    "h50".U -> low(debug.haltNextPc),
    "h54".U -> high(debug.haltNextPc),
    "h58".U -> Cat(0.U(28.W), debug.stopReason),
    "h5c".U -> Cat(0.U(29.W), csrIndex),
    "h60".U -> responseSequence,
    "h64".U -> responseLow,
    "h68".U -> responseHigh,
    "h6c".U -> Cat(0.U(23.W), responseIllegal, 0.U(3.W), responseFlags),
    "h80".U -> Cat(0.U(31.W), coreReset),
    "h84".U -> Cat(0.U(28.W), protocolError, putchPending, debug.stableHalted, debug.running),
    "h88".U -> Cat(2.U(8.W), 7.U(8.W), (width == 64).B, 0.U(7.W), width.U(8.W)),
    "h8c".U -> Cat(0.U(19.W), fprIndex, 0.U(3.W), gprIndex),
    "h90".U -> low(io.runtime.gprs(gprIndex)),
    "h94".U -> high(io.runtime.gprs(gprIndex)),
    "h98".U -> low(io.runtime.fprs(fprIndex)),
    "h9c".U -> high(io.runtime.fprs(fprIndex)),
    "ha0".U -> Cat(0.U(24.W), io.runtime.fcsr),
    "ha4".U -> low(io.runtime.mstatus),
    "ha8".U -> high(io.runtime.mstatus),
    "hac".U -> low(io.runtime.currentPc),
    "hb0".U -> high(io.runtime.currentPc),
    "hb4".U -> low(Mux(debug.halted, debug.haltPc, io.runtime.commitPc)),
    "hb8".U -> high(Mux(debug.halted, debug.haltPc, io.runtime.commitPc)),
    "hbc".U -> io.runtime.commitInstruction,
    "hc0".U -> low(Mux(debug.halted, debug.haltNextPc, io.runtime.commitNextPc)),
    "hc4".U -> high(Mux(debug.halted, debug.haltNextPc, io.runtime.commitNextPc)),
    "hc8".U -> io.runtime.cycleCount(31, 0),
    "hcc".U -> io.runtime.cycleCount(63, 32),
    "hd0".U -> debug.commitCount(31, 0),
    "hd4".U -> debug.commitCount(63, 32),
    "hd8".U -> low(debug.haltCode),
    "hdc".U -> high(debug.haltCode),
    "he0".U -> Cat(0.U(23.W), io.runtime.backpressureReasons),
    "he4".U -> io.runtime.frontendInstruction,
    "he8".U -> Cat(0.U(24.W), putchData),
    "hec".U -> low(selectedCsr),
    "hf0".U -> memoryHostBaseLow,
    "hf4".U -> memoryHostBaseHigh,
    "hf8".U -> high(selectedCsr),
    "hfc".U -> "h4e504302".U
  ))

  when(io.axi.ar.fire) {
    rData := readRegister(io.axi.ar.bits.addr)
    rValid := true.B
  }.elsewhen(io.axi.r.fire) {
    rValid := false.B
  }
}
