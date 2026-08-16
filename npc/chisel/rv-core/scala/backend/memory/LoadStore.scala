package npc

import chisel3._
import chisel3.util._
import npc.ip.memory.{MemoryFault, MemoryFaultReason}
import npc.protocol.{AxiLiteMasterIO, AxiLiteResp, ExecuteMemoryPayload, MemoryWritebackPayload}

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

/** 已发出到 D$ 的流水访存，用于把 AXI 响应与原始指令按顺序重新配对。 */
class PipelinedMemoryIssued(addrWidth: Int) extends Bundle {
  val tag = UInt(2.W)
  val write = Bool()
  val addr = UInt(addrWidth.W)
  val accessType = UInt(3.W)
  val serviceStartCycle = UInt(64.W)
  val queueCycles = UInt(64.W)
}

/** 已分配完成表槽位的待发访存。 */
class PipelinedMemoryPending(cfg: ISAConfig) extends Bundle {
  val tag = UInt(2.W)
  val payload = new ExecuteMemoryPayload(cfg)
}

/** 算术端点回填完成表时携带的结果。 */
class PipelinedArithmeticCompletion(xlen: Int) extends Bundle {
  val tag = UInt(2.W)
  val result = UInt(xlen.W)
  val illegal = Bool()
}

/** 供后端 RAW 检测和旁路选择的完成表候选项，顺序由新到旧。 */
class OutstandingCompletionCandidate(xlen: Int) extends Bundle {
  val valid = Bool()
  val writesRd = Bool()
  val rd = UInt(5.W)
  val data = UInt(xlen.W)
  val dataValid = Bool()
}

/** D$ 完成队列的元素；fault 也占据一个完成位置，不能让年轻请求越过它。 */
class PipelinedMemoryCompletion(addrWidth: Int, dataWidth: Int) extends Bundle {
  val data = UInt(dataWidth.W)
  val resp = UInt(2.W)
  val fault = Bool()
  val faultAddr = UInt(addrWidth.W)
  val faultWrite = Bool()
  val faultLen = UInt(4.W)
  val faultReason = UInt(3.W)
  val serviceStartCycle = UInt(64.W)
  val queueCycles = UInt(64.W)
  val serviceCycles = UInt(64.W)
}

/**
  * 本地两拍缓存模式的独立、按序流水 MEM stage。
  *
  * 输入 FIFO 保存完整 EX/MEM payload，pending FIFO 只保存尚未发往 D$ 的访存，
  * issued FIFO 记录已经完成 AR 或 AW/W 握手的事务，completion FIFO 保存 R/B。
  * 因为输出端仍按 input FIFO 取数，非访存指令不能越过较老的 miss；四个 FIFO
  * 的深度固定为四，命中流在 D$ 已准备好时可做到每拍接收和每拍返回。
  *
  * `perfMemoryStartCycle` 在 MEM/WB 被改写为真实 service 起点；排队起点、排队
  * 周期和 service 周期通过独立侧带保留。AXI fault 或错位访问都生成一个有序
  * completion，并锁住年轻请求直到外部观察到 fault。
  */
