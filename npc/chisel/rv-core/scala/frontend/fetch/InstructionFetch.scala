package npc

import chisel3._
import chisel3.util._
import npc.ip.memory.{MemoryFault, MemoryFaultReason}
import npc.protocol.{AxiLiteMasterIO, AxiLiteResp}

/** 带单项响应缓冲的 IF 阶段 AXI 读主机。
  *
  * `registerInitialRequest` 会把空闲态的首个 AR 请求延后一拍，以 `requestPc`
  * 寄存器切断 PC 到外部 AXI 地址与 valid 的组合路径。
  */
class IFetchAXIAdapter(
  addrWidth: Int = 32,
  dataWidth: Int = 64,
  registerInitialRequest: Boolean = false
) extends Module {
  require(dataWidth == 32 || dataWidth == 64, s"IFetch only supports RV32/RV64 bus widths, got $dataWidth")

  val io = IO(new Bundle {
    val pc = Input(UInt(addrWidth.W))
    val inst = Output(UInt(32.W))
    val responseValid = Output(Bool())
    val responseReady = Input(Bool())
    val performanceCycle = Input(UInt(64.W))
    val flush = Input(Bool())
    val busy = Output(Bool())
    val fault = Output(new MemoryFault(addrWidth))
    val responseIssueCycle = Output(UInt(64.W))
    val axi = new AxiLiteMasterIO(addrWidth, dataWidth)
  })

  val sIdle :: sArWait :: sRWait :: sFault :: Nil = Enum(4)
  val state = RegInit(sIdle)
  val instReg = RegInit(0x00000013.U(32.W))
  val cachedPc = RegInit(~0.U(addrWidth.W))
  val responsePending = RegInit(false.B)
  val discardResponse = RegInit(false.B)
  val requestPc = RegInit(0.U(addrWidth.W))
  val requestIssueCycle = RegInit(0.U(64.W))
  val faultAddrReg = RegInit(0.U(addrWidth.W))
  val faultReasonReg = RegInit(0.U(3.W))
  val beatOffsetBits = log2Ceil(dataWidth / 8)

  def beatAddr(addr: UInt): UInt = Cat(addr(addrWidth - 1, beatOffsetBits), 0.U(beatOffsetBits.W))
  def latchFault(addr: UInt, reason: UInt): Unit = {
    faultAddrReg := addr
    faultReasonReg := reason
    state := sFault
  }

  val needFetch = io.pc =/= cachedPc
  val responseData = WireDefault(instReg)
  when(state === sRWait && io.axi.r.valid) {
    val byteOffset = requestPc(beatOffsetBits - 1, 0)
    responseData := (io.axi.r.bits.data >> (byteOffset << 3))(31, 0)
  }
  val directResponseValid = state === sRWait && io.axi.r.valid && io.axi.r.bits.resp === 0.U &&
    io.pc === requestPc && !discardResponse && !io.flush
  io.inst := Mux(directResponseValid, responseData, instReg)
  io.responseValid := (responsePending && io.pc === cachedPc) || directResponseValid
  io.responseIssueCycle := requestIssueCycle
  io.busy := needFetch || state =/= sIdle || responsePending
  io.fault.valid := state === sFault
  io.fault.addr := faultAddrReg
  io.fault.write := false.B
  io.fault.len := 4.U
  io.fault.reason := faultReasonReg

  io.axi.aw.valid := false.B
  io.axi.aw.bits.addr := 0.U
  io.axi.aw.bits.size := 0.U
  io.axi.aw.bits.prot := 0.U
  io.axi.w.valid := false.B
  io.axi.w.bits.data := 0.U
  io.axi.w.bits.strb := 0.U
  io.axi.b.ready := false.B
  io.axi.ar.valid := false.B
  io.axi.ar.bits.addr := beatAddr(requestPc)
  io.axi.ar.bits.size := log2Ceil(dataWidth / 8).U(3.W)
  io.axi.ar.bits.prot := "b100".U
  io.axi.r.ready := state === sRWait && (!responsePending || io.flush)

  when(!reset.asBool) {
    when(io.flush) {
      cachedPc := ~0.U
      responsePending := false.B
      when(state === sRWait) {
        when(io.axi.r.fire) {
          discardResponse := false.B
          state := sIdle
        }.otherwise {
          discardResponse := true.B
        }
      }.otherwise {
        discardResponse := false.B
        state := sIdle
      }
    }.otherwise {
      when(responsePending && (io.responseReady || io.pc =/= cachedPc)) {
        responsePending := false.B
      }

      switch(state) {
        is(sIdle) {
          when(needFetch) {
            requestPc := io.pc
            requestIssueCycle := io.performanceCycle
            when(io.pc(1, 0).orR) {
              latchFault(io.pc, MemoryFaultReason.misaligned)
            }.otherwise {
              if (registerInitialRequest) {
                state := sArWait
              } else {
                io.axi.ar.valid := true.B
                io.axi.ar.bits.addr := beatAddr(io.pc)
                when(io.axi.ar.fire) {
                  state := sRWait
                }.otherwise {
                  state := sArWait
                }
              }
            }
          }
        }
        is(sArWait) {
          io.axi.ar.valid := true.B
          io.axi.ar.bits.addr := beatAddr(requestPc)
          when(io.axi.ar.fire) {
            requestIssueCycle := io.performanceCycle
            state := sRWait
          }
        }
        is(sRWait) {
          when(io.axi.r.fire) {
            when(discardResponse) {
              discardResponse := false.B
              state := sIdle
            }.elsewhen(io.axi.r.bits.resp =/= 0.U) {
              latchFault(requestPc, MemoryFaultReason.readResponse)
            }.otherwise {
              when(io.pc === requestPc) {
                instReg := responseData
                cachedPc := requestPc
                responsePending := !io.responseReady
              }
              state := sIdle
            }
          }
        }
        is(sFault) {}
      }
    }
  }
}

