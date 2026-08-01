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
  val write = Bool()
  val addr = UInt(addrWidth.W)
  val accessType = UInt(3.W)
  val serviceStartCycle = UInt(64.W)
  val queueCycles = UInt(64.W)
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
class PipelinedMemoryStage(
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
    dst.floatRegisterWriteEnable := order.floatRegisterWriteEnable && !responseFault
    dst.floatingInstruction := order.floatingInstruction && !responseFault
    dst.floatingExceptionFlags := order.floatingExceptionFlags
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
}