private class LegacyPipelinedMemoryStage(
  addrWidth: Int = 32,
  dataWidth: Int = 64,
  mainMemoryBase: Long = 0x80000000L,
  mainMemorySize: Long = 0x08000000L,
  val outstandingDepth: Int = 4,
  cfg: ISAConfig = ISAConfig()
) extends Module {
  require(outstandingDepth == 4,
    s"PipelinedMemoryStage freezes four memory queue entries, got $outstandingDepth")
  require(cfg.xlen == dataWidth,
    s"PipelinedMemoryStage payload XLEN ${cfg.xlen} must match AXI width $dataWidth")

  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new ExecuteMemoryPayload(cfg)))
    val response = Decoupled(new MemoryWritebackPayload(cfg))
    val cycle = Input(UInt(64.W))
    val flush = Input(Bool())
    val axi = new AxiLiteMasterIO(addrWidth, dataWidth)
    val fault = Output(new MemoryFault(addrWidth))
    val busy = Output(Bool())
    val drained = Output(Bool())
    val retirementDrained = Output(Bool())
  })

  val orderQueue = Module(new Queue(new ExecuteMemoryPayload(cfg), outstandingDepth,
    pipe = false, flow = false))
  val pendingQueue = Module(new Queue(new ExecuteMemoryPayload(cfg), outstandingDepth,
    pipe = false, flow = false))
  val issuedQueue = Module(new Queue(new PipelinedMemoryIssued(addrWidth), outstandingDepth,
    pipe = false, flow = false))
  val completionQueue = Module(new Queue(new PipelinedMemoryCompletion(addrWidth, dataWidth),
    outstandingDepth, pipe = false, flow = false))

  val faultValid = RegInit(false.B)
  val faultAddrReg = RegInit(0.U(addrWidth.W))
  val faultWriteReg = RegInit(false.B)
  val faultLenReg = RegInit(0.U(4.W))
  val faultReasonReg = RegInit(0.U(3.W))
  val dropYoung = RegInit(false.B)
  val writeAwSent = RegInit(false.B)
  val writeWSent = RegInit(false.B)

  def accessBytes(accessType: UInt): UInt = MuxLookup(accessType(1, 0), 1.U(4.W))(Seq(
    "b00".U -> 1.U(4.W), "b01".U -> 2.U(4.W), "b10".U -> 4.U(4.W),
    "b11".U -> 8.U(4.W)
  ))

  def naturallyMisaligned(addr: UInt, accessType: UInt): Bool = MuxLookup(accessType(1, 0), false.B)(Seq(
    "b00".U -> false.B,
    "b01".U -> addr(0),
    "b10".U -> addr(1, 0).orR,
    "b11".U -> addr(2, 0).orR
  ))

  private val beatBytes = dataWidth / 8
  private val beatOffsetBits = log2Ceil(beatBytes)
  def crossesBeat(addr: UInt, accessType: UInt): Bool =
    (addr(beatOffsetBits - 1, 0) +& accessBytes(accessType)) > beatBytes.U

  val requestIsMemory = io.request.bits.loadEnable || io.request.bits.storeEnable
  val requestMisaligned = naturallyMisaligned(io.request.bits.aluResult, io.request.bits.funct3)
  val requestCrossesBeat = crossesBeat(io.request.bits.aluResult, io.request.bits.funct3)

  // fault 后不再接收新的年轻指令；已经进入 FIFO 的年轻项在 fault 提交后被丢弃。
  io.request.ready := !dropYoung && !faultValid && orderQueue.io.enq.ready &&
    (!requestIsMemory || pendingQueue.io.enq.ready) && !io.flush
  orderQueue.io.enq.valid := io.request.fire
  orderQueue.io.enq.bits := io.request.bits
  pendingQueue.io.enq.valid := io.request.fire && requestIsMemory
  pendingQueue.io.enq.bits := io.request.bits

  val pending = pendingQueue.io.deq.bits
  val pendingValid = pendingQueue.io.deq.valid && !dropYoung && !faultValid && !io.flush
  val pendingMisaligned = naturallyMisaligned(pending.aluResult, pending.funct3)
  val pendingCrossesBeat = crossesBeat(pending.aluResult, pending.funct3)
  val pendingFault = pendingMisaligned || pendingCrossesBeat
  val canIssueBus = pendingValid && !pendingFault && issuedQueue.io.enq.ready

  io.axi.aw.valid := canIssueBus && pending.storeEnable && !writeAwSent
  io.axi.aw.bits.addr := pending.aluResult(addrWidth - 1, 0)
  io.axi.aw.bits.size := pending.funct3(1, 0)
  io.axi.aw.bits.prot := "b000".U
  io.axi.w.valid := canIssueBus && pending.storeEnable && !writeWSent
  // AXI-Lite 的 strobe 已按地址移到字内对应 lane，数据也必须同步左移；否则
  // 非零字节偏移的 store 会在 D$ 的按 lane 合并中取到错误的高位。
  io.axi.w.bits.data := AxiLiteWstrb.alignData(pending.storeData,
    pending.aluResult(beatOffsetBits - 1, 0), dataWidth)
  io.axi.w.bits.strb := AxiLiteWstrb.genStrb(pending.funct3,
    pending.aluResult(beatOffsetBits - 1, 0), dataWidth)
  io.axi.b.ready := false.B
  io.axi.ar.valid := canIssueBus && pending.loadEnable
  io.axi.ar.bits.addr := pending.aluResult(addrWidth - 1, 0)
  io.axi.ar.bits.size := pending.funct3(1, 0)
  io.axi.ar.bits.prot := "b100".U
  io.axi.r.ready := false.B

  val awFire = io.axi.aw.fire
  val wFire = io.axi.w.fire
  val writeComplete = canIssueBus && pending.storeEnable &&
    (writeAwSent || awFire) && (writeWSent || wFire)
  val readIssue = io.axi.ar.fire
  val busIssue = readIssue || writeComplete
  val busResponse = WireDefault(false.B)
  // 错位项可能早于更老的 AXI 响应被发现。等 issued FIFO 清空后再插入 fault，
  // 这样 fault completion 仍位于所有更老响应之后。
  val faultCompletion = pendingValid && pendingFault && !issuedQueue.io.deq.valid &&
    completionQueue.io.enq.ready && !busResponse
  pendingQueue.io.deq.ready := dropYoung || faultCompletion || readIssue || writeComplete

  issuedQueue.io.enq.valid := busIssue
  issuedQueue.io.enq.bits.tag := 0.U
  issuedQueue.io.enq.bits.write := pending.storeEnable
  issuedQueue.io.enq.bits.addr := pending.aluResult(addrWidth - 1, 0)
  issuedQueue.io.enq.bits.accessType := pending.funct3
  issuedQueue.io.enq.bits.serviceStartCycle := io.cycle
  issuedQueue.io.enq.bits.queueCycles := io.cycle - pending.perfMemoryQueueStartCycle

  when(writeComplete) {
    // AW/W 都在本拍完成时，下一笔 store 从空的握手状态开始。
    writeAwSent := false.B
    writeWSent := false.B
  }.otherwise {
    when(awFire) { writeAwSent := true.B }
    when(wFire) { writeWSent := true.B }
  }

  val issued = issuedQueue.io.deq.bits
  val responseQueueReady = completionQueue.io.enq.ready
  io.axi.r.ready := issuedQueue.io.deq.valid && !issued.write && responseQueueReady
  io.axi.b.ready := issuedQueue.io.deq.valid && issued.write && responseQueueReady
  val readResponse = io.axi.r.fire
  val writeResponse = io.axi.b.fire
  busResponse := readResponse || writeResponse
  issuedQueue.io.deq.ready := busResponse

  completionQueue.io.enq.valid := faultCompletion || busResponse
  completionQueue.io.enq.bits.data := Mux(busResponse && !issued.write,
    AxiLiteLoadUnpack.unpack(io.axi.r.bits.data,
      issued.addr(beatOffsetBits - 1, 0), issued.accessType, dataWidth), 0.U)
  completionQueue.io.enq.bits.resp := Mux(busResponse,
    Mux(issued.write, io.axi.b.bits.resp, io.axi.r.bits.resp), AxiLiteResp.OKAY)
  completionQueue.io.enq.bits.fault := Mux(busResponse,
    Mux(issued.write, io.axi.b.bits.resp =/= AxiLiteResp.OKAY,
      io.axi.r.bits.resp =/= AxiLiteResp.OKAY), faultCompletion)
  completionQueue.io.enq.bits.faultAddr := Mux(busResponse, issued.addr,
    pending.aluResult(addrWidth - 1, 0))
  completionQueue.io.enq.bits.faultWrite := Mux(busResponse, issued.write, pending.storeEnable)
  completionQueue.io.enq.bits.faultLen := Mux(busResponse, accessBytes(issued.accessType),
    accessBytes(pending.funct3))
  completionQueue.io.enq.bits.faultReason := Mux(busResponse,
    Mux(issued.write, MemoryFaultReason.writeResponse, MemoryFaultReason.readResponse),
    Mux(pendingMisaligned, MemoryFaultReason.misaligned, MemoryFaultReason.crossBeat))
  completionQueue.io.enq.bits.serviceStartCycle := Mux(busResponse,
    issued.serviceStartCycle, io.cycle)
  completionQueue.io.enq.bits.queueCycles := Mux(busResponse,
    issued.queueCycles, io.cycle - pending.perfMemoryQueueStartCycle)
  completionQueue.io.enq.bits.serviceCycles := Mux(busResponse,
    io.cycle - issued.serviceStartCycle, 0.U)

  when(faultCompletion) {
    faultValid := true.B
    faultAddrReg := pending.aluResult(addrWidth - 1, 0)
    faultWriteReg := pending.storeEnable
    faultLenReg := accessBytes(pending.funct3)
    faultReasonReg := Mux(pendingMisaligned, MemoryFaultReason.misaligned,
      MemoryFaultReason.crossBeat)
  }
  when(busResponse && Mux(issued.write, io.axi.b.bits.resp, io.axi.r.bits.resp) =/= AxiLiteResp.OKAY) {
    faultValid := true.B
    faultAddrReg := issued.addr
    faultWriteReg := issued.write
    faultLenReg := accessBytes(issued.accessType)
    faultReasonReg := Mux(issued.write, MemoryFaultReason.writeResponse,
      MemoryFaultReason.readResponse)
  }

  val completion = completionQueue.io.deq.bits
  val order = orderQueue.io.deq.bits
  val orderIsMemory = order.loadEnable || order.storeEnable
  val orderedResponseValid = !dropYoung && orderQueue.io.deq.valid &&
    (!orderIsMemory || completionQueue.io.deq.valid)
  io.response.valid := orderedResponseValid
  io.response.bits := 0.U.asTypeOf(new MemoryWritebackPayload(cfg))
  val responseData = Mux(orderIsMemory, completion.data, 0.U(cfg.xlen.W))
  val responseQueueCycles = Mux(orderIsMemory, completion.queueCycles, 0.U)
  val responseServiceStart = Mux(orderIsMemory, completion.serviceStartCycle,
    order.perfMemoryStartCycle)
  val responseServiceCycles = Mux(orderIsMemory, completion.serviceCycles, 0.U)
  val responseQueueStart = Mux(orderIsMemory, order.perfMemoryQueueStartCycle,
    order.perfMemoryStartCycle)
  val responseFault = orderIsMemory && completion.fault
  def fillResponse(dst: MemoryWritebackPayload): Unit = {
    val branchNextPc = Mux(order.branchTaken === 2.U, order.jalrTarget, order.branchTarget)
    dst.pc := order.pc
    dst.instruction := order.instruction
    dst.perfFetchStartCycle := order.perfFetchStartCycle
    dst.perfFetchCycles := order.perfFetchCycles
    dst.perfDecodeStartCycle := order.perfDecodeStartCycle
    dst.perfDecodeCycles := order.perfDecodeCycles
    dst.perfExecuteStartCycle := order.perfExecuteStartCycle
    dst.perfExecuteCycles := order.perfExecuteCycles
    dst.perfMemoryStartCycle := responseServiceStart
    dst.perfMemoryCycles := responseServiceCycles
    dst.perfMemoryQueueStartCycle := responseQueueStart
    dst.perfMemoryServiceStartCycle := responseServiceStart
    dst.perfMemoryQueueCycles := responseQueueCycles
    dst.perfMemoryServiceCycles := responseServiceCycles
    dst.perfWritebackStartCycle := io.cycle
    dst.nextPc := Mux(order.branch && order.branchTaken =/= 0.U, branchNextPc, order.pc + 4.U)
    dst.rd := order.rd
    dst.aluResult := order.aluResult
    dst.storeData := order.storeData
    dst.storeEnable := order.storeEnable
    dst.storeAccessType := order.funct3
    dst.loadData := responseData
    dst.csrReadData := order.csrReadData
    dst.writebackFromMemory := order.writebackFromMemory
    dst.registerWriteEnable := order.registerWriteEnable && !responseFault
    dst.csrReadWritebackEnable := order.csrReadWritebackEnable
    dst.csrAddress := order.csrAddress
    dst.csrWriteEnable := order.csrWriteEnable
    dst.csrWriteData := order.csrWriteData
    dst.csrAccessAllowed := order.csrAccessAllowed
    dst.trapEnable := order.trapEnable
    dst.trapCause := order.trapCause
    dst.trapEpc := order.trapEpc
    dst.mretEnable := order.mretEnable
  }
  fillResponse(io.response.bits)

  val responseFire = io.response.fire
  orderQueue.io.deq.ready := dropYoung || responseFire
  completionQueue.io.deq.ready := dropYoung ||
    (responseFire && orderIsMemory)
  when(responseFire && orderIsMemory && completion.fault) {
    // 只有 fault 对应的最老项已经交给 WB 后，才可丢弃其后的年轻项。
    dropYoung := true.B
  }
  when(dropYoung && !orderQueue.io.deq.valid && !pendingQueue.io.deq.valid &&
    !completionQueue.io.deq.valid && !issuedQueue.io.deq.valid) {
    dropYoung := false.B
  }

  io.fault.valid := faultValid
  io.fault.addr := faultAddrReg
  io.fault.write := faultWriteReg
  io.fault.len := faultLenReg
  io.fault.reason := faultReasonReg

  val anyQueueValid = orderQueue.io.deq.valid || pendingQueue.io.deq.valid ||
    issuedQueue.io.deq.valid || completionQueue.io.deq.valid
  io.busy := faultValid || dropYoung || anyQueueValid || writeAwSent || writeWSent
  io.drained := !io.busy
  io.retirementDrained := !orderQueue.io.deq.valid && !faultValid && !dropYoung &&
    !issuedQueue.io.deq.valid && !completionQueue.io.deq.valid && !writeAwSent && !writeWSent
}