/** 流水取指在 AR 发射时保存的 PC/epoch。 */
class PipelinedFetchRequest(addrWidth: Int) extends Bundle {
  val pc = UInt(addrWidth.W)
  val epoch = Bool()
  val issueCycle = UInt(64.W)
}

/**
  * 本地两拍缓存模式的顺序取指 AXI 适配器。
  *
  * FIFO 最多保存若干已发 AR 的 PC/epoch。正常顺序流每拍可发一笔；redirect 或
  * FENCE.I 到来时切换 epoch，并让目标 PC 的新 AR 立即排在旧请求之后。旧 R 仍按
  * AXI 顺序被消费但不会输出，因此错误路径的指令不会进入 instruction buffer。
  */
class PipelinedIFetchAXIAdapter(
  addrWidth: Int = 32,
  dataWidth: Int = 64,
  outstandingDepth: Int = 4,
  allowRedirectRequestOverlap: Boolean = true
) extends Module {
  require(dataWidth == 32 || dataWidth == 64,
    s"PipelinedIFetch only supports RV32/RV64 bus widths, got $dataWidth")
  require(outstandingDepth > 0 && (outstandingDepth & (outstandingDepth - 1)) == 0,
    s"outstandingDepth must be a power of two, got $outstandingDepth")

  val io = IO(new Bundle {
    val pc = Input(UInt(addrWidth.W))
    val restartPc = Input(UInt(addrWidth.W))
    val inst = Output(UInt(32.W))
    val responsePc = Output(UInt(addrWidth.W))
    val responseIssueCycle = Output(UInt(64.W))
    val responseValid = Output(Bool())
    val responseReady = Input(Bool())
    val performanceCycle = Input(UInt(64.W))
    // 后端排空直通路径的过渡拍不再发出新的 AR，避免已保持的 R 在缓存响应 FIFO 中
    // 形成持续占用；保持解除后仍由同一 nextPc 重新发起该请求。
    val issueHold = Input(Bool())
    val predictionValid = Input(Bool())
    val predictionTarget = Input(UInt(addrWidth.W))
    val flush = Input(Bool())
    val busy = Output(Bool())
    val fault = Output(new MemoryFault(addrWidth))
    val axi = new AxiLiteMasterIO(addrWidth, dataWidth)
  })

  val requests = Module(new Queue(new PipelinedFetchRequest(addrWidth),
    outstandingDepth, pipe = false, flow = false))
  val currentEpoch = RegInit(false.B)
  val nextPc = RegInit(0.U(addrWidth.W))
  val initialized = RegInit(false.B)
  val drainingOld = RegInit(false.B)
  val faultValid = RegInit(false.B)
  val faultAddr = RegInit(0.U(addrWidth.W))
  val faultReason = RegInit(0.U(3.W))
  private val beatOffsetBits = log2Ceil(dataWidth / 8)

  def beatAddr(address: UInt): UInt = Cat(address(addrWidth - 1, beatOffsetBits), 0.U(beatOffsetBits.W))

  val issuePc = Mux(initialized, nextPc, io.pc)
  val issueNextPc = issuePc + 4.U
  val issueMisaligned = issuePc(1, 0).orR
  val issueAllowed = (allowRedirectRequestOverlap.B || !drainingOld) && !io.flush && !io.issueHold &&
    !io.predictionValid && !faultValid && !issueMisaligned
  io.axi.aw.valid := false.B
  io.axi.aw.bits.addr := 0.U
  io.axi.aw.bits.size := 0.U
  io.axi.aw.bits.prot := 0.U
  io.axi.w.valid := false.B
  io.axi.w.bits.data := 0.U
  io.axi.w.bits.strb := 0.U
  io.axi.b.ready := false.B
  io.axi.ar.valid := issueAllowed && requests.io.enq.ready
  io.axi.ar.bits.addr := beatAddr(issuePc)
  io.axi.ar.bits.size := log2Ceil(dataWidth / 8).U(3.W)
  io.axi.ar.bits.prot := "b100".U
  requests.io.enq.valid := io.axi.ar.fire
  requests.io.enq.bits.pc := issuePc
  requests.io.enq.bits.epoch := currentEpoch
  requests.io.enq.bits.issueCycle := io.performanceCycle

  val responseRequest = requests.io.deq.bits
  val responseEpochMatches = responseRequest.epoch === currentEpoch &&
    (allowRedirectRequestOverlap.B || !drainingOld)
  val responseOk = io.axi.r.bits.resp === AxiLiteResp.OKAY
  val responseWord = (io.axi.r.bits.data >>
    (responseRequest.pc(beatOffsetBits - 1, 0) << 3))(31, 0)
  io.responsePc := responseRequest.pc
  io.responseIssueCycle := responseRequest.issueCycle
  io.inst := responseWord
  io.responseValid := io.axi.r.valid && requests.io.deq.valid && responseEpochMatches && responseOk
  io.axi.r.ready := requests.io.deq.valid &&
    (drainingOld || !responseEpochMatches || !responseOk || io.responseReady)
  requests.io.deq.ready := io.axi.r.fire

  when(io.axi.ar.fire) {
    nextPc := issueNextPc
    initialized := true.B
  }
  when(!initialized && issueMisaligned && !io.flush) {
    faultValid := true.B
    faultAddr := issuePc
    faultReason := MemoryFaultReason.misaligned
  }
  when(io.axi.r.fire && (!drainingOld || allowRedirectRequestOverlap.B) &&
    responseEpochMatches && !responseOk) {
    faultValid := true.B
    faultAddr := responseRequest.pc
    faultReason := MemoryFaultReason.readResponse
  }
  // 预测命中的控制流不会等待 EX 的重复 redirect。非重叠路径则先清空旧 epoch，
  // 使标量核不会在后端恢复前保留跨越 redirect 的错误路径请求。
  when(io.predictionValid) {
    currentEpoch := !currentEpoch
    nextPc := io.predictionTarget
    initialized := true.B
  }
  when(io.flush) {
    currentEpoch := !currentEpoch
    nextPc := io.restartPc
    initialized := true.B
    drainingOld := !allowRedirectRequestOverlap.B && (requests.io.deq.valid || io.axi.r.valid)
    faultValid := false.B
  }.elsewhen(drainingOld && !requests.io.deq.valid) {
    drainingOld := false.B
  }

  io.busy := drainingOld || requests.io.deq.valid || io.axi.ar.valid || faultValid
  io.fault.valid := faultValid
  io.fault.addr := faultAddr
  io.fault.write := false.B
  io.fault.len := 4.U
  io.fault.reason := faultReason
}