/** 两拍缓存完成表的公共接口。 */
class PipelinedMemoryStageIO(
  addrWidth: Int,
  dataWidth: Int,
  outstandingDepth: Int,
  cfg: ISAConfig
) extends Bundle {
  val request = Flipped(Decoupled(new ExecuteMemoryPayload(cfg)))
  val arithmeticRequest = Flipped(Decoupled(new ExecuteMemoryPayload(cfg)))
  val arithmeticAllocateTag = Output(UInt(2.W))
  val arithmeticSlotAvailable = Output(Bool())
  val arithmeticCompletion = Vec(2, Flipped(Decoupled(new PipelinedArithmeticCompletion(cfg.xlen))))
  val completionCandidates = Output(Vec(outstandingDepth, new OutstandingCompletionCandidate(cfg.xlen)))
  val response = Decoupled(new MemoryWritebackPayload(cfg))
  val cycle = Input(UInt(64.W))
  val flush = Input(Bool())
  val axi = new AxiLiteMasterIO(addrWidth, dataWidth)
  val fault = Output(new MemoryFault(addrWidth))
  val busy = Output(Bool())
  val drained = Output(Bool())
  // 不含本拍 EX/MEM 输入的历史事务排空状态，供后端整数 WB 旁路保持提交顺序。
  val retirementDrained = Output(Bool())
}

/**
  * 本地两拍缓存的四项完成/退休环。
  *
  * 槽位按分配顺序形成一个固定环。访存和 M/F 请求先占据槽位，完成可以乱序回填，
  * 但只有环首已完成的槽位可送入 MEM/WB。发生 fault 后停止新分配，在 fault
  * 退休的同拍丢弃年轻槽位，并等待旧端点响应被吸收后才复用 tag。
  */
private class OutstandingPipelinedMemoryStage(
  addrWidth: Int,
  dataWidth: Int,
  mainMemoryBase: Long,
  mainMemorySize: Long,
  outstandingDepth: Int,
  cfg: ISAConfig
) extends Module {
  require(outstandingDepth == 4,
    s"OutstandingPipelinedMemoryStage freezes four completion entries, got $outstandingDepth")

  val io = IO(new PipelinedMemoryStageIO(addrWidth, dataWidth, outstandingDepth, cfg))
  private val tagWidth = 2
  private val beatBytes = dataWidth / 8
  private val beatOffsetBits = log2Ceil(beatBytes)

  val entryPayload = Reg(Vec(outstandingDepth, new ExecuteMemoryPayload(cfg)))
  val entryValid = RegInit(VecInit(Seq.fill(outstandingDepth)(false.B)))
  val entryComplete = RegInit(VecInit(Seq.fill(outstandingDepth)(false.B)))
  val entryArithmetic = RegInit(VecInit(Seq.fill(outstandingDepth)(false.B)))
  val entryLoadData = Reg(Vec(outstandingDepth, UInt(cfg.xlen.W)))
  val entryResult = Reg(Vec(outstandingDepth, UInt(cfg.xlen.W)))
  val entryIllegal = RegInit(VecInit(Seq.fill(outstandingDepth)(false.B)))
  val entryFault = RegInit(VecInit(Seq.fill(outstandingDepth)(false.B)))
  val entryFaultReason = Reg(Vec(outstandingDepth, UInt(3.W)))
  val entryServiceStart = Reg(Vec(outstandingDepth, UInt(64.W)))
  val entryQueueCycles = Reg(Vec(outstandingDepth, UInt(64.W)))
  val entryServiceCycles = Reg(Vec(outstandingDepth, UInt(64.W)))
  val headIndex = RegInit(0.U(tagWidth.W))
  val tailIndex = RegInit(0.U(tagWidth.W))
  val entryCount = RegInit(0.U(3.W))

  val pendingQueue = Module(new Queue(new PipelinedMemoryPending(cfg), outstandingDepth,
    // EX/MEM 已经是寄存器边界。空 pending FIFO 让首个请求直接握手到 D$，避免
    // 在同步 L1 命中之前再额外停一拍；下游拒绝时仍会按原顺序保存该请求。
    pipe = false, flow = true))
  val issuedQueue = Module(new Queue(new PipelinedMemoryIssued(addrWidth), outstandingDepth,
    pipe = false, flow = false))
  val writeAwSent = RegInit(false.B)
  val writeWSent = RegInit(false.B)
  val faultPending = RegInit(false.B)
  val faultAddrReg = RegInit(0.U(addrWidth.W))
  val faultWriteReg = RegInit(false.B)
  val faultLenReg = RegInit(0.U(4.W))
  val faultReasonReg = RegInit(0.U(3.W))
  val dropYoung = RegInit(false.B)
  // fault 退休后完成表会清空年轻槽位，但端点中已经接受的请求仍会迟到响应。计数器
  // 在发射和响应握手的同拍同时更新，只有所有悬挂端点都被吸收后才能复用 tag。
  val arithmeticInFlightWidth = log2Ceil(outstandingDepth + 1)
  val mulDivInFlight = RegInit(0.U(arithmeticInFlightWidth.W))

  def accessBytes(accessType: UInt): UInt = MuxLookup(accessType(1, 0), 1.U(4.W))(Seq(
    "b00".U -> 1.U(4.W), "b01".U -> 2.U(4.W), "b10".U -> 4.U(4.W),
    "b11".U -> 8.U(4.W)
  ))

  def naturallyMisaligned(addr: UInt, accessType: UInt): Bool = MuxLookup(accessType(1, 0), false.B)(Seq(
    "b00".U -> false.B,
    "b01".U -> addr(0),
    "b10".U -> addr(1, 0).orR,
    "b11".U -> addr(2, 0).orR
  ))

  def crossesBeat(addr: UInt, accessType: UInt): Bool =
    (addr(beatOffsetBits - 1, 0) +& accessBytes(accessType)) > beatBytes.U

  private def increment(index: UInt): UInt = (index + 1.U)(tagWidth - 1, 0)
  private def newestIndex(offset: Int): UInt = (tailIndex - (offset + 1).U)(tagWidth - 1, 0)
  private def isMemory(payload: ExecuteMemoryPayload): Bool = payload.loadEnable || payload.storeEnable

  val canAllocate = !dropYoung && !faultPending && !io.flush && entryCount =/= outstandingDepth.U
  val normalIsMemory = isMemory(io.request.bits)
  // 环为空时，已完成的普通项可直接退休；该快路径不跨越任何较老槽位，也不占用
  // MEM queue/service 统计，保持原有空 MEM 的单拍行为。
  val sameCycleRetire = canAllocate && entryCount === 0.U && io.request.valid && !normalIsMemory
  io.request.ready := Mux(sameCycleRetire, io.response.ready,
    canAllocate && (!normalIsMemory || pendingQueue.io.enq.ready))
  // EX/MEM 中的指令比 ID/EX 的算术请求更老，因而分配端口必须先让 EX/MEM 使用空槽位。
  io.arithmeticRequest.ready := canAllocate && !io.request.valid
  io.arithmeticAllocateTag := tailIndex
  io.arithmeticSlotAvailable := canAllocate
  val normalAllocate = io.request.fire && !sameCycleRetire
  val arithmeticAllocate = io.arithmeticRequest.fire
  val allocate = normalAllocate || arithmeticAllocate
  val allocatePayload = Wire(new ExecuteMemoryPayload(cfg))
  allocatePayload := Mux(arithmeticAllocate, io.arithmeticRequest.bits, io.request.bits)
  val allocateMemory = normalAllocate && normalIsMemory

  pendingQueue.io.enq.valid := normalAllocate && normalIsMemory
  pendingQueue.io.enq.bits.tag := tailIndex
  pendingQueue.io.enq.bits.payload := io.request.bits

  val pending = pendingQueue.io.deq.bits
  val pendingPayload = pending.payload
  val pendingValid = pendingQueue.io.deq.valid && !dropYoung && !faultPending && !io.flush
  val pendingMisaligned = naturallyMisaligned(pendingPayload.aluResult, pendingPayload.funct3)
  val pendingCrossesBeat = crossesBeat(pendingPayload.aluResult, pendingPayload.funct3)
  val pendingFault = pendingMisaligned || pendingCrossesBeat
  val canIssueBus = pendingValid && !pendingFault && issuedQueue.io.enq.ready

  io.axi.aw.valid := canIssueBus && pendingPayload.storeEnable && !writeAwSent
  io.axi.aw.bits.addr := pendingPayload.aluResult(addrWidth - 1, 0)
  io.axi.aw.bits.size := pendingPayload.funct3(1, 0)
  io.axi.aw.bits.prot := "b000".U
  io.axi.w.valid := canIssueBus && pendingPayload.storeEnable && !writeWSent
  io.axi.w.bits.data := AxiLiteWstrb.alignData(pendingPayload.storeData,
    pendingPayload.aluResult(beatOffsetBits - 1, 0), dataWidth)
  io.axi.w.bits.strb := AxiLiteWstrb.genStrb(pendingPayload.funct3,
    pendingPayload.aluResult(beatOffsetBits - 1, 0), dataWidth)
  io.axi.ar.valid := canIssueBus && pendingPayload.loadEnable
  io.axi.ar.bits.addr := pendingPayload.aluResult(addrWidth - 1, 0)
  io.axi.ar.bits.size := pendingPayload.funct3(1, 0)
  io.axi.ar.bits.prot := "b100".U
  io.axi.r.ready := false.B
  io.axi.b.ready := false.B

  val awFire = io.axi.aw.fire
  val wFire = io.axi.w.fire
  val writeComplete = canIssueBus && pendingPayload.storeEnable &&
    (writeAwSent || awFire) && (writeWSent || wFire)
  val readIssue = io.axi.ar.fire
  val busIssue = readIssue || writeComplete
  pendingQueue.io.deq.ready := dropYoung || readIssue || writeComplete
  issuedQueue.io.enq.valid := busIssue
  issuedQueue.io.enq.bits.tag := pending.tag
  issuedQueue.io.enq.bits.write := pendingPayload.storeEnable
  issuedQueue.io.enq.bits.addr := pendingPayload.aluResult(addrWidth - 1, 0)
  issuedQueue.io.enq.bits.accessType := pendingPayload.funct3
  issuedQueue.io.enq.bits.serviceStartCycle := io.cycle
  issuedQueue.io.enq.bits.queueCycles := io.cycle - pendingPayload.perfMemoryQueueStartCycle

  when(writeComplete) {
    writeAwSent := false.B
    writeWSent := false.B
  }.otherwise {
    when(awFire) { writeAwSent := true.B }
    when(wFire) { writeWSent := true.B }
  }

  val issued = issuedQueue.io.deq.bits
  // 错位 fault 需先排在全部已发请求之后，才能与正常 AXI 响应共用一次回填端口。
  val faultCompletion = pendingValid && pendingFault && !issuedQueue.io.deq.valid
  val arithmeticSelected = io.arithmeticCompletion(0).valid

  val headFault = entryValid(headIndex) && entryComplete(headIndex) && entryFault(headIndex)
  val faultRetire = io.response.fire && !sameCycleRetire && headFault
  // `faultRetire` 只能在时钟边界启动清理，不能反向参与本拍 response.valid，
  // 否则 valid/ready 会形成组合环。
  val droppingNow = dropYoung
  val arithmeticFire = arithmeticSelected && !faultCompletion && !droppingNow
  io.arithmeticCompletion(0).ready := droppingNow || arithmeticFire
  io.arithmeticCompletion(1).ready := true.B

  val allowBusResponse = !faultCompletion && !arithmeticSelected && !droppingNow
  io.axi.r.ready := issuedQueue.io.deq.valid && !issued.write && (allowBusResponse || dropYoung)
  io.axi.b.ready := issuedQueue.io.deq.valid && issued.write && (allowBusResponse || dropYoung)
  val readResponse = io.axi.r.fire
  val writeResponse = io.axi.b.fire
  val busResponse = readResponse || writeResponse
  issuedQueue.io.deq.ready := dropYoung || busResponse

  val memoryCompletion = faultCompletion || busResponse
  val memoryCompletionTag = Mux(faultCompletion, pending.tag, issued.tag)
  val memoryCompletionFault = Mux(faultCompletion, true.B,
    Mux(issued.write, io.axi.b.bits.resp =/= AxiLiteResp.OKAY, io.axi.r.bits.resp =/= AxiLiteResp.OKAY))
  val memoryCompletionReason = Mux(faultCompletion,
    Mux(pendingMisaligned, MemoryFaultReason.misaligned, MemoryFaultReason.crossBeat),
    Mux(issued.write, MemoryFaultReason.writeResponse, MemoryFaultReason.readResponse))
  val memoryCompletionData = Mux(issued.write, 0.U(cfg.xlen.W),
    AxiLiteLoadUnpack.unpack(io.axi.r.bits.data, issued.addr(beatOffsetBits - 1, 0),
      issued.accessType, dataWidth))
  val memoryCompletionStart = Mux(faultCompletion, io.cycle, issued.serviceStartCycle)
  val memoryCompletionQueueCycles = Mux(faultCompletion,
    io.cycle - pendingPayload.perfMemoryQueueStartCycle, issued.queueCycles)
  val memoryCompletionCycles = Mux(faultCompletion, 0.U, io.cycle - issued.serviceStartCycle)

  val responsePayload = Wire(new ExecuteMemoryPayload(cfg))
  responsePayload := entryPayload(headIndex)
  when(sameCycleRetire) { responsePayload := io.request.bits }
  val responseArithmetic = !sameCycleRetire && entryArithmetic(headIndex)
  val responseFault = !sameCycleRetire && entryFault(headIndex)
  val responseIllegal = !sameCycleRetire && entryIllegal(headIndex)
  val responseMemory = isMemory(responsePayload)
  val responseAluResult = Mux(responseArithmetic, entryResult(headIndex), responsePayload.aluResult)
  val responseData = Mux(responsePayload.writebackFromMemory, entryLoadData(headIndex), responseAluResult)
  val responseFaultCause = Mux(responsePayload.storeEnable,
    Mux(entryFaultReason(headIndex) === MemoryFaultReason.misaligned ||
      entryFaultReason(headIndex) === MemoryFaultReason.crossBeat,
      CsrCause.misalignedStore.U(cfg.xlen.W), CsrCause.storeAccess.U(cfg.xlen.W)),
    Mux(entryFaultReason(headIndex) === MemoryFaultReason.misaligned ||
      entryFaultReason(headIndex) === MemoryFaultReason.crossBeat,
      CsrCause.misalignedLoad.U(cfg.xlen.W), CsrCause.loadAccess.U(cfg.xlen.W)))
  val branchNextPc = Mux(responsePayload.branchTaken === 2.U,
    responsePayload.jalrTarget, responsePayload.branchTarget)

  io.response.valid := sameCycleRetire ||
    (entryValid(headIndex) && entryComplete(headIndex) && !droppingNow)
  io.response.bits := 0.U.asTypeOf(new MemoryWritebackPayload(cfg))
  io.response.bits.pc := responsePayload.pc
  io.response.bits.instruction := responsePayload.instruction
  io.response.bits.perfFetchStartCycle := responsePayload.perfFetchStartCycle
  io.response.bits.perfFetchCycles := responsePayload.perfFetchCycles
  io.response.bits.perfDecodeStartCycle := responsePayload.perfDecodeStartCycle
  io.response.bits.perfDecodeCycles := responsePayload.perfDecodeCycles
  io.response.bits.perfExecuteStartCycle := responsePayload.perfExecuteStartCycle
  io.response.bits.perfExecuteCycles := responsePayload.perfExecuteCycles
  io.response.bits.perfMemoryStartCycle := Mux(responseMemory, entryServiceStart(headIndex), responsePayload.perfMemoryStartCycle)
  io.response.bits.perfMemoryCycles := Mux(responseMemory, entryServiceCycles(headIndex), 0.U)
  io.response.bits.perfMemoryQueueStartCycle := Mux(responseMemory,
    responsePayload.perfMemoryQueueStartCycle, responsePayload.perfMemoryStartCycle)
  io.response.bits.perfMemoryServiceStartCycle := Mux(responseMemory,
    entryServiceStart(headIndex), responsePayload.perfMemoryStartCycle)
  io.response.bits.perfMemoryQueueCycles := Mux(responseMemory, entryQueueCycles(headIndex), 0.U)
  io.response.bits.perfMemoryServiceCycles := Mux(responseMemory, entryServiceCycles(headIndex), 0.U)
  io.response.bits.perfWritebackStartCycle := io.cycle
  io.response.bits.nextPc := Mux(responsePayload.branch && responsePayload.branchTaken =/= 0.U,
    branchNextPc, responsePayload.pc + 4.U)
  io.response.bits.rd := responsePayload.rd
  io.response.bits.aluResult := responseAluResult
  io.response.bits.storeData := responsePayload.storeData
  io.response.bits.storeEnable := responsePayload.storeEnable
  io.response.bits.storeAccessType := responsePayload.funct3
  io.response.bits.loadData := responseData
  io.response.bits.csrReadData := responsePayload.csrReadData
  io.response.bits.writebackFromMemory := responsePayload.writebackFromMemory
  io.response.bits.registerWriteEnable := responsePayload.registerWriteEnable && !responseFault && !responseIllegal
  io.response.bits.csrReadWritebackEnable := responsePayload.csrReadWritebackEnable
  io.response.bits.csrAddress := responsePayload.csrAddress
  io.response.bits.csrWriteEnable := responsePayload.csrWriteEnable
  io.response.bits.csrWriteData := responsePayload.csrWriteData
  io.response.bits.csrAccessAllowed := responsePayload.csrAccessAllowed
  io.response.bits.trapEnable := responsePayload.trapEnable || responseFault || responseIllegal
  io.response.bits.trapCause := Mux(responseFault, responseFaultCause,
    Mux(responseIllegal, CsrCause.illegalInstruction.U(cfg.xlen.W), responsePayload.trapCause))
  io.response.bits.trapEpc := Mux(responseFault || responseIllegal, responsePayload.pc,
    responsePayload.trapEpc)
  io.response.bits.mretEnable := responsePayload.mretEnable

  val selectedArithmeticCompletion = Wire(new PipelinedArithmeticCompletion(cfg.xlen))
  selectedArithmeticCompletion := io.arithmeticCompletion(0).bits

  for (offset <- 0 until outstandingDepth) {
    val index = newestIndex(offset)
    val payload = entryPayload(index)
    val faultOrIllegal = entryFault(index) || entryIllegal(index)
    // R/B 或算术端点在本拍握手时，结果已经稳定。把它直接作为该完成槽位的
    // 前递值，能让紧随其后的相关指令在下一拍进入 EX；槽位仍要到时钟边界才
    // 允许按序退休，因此不会改变异常或提交的可见顺序。
    val memoryCompletesThisSlot = memoryCompletion && memoryCompletionTag === index &&
      !memoryCompletionFault
    val arithmeticCompletesThisSlot = arithmeticFire &&
      selectedArithmeticCompletion.tag === index && !selectedArithmeticCompletion.illegal
    val savedData = Mux(payload.csrReadWritebackEnable, payload.csrReadData,
      Mux(payload.writebackFromMemory, entryLoadData(index),
        Mux(entryArithmetic(index), entryResult(index), payload.aluResult)))
    io.completionCandidates(offset).valid := entryCount > offset.U
    io.completionCandidates(offset).writesRd := payload.registerWriteEnable && !faultOrIllegal
    io.completionCandidates(offset).rd := payload.rd
    io.completionCandidates(offset).data := Mux(memoryCompletesThisSlot, memoryCompletionData,
      Mux(arithmeticCompletesThisSlot, selectedArithmeticCompletion.result, savedData))
    io.completionCandidates(offset).dataValid := entryComplete(index) || memoryCompletesThisSlot ||
      arithmeticCompletesThisSlot
  }

  when(allocate) {
    entryPayload(tailIndex) := allocatePayload
    entryValid(tailIndex) := true.B
    entryComplete(tailIndex) := !allocateMemory && !arithmeticAllocate
    entryArithmetic(tailIndex) := arithmeticAllocate
    entryLoadData(tailIndex) := 0.U
    entryResult(tailIndex) := allocatePayload.aluResult
    entryIllegal(tailIndex) := false.B
    entryFault(tailIndex) := false.B
    entryFaultReason(tailIndex) := 0.U
    entryServiceStart(tailIndex) := allocatePayload.perfMemoryStartCycle
    entryQueueCycles(tailIndex) := 0.U
    entryServiceCycles(tailIndex) := 0.U
    tailIndex := increment(tailIndex)
  }
  when(memoryCompletion && !droppingNow) {
    entryComplete(memoryCompletionTag) := true.B
    entryLoadData(memoryCompletionTag) := memoryCompletionData
    entryFault(memoryCompletionTag) := memoryCompletionFault
    entryFaultReason(memoryCompletionTag) := memoryCompletionReason
    entryServiceStart(memoryCompletionTag) := memoryCompletionStart
    entryQueueCycles(memoryCompletionTag) := memoryCompletionQueueCycles
    entryServiceCycles(memoryCompletionTag) := memoryCompletionCycles
    when(memoryCompletionFault) {
      faultPending := true.B
      faultAddrReg := Mux(faultCompletion, pendingPayload.aluResult(addrWidth - 1, 0), issued.addr)
      faultWriteReg := Mux(faultCompletion, pendingPayload.storeEnable, issued.write)
      faultLenReg := Mux(faultCompletion, accessBytes(pendingPayload.funct3), accessBytes(issued.accessType))
      faultReasonReg := memoryCompletionReason
    }
  }
  when(arithmeticFire) {
    entryComplete(selectedArithmeticCompletion.tag) := true.B
    entryResult(selectedArithmeticCompletion.tag) := selectedArithmeticCompletion.result
    entryIllegal(selectedArithmeticCompletion.tag) := selectedArithmeticCompletion.illegal
  }

  val mulDivAllocate = arithmeticAllocate
  val mulDivComplete = io.arithmeticCompletion(0).fire
  when(mulDivAllocate =/= mulDivComplete) {
    mulDivInFlight := Mux(mulDivAllocate, mulDivInFlight + 1.U, mulDivInFlight - 1.U)
  }

  val tableRetire = io.response.fire && !sameCycleRetire
  when(tableRetire) {
    entryValid(headIndex) := false.B
    headIndex := increment(headIndex)
  }
  when(allocate && !tableRetire) {
    entryCount := entryCount + 1.U
  }.elsewhen(!allocate && tableRetire) {
    entryCount := entryCount - 1.U
  }

  when(faultRetire) {
    // fault 已在环首退休，年轻项不再具有架构可见性，tag 仍保留到端点响应被吸收。
    dropYoung := true.B
    faultPending := false.B
    entryCount := 0.U
    for (index <- 0 until outstandingDepth) {
      entryValid(index) := false.B
      entryComplete(index) := false.B
    }
  }
  when(dropYoung && !pendingQueue.io.deq.valid && !issuedQueue.io.deq.valid &&
    !mulDivInFlight.orR && !writeAwSent && !writeWSent) {
    dropYoung := false.B
    headIndex := 0.U
    tailIndex := 0.U
  }

  io.fault.valid := faultPending
  io.fault.addr := faultAddrReg
  io.fault.write := faultWriteReg
  io.fault.len := faultLenReg
  io.fault.reason := faultReasonReg
  io.busy := entryCount =/= 0.U || pendingQueue.io.deq.valid || issuedQueue.io.deq.valid ||
    faultPending || dropYoung || mulDivInFlight.orR || writeAwSent || writeWSent
  io.drained := !io.busy
  // `pendingQueue` 开启 flow 后，deq.valid 可以组合地反映当前 EX/MEM 请求；该请求
  // 不是比 ID/EX 旁路候选更老的历史项，不能让其反向参与后端的 ready 判定。
  io.retirementDrained := entryCount === 0.U && !faultPending && !dropYoung &&
    !mulDivInFlight.orR && !writeAwSent && !writeWSent
}

/**
  * 两拍缓存 MEM 阶段的公开封装。
  *
  * 默认实例保持历史按序 FIFO 行为；只有显式开启完成表旁路时才实例化可并发完成的
  * 四项环，从而使其他流水线、FPGA 和 SoC 的生成 RTL 保持不变。
  */
class PipelinedMemoryStage(
  addrWidth: Int = 32,
  dataWidth: Int = 64,
  mainMemoryBase: Long = 0x80000000L,
  mainMemorySize: Long = 0x08000000L,
  val outstandingDepth: Int = 4,
  cfg: ISAConfig = ISAConfig(),
  enableOutstandingCompletionForwarding: Boolean = false
) extends Module {
  val io = IO(new PipelinedMemoryStageIO(addrWidth, dataWidth, outstandingDepth, cfg))

  if (enableOutstandingCompletionForwarding) {
    val implementation = Module(new OutstandingPipelinedMemoryStage(addrWidth, dataWidth,
      mainMemoryBase, mainMemorySize, outstandingDepth, cfg))
    implementation.io.request.valid := io.request.valid
    implementation.io.request.bits := io.request.bits
    io.request.ready := implementation.io.request.ready
    implementation.io.arithmeticRequest.valid := io.arithmeticRequest.valid
    implementation.io.arithmeticRequest.bits := io.arithmeticRequest.bits
    io.arithmeticRequest.ready := implementation.io.arithmeticRequest.ready
    io.arithmeticAllocateTag := implementation.io.arithmeticAllocateTag
    io.arithmeticSlotAvailable := implementation.io.arithmeticSlotAvailable
    for (index <- 0 until 2) {
      implementation.io.arithmeticCompletion(index).valid := io.arithmeticCompletion(index).valid
      implementation.io.arithmeticCompletion(index).bits := io.arithmeticCompletion(index).bits
      io.arithmeticCompletion(index).ready := implementation.io.arithmeticCompletion(index).ready
    }
    io.completionCandidates := implementation.io.completionCandidates
    io.response.valid := implementation.io.response.valid
    io.response.bits := implementation.io.response.bits
    implementation.io.response.ready := io.response.ready
    implementation.io.cycle := io.cycle
    implementation.io.flush := io.flush
    io.axi <> implementation.io.axi
    io.fault := implementation.io.fault
    io.busy := implementation.io.busy
    io.drained := implementation.io.drained
    io.retirementDrained := implementation.io.retirementDrained
  } else {
    val implementation = Module(new LegacyPipelinedMemoryStage(addrWidth, dataWidth,
      mainMemoryBase, mainMemorySize, outstandingDepth, cfg))
    implementation.io.request.valid := io.request.valid
    implementation.io.request.bits := io.request.bits
    io.request.ready := implementation.io.request.ready
    io.arithmeticRequest.ready := false.B
    io.arithmeticAllocateTag := 0.U
    io.arithmeticSlotAvailable := false.B
    for (index <- 0 until 2) {
      io.arithmeticCompletion(index).ready := true.B
    }
    io.completionCandidates := VecInit(Seq.fill(outstandingDepth)(0.U.asTypeOf(
      new OutstandingCompletionCandidate(cfg.xlen))))
    io.response.valid := implementation.io.response.valid
    io.response.bits := implementation.io.response.bits
    implementation.io.response.ready := io.response.ready
    implementation.io.cycle := io.cycle
    implementation.io.flush := io.flush
    io.axi <> implementation.io.axi
    io.fault := implementation.io.fault
    io.busy := implementation.io.busy
    io.drained := implementation.io.drained
    io.retirementDrained := implementation.io.retirementDrained
  }
}
