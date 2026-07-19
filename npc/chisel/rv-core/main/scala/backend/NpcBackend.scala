package scpu

import chisel3._
import chisel3.util._
import scpu.protocol._

/** 供流水算术端点接受的一条操作所使用的按序退休槽。
  * 结果按 tag 回填；架构释放严格遵循队首顺序。
  */
class ArithmeticCompletionEntry(cfg: ISAConfig) extends Bundle {
  val payload = new DecodeExecutePayload(cfg)
  val result = UInt(cfg.xlen.W)
  val exceptionFlags = UInt(5.W)
  val illegal = Bool()
  val completed = Bool()
}

/** NPC 的按序架构后端。
  *
  * 派发请求刻意不携带操作数值；寄存器读取、RAW 冒险检测、执行、访存顺序和
  * 架构提交都归本模块所有。
  */
class NpcBackend(
  config: NpcConfig,
  components: NpcCoreComponents = SimulationCoreComponents
) extends Module {
  private val cfg = config.isa
  private val pipelineConfig = config.pipeline
  private val operatorConfig = config.operators
  private val debugEnabled = config.debug.enableTopDebugIo
  private val axiConfig = config.axi
  private val arithmeticTagWidth =
    if (cfg.M) operatorConfig.mulDiv.tagWidth
    else if (cfg.F) operatorConfig.floating.tagWidth
    else 1
  private val arithmeticQueueDepth = 1 << arithmeticTagWidth
  require(arithmeticTagWidth <= 8,
    s"Arithmetic tagWidth must be <= 8 so the in-order completion queue remains bounded, got $arithmeticTagWidth")
  require(!cfg.M || !cfg.F || operatorConfig.mulDiv.tagWidth == operatorConfig.floating.tagWidth,
    "Integer and floating arithmetic endpoints must use the same tagWidth")

  val io = IO(new Bundle {
    val interrupt = Input(Bool())
    val dispatch = Flipped(Decoupled(new DecodedDispatchPayload(cfg)))
    val axi = new AxiLiteMasterIO(axiConfig.addrWidth, axiConfig.dataWidth)
    val redirectValid = Output(Bool())
    val redirectTarget = Output(UInt(cfg.xlen.W))
    val arithmeticAssist = if (cfg.F && components.exposesArithmeticAssist(config)) {
      Some(new ArithmeticAssistPort(cfg.xlen))
    } else None

    val debug = Output(new NpcBackendDebugBundle(cfg))
  })

  val decodeExecuteReg = Module(new PipelineRegister(new DecodeExecutePayload(cfg)))
  val executeMemoryReg = Module(new PipelineRegister(new ExecuteMemoryPayload(cfg)))
  val memoryWritebackReg = Module(new PipelineRegister(new MemoryWritebackPayload(cfg)))
  val arithmeticEntries = Reg(Vec(arithmeticQueueDepth, new ArithmeticCompletionEntry(cfg)))
  val arithmeticValid = RegInit(VecInit(Seq.fill(arithmeticQueueDepth)(false.B)))
  val arithmeticHead = RegInit(0.U(arithmeticTagWidth.W))
  val arithmeticTail = RegInit(0.U(arithmeticTagWidth.W))
  val arithmeticCount = RegInit(0.U((arithmeticTagWidth + 1).W))
  val redirectBarrier = RegInit(false.B)

  val debugCycleCounter = if (debugEnabled) Some(RegInit(0.U(64.W))) else None
  debugCycleCounter.foreach(counter => counter := counter + 1.U)
  val performanceCycle = debugCycleCounter.getOrElse(0.U(64.W))

  val executeRedirectValid = WireDefault(false.B)
  val executeRedirectTarget = WireDefault(0.U(cfg.xlen.W))
  val commitRedirectValid = WireDefault(false.B)
  val commitRedirectTarget = WireDefault(0.U(cfg.xlen.W))
  val frontendRedirectValid = commitRedirectValid || executeRedirectValid
  val frontendRedirectTarget = Mux(commitRedirectValid, commitRedirectTarget, executeRedirectTarget)

  val registerFile = Module(new RegisterFile(width = cfg.xlen, debug = true))
  val floatingRegisterFile = if (cfg.F) Some(Module(new FloatingRegisterFile(cfg.xlen))) else None
  val integerAlu = Module(new IntegerAlu(cfg.xlen))
  val mulDivAlu = if (cfg.M) Some(components.makeMulDivAlu(cfg.xlen, operatorConfig.mulDiv)) else None
  val floatingAlu = if (cfg.F) Some(components.makeFloatingAlu(cfg.xlen, operatorConfig.floating)) else None
  val arithmeticAssistBusy = floatingAlu.map(_.io.assist.busy).getOrElse(false.B)
  private val arithmeticResponseSourceCount = (if (cfg.M) 1 else 0) + (if (cfg.F) 1 else 0)
  // 各 ISA ALU 在内部选择并汇聚纯算子；后端只需仲裁独立发射的 ISA 扩展。
  val arithmeticResponseArbiter =
    if (arithmeticResponseSourceCount > 0) Some(Module(new RRArbiter(
      new ArithmeticResponse(cfg.xlen, arithmeticTagWidth), arithmeticResponseSourceCount)))
    else None
  val csrExecution = Module(new CsrExecution(cfg))
  val csrFile = Module(new CsrFile(cfg))
  val loadStoreUnit = Module(new LSUAXIAdapter(axiConfig.addrWidth, axiConfig.dataWidth))
  loadStoreUnit.io.axi <> io.axi

  val dispatch = io.dispatch.bits
  registerFile.io.rs1 := dispatch.rs1
  registerFile.io.rs2 := dispatch.rs2
  val floatingRs1Data = WireDefault(0.U(cfg.xlen.W))
  val floatingRs2Data = WireDefault(0.U(cfg.xlen.W))
  val floatingRs3Data = WireDefault(0.U(cfg.xlen.W))
  floatingRegisterFile.foreach { fp =>
    fp.io.rs1 := dispatch.rs1
    fp.io.rs2 := dispatch.rs2
    fp.io.rs3 := dispatch.rs3
    floatingRs1Data := fp.io.rs1Data
    floatingRs2Data := fp.io.rs2Data
    floatingRs3Data := fp.io.rs3Data
  }

  val executeIdle :: executeDone :: Nil = Enum(2)
  val executeState = RegInit(executeIdle)
  val executeRequestReg = Reg(new DecodeExecutePayload(cfg))
  val memoryIdle :: memoryWait :: Nil = Enum(2)
  val memoryState = RegInit(memoryIdle)
  val memoryRequestReg = Reg(new ExecuteMemoryPayload(cfg))

  val executeInput = decodeExecuteReg.io.out.bits
  val executeInputFloatingDisabled = (executeInput.floatingInstruction ||
    (executeInput.csrEnable && CsrAccess.isFloatingAddress(executeInput.csrAddress))) && !csrFile.io.fEnabled
  val executeInputFloatingTrap = executeInputFloatingDisabled ||
    CsrAccess.hasInvalidFloatingRounding(executeInput.floatingOperation, executeInput.aluCtrl,
      executeInput.funct3, csrFile.io.frmOut)
  val executeInputIsArithmetic = executeInput.executionUnit === NpcExecutionUnit.multiply ||
    executeInput.executionUnit === NpcExecutionUnit.divide ||
    (executeInput.executionUnit === NpcExecutionUnit.floating && !executeInputFloatingTrap)
  val executeInputIsSerial = executeInput.csrEnable || executeInput.trapEnable || executeInput.mretEnable ||
    executeInputFloatingTrap
  val pipelineMode = pipelineConfig.enablePipeline.B
  val olderInstructionsDrained = !executeMemoryReg.io.out.valid && memoryState === memoryIdle &&
    !memoryWritebackReg.io.out.valid && !loadStoreUnit.io.busy
  val arithmeticUnitReady = Mux(
    executeInput.executionUnit === NpcExecutionUnit.multiply || executeInput.executionUnit === NpcExecutionUnit.divide,
    mulDivAlu.map(_.io.req.ready).getOrElse(false.B),
    Mux(executeInput.executionUnit === NpcExecutionUnit.floating,
      floatingAlu.map(_.io.req.ready).getOrElse(false.B), false.B)
  )
  val arithmeticQueueNonEmpty = arithmeticCount =/= 0.U
  val arithmeticQueueHasSpace = arithmeticCount =/= arithmeticQueueDepth.U
  val arithmeticCanAccept = executeState === executeIdle && arithmeticQueueHasSpace && arithmeticUnitReady &&
    (arithmeticQueueNonEmpty || olderInstructionsDrained)
  val serialCanAccept = executeState === executeIdle && !arithmeticQueueNonEmpty &&
    (!pipelineMode || olderInstructionsDrained)
  val directCanAccept = executeState === executeIdle && !arithmeticQueueNonEmpty && executeMemoryReg.io.in.ready
  val directExecuteWillFire = pipelineMode && decodeExecuteReg.io.out.valid &&
    !executeInputIsSerial && !executeInputIsArithmetic && directCanAccept

  // 下方组装 EX/WB 数据通路后这些值才有具体连接。在此声明连线可使候选项优先级
  // 独立于数据通路在源码中的书写顺序。
  val directForwardData = WireDefault(0.U(cfg.xlen.W))
  val serialForwardData = WireDefault(0.U(cfg.xlen.W))
  val commitForwardData = WireDefault(0.U(cfg.xlen.W))
  val executeMemoryForwardData = Mux(executeMemoryReg.io.out.bits.csrReadWritebackEnable,
    executeMemoryReg.io.out.bits.csrReadData, executeMemoryReg.io.out.bits.aluResult)
  val memoryResponseAvailable = memoryState === memoryWait && !loadStoreUnit.io.busy &&
    memoryWritebackReg.io.in.ready

  val forwardingUnit = Module(new ForwardingUnit(cfg.xlen))
  forwardingUnit.io.enableIdForwarding := pipelineConfig.forwarding.enableIdForwarding.B
  forwardingUnit.io.enableExecuteForwarding := pipelineConfig.forwarding.enableExecuteForwarding.B
  forwardingUnit.io.idRs1 := dispatch.rs1
  forwardingUnit.io.idRs2 := dispatch.rs2
  forwardingUnit.io.idUsesRs1 := dispatch.usesRs1
  forwardingUnit.io.idUsesRs2 := dispatch.usesRs2
  forwardingUnit.io.idRs1Data := registerFile.io.rs1Data
  forwardingUnit.io.idRs2Data := registerFile.io.rs2Data
  forwardingUnit.io.executeRs1 := executeInput.rs1
  forwardingUnit.io.executeRs2 := executeInput.rs2
  forwardingUnit.io.executeUsesRs1 := executeInput.usesRs1
  forwardingUnit.io.executeUsesRs2 := executeInput.usesRs2
  forwardingUnit.io.executeRs1Data := executeInput.rs1Data
  forwardingUnit.io.executeRs2Data := executeInput.storeData

  val hazardUnit = Module(new HazardUnit)
  hazardUnit.io.enableInterlock := pipelineConfig.enableInterlock.B
  hazardUnit.io.enableIdForwarding := pipelineConfig.forwarding.enableIdForwarding.B
  hazardUnit.io.enableExecuteForwarding := pipelineConfig.forwarding.enableExecuteForwarding.B
  hazardUnit.io.usesRs1 := dispatch.usesRs1
  hazardUnit.io.usesRs2 := dispatch.usesRs2
  hazardUnit.io.rs1 := dispatch.rs1
  hazardUnit.io.rs2 := dispatch.rs2

  val producerValid = Seq(
    decodeExecuteReg.io.out.valid,
    executeState =/= executeIdle,
    executeMemoryReg.io.out.valid,
    memoryState === memoryWait,
    memoryWritebackReg.io.out.valid
  )
  val producerWritesRd = Seq(
    decodeExecuteReg.io.out.bits.registerWriteEnable,
    executeRequestReg.registerWriteEnable,
    executeMemoryReg.io.out.bits.registerWriteEnable,
    memoryRequestReg.registerWriteEnable,
    memoryWritebackReg.io.out.bits.registerWriteEnable
  )
  val producerRd = Seq(
    decodeExecuteReg.io.out.bits.rd,
    executeRequestReg.rd,
    executeMemoryReg.io.out.bits.rd,
    memoryRequestReg.rd,
    memoryWritebackReg.io.out.bits.rd
  )
  val producerWritesFloatingRd = Seq(
    decodeExecuteReg.io.out.bits.floatRegisterWriteEnable,
    executeRequestReg.floatRegisterWriteEnable,
    executeMemoryReg.io.out.bits.floatRegisterWriteEnable,
    memoryRequestReg.floatRegisterWriteEnable,
    memoryWritebackReg.io.out.bits.floatRegisterWriteEnable
  )

  val idCandidateAvailable = Seq(
    directExecuteWillFire && executeInput.executionUnit === NpcExecutionUnit.integer && !executeInput.writebackFromMemory,
    executeState === executeDone,
    !executeMemoryReg.io.out.bits.writebackFromMemory,
    memoryResponseAvailable,
    true.B
  )
  val dispatchIsArithmetic = dispatch.executionUnit === NpcExecutionUnit.multiply ||
    dispatch.executionUnit === NpcExecutionUnit.divide || dispatch.executionUnit === NpcExecutionUnit.floating
  val dispatchIsSerial = dispatchIsArithmetic || dispatch.csrEnable || dispatch.trapEnable || dispatch.mretEnable
  val currentDecodeSlotCanAdvance = !decodeExecuteReg.io.out.valid ||
    (executeInputIsArithmetic && arithmeticCanAccept) ||
    (!executeInputIsSerial && !executeInputIsArithmetic && directCanAccept)
  val incomingCanExecuteDirectNextCycle = pipelineMode && !dispatchIsSerial &&
    executeState === executeIdle && currentDecodeSlotCanAdvance
  val executeForwardNextCycleAvailable = Seq(
    incomingCanExecuteDirectNextCycle && directExecuteWillFire &&
      executeInput.executionUnit === NpcExecutionUnit.integer && !executeInput.writebackFromMemory,
    incomingCanExecuteDirectNextCycle && executeState === executeDone && executeMemoryReg.io.in.ready,
    incomingCanExecuteDirectNextCycle && !executeMemoryReg.io.out.bits.writebackFromMemory,
    incomingCanExecuteDirectNextCycle && memoryResponseAvailable,
    false.B
  )
  val idCandidateData = Seq(
    directForwardData,
    serialForwardData,
    executeMemoryForwardData,
    loadStoreUnit.io.rdata,
    commitForwardData
  )

  def driveCandidate(
    candidate: ForwardingCandidate,
    valid: Bool,
    writesRd: Bool,
    rd: UInt,
    data: UInt,
    dataValid: Bool
  ): Unit = {
    candidate.valid := valid
    candidate.writesRd := writesRd
    candidate.rd := rd
    candidate.data := data
    candidate.dataValid := dataValid
  }

  for (index <- 0 until 5) {
    hazardUnit.io.producers(index).valid := producerValid(index)
    hazardUnit.io.producers(index).writesRd := producerWritesRd(index)
    hazardUnit.io.producers(index).rd := producerRd(index)
    hazardUnit.io.producers(index).idForwardAvailable := idCandidateAvailable(index)
    hazardUnit.io.producers(index).executeForwardNextCycleAvailable :=
      executeForwardNextCycleAvailable(index)
    driveCandidate(
      forwardingUnit.io.idCandidates(index),
      producerValid(index),
      producerWritesRd(index),
      producerRd(index),
      idCandidateData(index),
      idCandidateAvailable(index)
    )
  }
  // 在 EX，槽位零就是当前消费指令；更老结果保持与 ID 候选相同的由新到旧优先级。
  driveCandidate(forwardingUnit.io.executeCandidates(0), false.B, false.B, 0.U,
    0.U(cfg.xlen.W), false.B)
  // 串行操作不能与直接 EX 消费者重叠。将槽位一排除在该 mux 外，也避免串行 ALU
  // 结果组合地反馈到直接 ALU；其完成值仍可在 ID 使用。
  driveCandidate(forwardingUnit.io.executeCandidates(1), false.B, false.B, 0.U,
    0.U(cfg.xlen.W), false.B)
  for (index <- 2 until 5) {
    driveCandidate(
      forwardingUnit.io.executeCandidates(index),
      producerValid(index),
      producerWritesRd(index),
      producerRd(index),
      idCandidateData(index),
      idCandidateAvailable(index)
    )
  }
  def floatingSourceHazard(source: UInt, used: Bool): Bool =
    used && producerValid.zip(producerWritesFloatingRd).zip(producerRd).map {
      case ((valid, writesRd), rd) => valid && writesRd && rd === source
    }.reduce(_ || _)
  val floatingRawHazard = floatingSourceHazard(dispatch.rs1, dispatch.usesFrs1) ||
    floatingSourceHazard(dispatch.rs2, dispatch.usesFrs2) ||
    floatingSourceHazard(dispatch.rs3, dispatch.usesFrs3)
  def arithmeticSourceHazard(
    source: UInt,
    used: Bool,
    writes: ArithmeticCompletionEntry => Bool,
    zeroRegisterIsImmutable: Boolean
  ): Bool = {
    val sourceCanCarryDependency = if (zeroRegisterIsImmutable) source =/= 0.U else true.B
    used && sourceCanCarryDependency && arithmeticValid.zip(arithmeticEntries).map {
      case (valid, entry) =>
        val destinationCanCarryDependency = if (zeroRegisterIsImmutable) entry.payload.rd =/= 0.U else true.B
        valid && writes(entry) && destinationCanCarryDependency && entry.payload.rd === source
    }.reduce(_ || _)
  }
  val arithmeticIntegerRawHazard =
    arithmeticSourceHazard(dispatch.rs1, dispatch.usesRs1, _.payload.registerWriteEnable,
      zeroRegisterIsImmutable = true) ||
      arithmeticSourceHazard(dispatch.rs2, dispatch.usesRs2, _.payload.registerWriteEnable,
        zeroRegisterIsImmutable = true)
  val arithmeticFloatingRawHazard =
    arithmeticSourceHazard(dispatch.rs1, dispatch.usesFrs1, _.payload.floatRegisterWriteEnable,
      zeroRegisterIsImmutable = false) ||
    arithmeticSourceHazard(dispatch.rs2, dispatch.usesFrs2, _.payload.floatRegisterWriteEnable,
      zeroRegisterIsImmutable = false) ||
    arithmeticSourceHazard(dispatch.rs3, dispatch.usesFrs3, _.payload.floatRegisterWriteEnable,
      zeroRegisterIsImmutable = false)

  val busyAfterDecode = decodeExecuteReg.io.out.valid ||
    arithmeticQueueNonEmpty || (executeState =/= executeIdle) || executeMemoryReg.io.out.valid ||
    (memoryState =/= memoryIdle) || memoryWritebackReg.io.out.valid || loadStoreUnit.io.busy
  val decodeCanIssue = !arithmeticAssistBusy && Mux(
    pipelineConfig.enablePipeline.B,
    !hazardUnit.io.stall && !floatingRawHazard && !arithmeticIntegerRawHazard &&
      !arithmeticFloatingRawHazard && !redirectBarrier && !frontendRedirectValid,
    !busyAfterDecode && !frontendRedirectValid
  )
  decodeExecuteReg.io.flush := frontendRedirectValid
  decodeExecuteReg.io.in.valid := io.dispatch.valid && decodeCanIssue
  io.dispatch.ready := decodeExecuteReg.io.in.ready && decodeCanIssue
  decodeExecuteReg.io.in.bits.pc := dispatch.pc
  decodeExecuteReg.io.in.bits.instruction := dispatch.instruction
  decodeExecuteReg.io.in.bits.perfFetchCycles := dispatch.perfFetchCycles
  decodeExecuteReg.io.in.bits.perfDecodeStartCycle := dispatch.perfDecodeStartCycle
  decodeExecuteReg.io.in.bits.perfDecodeCycles := 0.U
  decodeExecuteReg.io.in.bits.perfExecuteStartCycle := 0.U
  def normalizedFpr(raw: UInt): UInt =
    if (cfg.xlen == 64) Mux(raw(63, 32) === Fill(32, 1.U(1.W)), raw, Cat(Fill(32, 1.U(1.W)), "h7fc00000".U(32.W)))
    else raw
  val rs1FprValue = Mux(dispatch.aluCtrl === NpcAluOp.Floating.FMV_X_W.asUInt, floatingRs1Data, normalizedFpr(floatingRs1Data))
  val rs2FprValue = Mux(dispatch.storeEnable, floatingRs2Data, normalizedFpr(floatingRs2Data))
  decodeExecuteReg.io.in.bits.rs1Data := Mux(dispatch.usesFrs1, rs1FprValue, forwardingUnit.io.idRs1Forwarded)
  decodeExecuteReg.io.in.bits.storeData := Mux(dispatch.usesFrs2, rs2FprValue, forwardingUnit.io.idRs2Forwarded)
  decodeExecuteReg.io.in.bits.operandC := normalizedFpr(floatingRs3Data)
  decodeExecuteReg.io.in.bits.immediate := dispatch.immediate
  decodeExecuteReg.io.in.bits.rd := dispatch.rd
  decodeExecuteReg.io.in.bits.rs1 := dispatch.rs1
  decodeExecuteReg.io.in.bits.rs2 := dispatch.rs2
  decodeExecuteReg.io.in.bits.rs3 := dispatch.rs3
  decodeExecuteReg.io.in.bits.usesRs1 := dispatch.usesRs1
  decodeExecuteReg.io.in.bits.usesRs2 := dispatch.usesRs2
  decodeExecuteReg.io.in.bits.floatingOperation := dispatch.floatingOperation
  decodeExecuteReg.io.in.bits.floatingInstruction := dispatch.floatingInstruction
  decodeExecuteReg.io.in.bits.floatRegisterWriteEnable := dispatch.floatRegisterWriteEnable
  decodeExecuteReg.io.in.bits.usesFrs1 := dispatch.usesFrs1
  decodeExecuteReg.io.in.bits.usesFrs2 := dispatch.usesFrs2
  decodeExecuteReg.io.in.bits.usesFrs3 := dispatch.usesFrs3
  decodeExecuteReg.io.in.bits.funct3 := dispatch.funct3
  decodeExecuteReg.io.in.bits.csrAddress := dispatch.csrAddress
  decodeExecuteReg.io.in.bits.branch := dispatch.branch
  decodeExecuteReg.io.in.bits.loadEnable := dispatch.loadEnable
  decodeExecuteReg.io.in.bits.writebackFromMemory := dispatch.writebackFromMemory
  decodeExecuteReg.io.in.bits.storeEnable := dispatch.storeEnable
  decodeExecuteReg.io.in.bits.useImmediate := dispatch.useImmediate
  decodeExecuteReg.io.in.bits.registerWriteEnable := dispatch.registerWriteEnable
  decodeExecuteReg.io.in.bits.executionUnit := dispatch.executionUnit
  decodeExecuteReg.io.in.bits.aluCtrl := dispatch.aluCtrl
  decodeExecuteReg.io.in.bits.privilegedInstruction := dispatch.privilegedInstruction
  decodeExecuteReg.io.in.bits.trapEnable := dispatch.trapEnable
  decodeExecuteReg.io.in.bits.trapCause := dispatch.trapCause
  decodeExecuteReg.io.in.bits.mretEnable := dispatch.mretEnable
  decodeExecuteReg.io.in.bits.csrEnable := dispatch.csrEnable
  decodeExecuteReg.io.in.bits.csrOperation := dispatch.csrOperation
  decodeExecuteReg.io.in.bits.csrUseImmediate := dispatch.csrUseImmediate
  decodeExecuteReg.io.in.bits.csrReadWritebackEnable := dispatch.csrReadWritebackEnable

  decodeExecuteReg.io.out.ready := Mux(
    executeInputIsArithmetic,
    arithmeticCanAccept,
    Mux(pipelineMode, Mux(executeInputIsSerial, serialCanAccept, directCanAccept), executeState === executeIdle)
  )
  val decodeExecuteFire = decodeExecuteReg.io.out.fire
  val arithmeticIssue = decodeExecuteFire && executeInputIsArithmetic
  val serialExecuteAccept = decodeExecuteFire && !executeInputIsArithmetic &&
    (!pipelineMode || executeInputIsSerial)
  val directExecuteFire = decodeExecuteFire && pipelineMode && !executeInputIsSerial && !executeInputIsArithmetic

  val executeRequest = Wire(new DecodeExecutePayload(cfg))
  executeRequest := executeRequestReg
  when(serialExecuteAccept) { executeRequest := executeInput }
  val serialAluOperandB = Mux(executeRequest.useImmediate, executeRequest.immediate, executeRequest.storeData)
  val directRs1Data = forwardingUnit.io.executeRs1Forwarded
  val directRs2Data = forwardingUnit.io.executeRs2Forwarded
  val directAluOperandB = Mux(executeInput.useImmediate, executeInput.immediate, directRs2Data)
  val arithmeticIssuePayload = Wire(new DecodeExecutePayload(cfg))
  arithmeticIssuePayload := executeInput
  arithmeticIssuePayload.perfDecodeCycles := performanceCycle - executeInput.perfDecodeStartCycle
  arithmeticIssuePayload.perfExecuteStartCycle := performanceCycle
  when(arithmeticIssue) {
    arithmeticValid(arithmeticTail) := true.B
    arithmeticEntries(arithmeticTail).payload := arithmeticIssuePayload
    arithmeticEntries(arithmeticTail).completed := false.B
    arithmeticEntries(arithmeticTail).illegal := false.B
    arithmeticTail := arithmeticTail + 1.U
  }

  def completeArithmetic(tag: UInt, result: UInt, exceptionFlags: UInt, illegal: Bool): Unit = {
    assert(arithmeticValid(tag), "Arithmetic endpoint returned an inactive tag")
    arithmeticEntries(tag).result := result
    arithmeticEntries(tag).exceptionFlags := exceptionFlags
    arithmeticEntries(tag).illegal := illegal
    arithmeticEntries(tag).completed := true.B
  }

  var arithmeticResponseIndex = 0
  def connectArithmeticResponse(source: DecoupledIO[ArithmeticResponse]): Unit = {
    val sink = arithmeticResponseArbiter.get.io.in(arithmeticResponseIndex)
    sink.valid := source.valid
    sink.bits.tag := source.bits.tag
    sink.bits.result := source.bits.result
    sink.bits.exceptionFlags := source.bits.exceptionFlags
    sink.bits.illegal := source.bits.illegal
    source.ready := sink.ready
    arithmeticResponseIndex += 1
  }

  mulDivAlu.foreach { alu =>
    alu.io.req.valid := arithmeticIssue &&
      (executeInput.executionUnit === NpcExecutionUnit.multiply || executeInput.executionUnit === NpcExecutionUnit.divide)
    alu.io.req.bits.operandA := executeInput.rs1Data
    alu.io.req.bits.operandB := Mux(executeInput.useImmediate, executeInput.immediate, executeInput.storeData)
    alu.io.req.bits.operandC := 0.U
    alu.io.req.bits.aluOp := executeInput.aluCtrl
    alu.io.req.bits.roundingMode := 0.U
    alu.io.req.bits.pc := executeInput.pc
    alu.io.req.bits.instruction := executeInput.instruction
    alu.io.req.bits.fcsr := csrFile.io.fcsrOut
    alu.io.req.bits.tag := arithmeticTail
    connectArithmeticResponse(alu.io.resp)
  }
  floatingAlu.foreach { alu =>
    alu.io.req.valid := arithmeticIssue && executeInput.executionUnit === NpcExecutionUnit.floating
    alu.io.req.bits.operandA := executeInput.rs1Data
    alu.io.req.bits.operandB := executeInput.storeData
    alu.io.req.bits.operandC := executeInput.operandC
    alu.io.req.bits.aluOp := executeInput.aluCtrl
    alu.io.req.bits.roundingMode := Mux(executeInput.funct3 === 7.U, csrFile.io.frmOut, executeInput.funct3)
    alu.io.req.bits.pc := executeInput.pc
    alu.io.req.bits.instruction := executeInput.instruction
    alu.io.req.bits.fcsr := csrFile.io.fcsrOut
    alu.io.req.bits.tag := arithmeticTail
    connectArithmeticResponse(alu.io.resp)
  }
  floatingAlu.foreach { alu =>
    io.arithmeticAssist match {
      case Some(external) =>
        external.request.valid := alu.io.assist.request.valid
        external.request.bits := alu.io.assist.request.bits
        alu.io.assist.request.ready := external.request.ready
        alu.io.assist.response.valid := external.response.valid
        alu.io.assist.response.bits := external.response.bits
        external.response.ready := alu.io.assist.response.ready
        external.busy := alu.io.assist.busy
      case None =>
        // Simulation components do not expose a host assist port. Their
        // FloatingAlu ties its outputs off, while the inputs still need
        // deterministic values at this module boundary.
        alu.io.assist.request.ready := true.B
        alu.io.assist.response.valid := false.B
        alu.io.assist.response.bits := 0.U.asTypeOf(alu.io.assist.response.bits)
    }
  }
  arithmeticResponseArbiter.foreach { arbiter =>
    arbiter.io.out.ready := true.B
    when(arbiter.io.out.fire) {
      completeArithmetic(arbiter.io.out.bits.tag, arbiter.io.out.bits.result,
        arbiter.io.out.bits.exceptionFlags, arbiter.io.out.bits.illegal)
    }
  }
  when(serialExecuteAccept) {
    executeRequestReg := executeInput
    executeRequestReg.perfDecodeCycles := performanceCycle - executeInput.perfDecodeStartCycle
    executeRequestReg.perfExecuteStartCycle := performanceCycle
    executeState := executeDone
  }.elsewhen(executeState === executeDone && executeMemoryReg.io.in.fire) {
    executeState := executeIdle
  }

  integerAlu.io.a := Mux(directExecuteFire, directRs1Data, executeRequest.rs1Data)
  integerAlu.io.b := Mux(directExecuteFire, directAluOperandB, serialAluOperandB)
  integerAlu.io.pc := Mux(directExecuteFire, executeInput.pc, executeRequest.pc)
  integerAlu.io.control := Mux(directExecuteFire, executeInput.aluCtrl, executeRequest.aluCtrl)
  csrExecution.io.csrRequestEnable := executeRequest.csrEnable
  csrExecution.io.csrOperation := executeRequest.csrOperation
  csrExecution.io.csrUseImmediate := executeRequest.csrUseImmediate
  val executeRequestFloatingTrap =
    ((executeRequest.floatingInstruction || (executeRequest.csrEnable && CsrAccess.isFloatingAddress(executeRequest.csrAddress))) &&
      !csrFile.io.fEnabled) ||
    CsrAccess.hasInvalidFloatingRounding(executeRequest.floatingOperation, executeRequest.aluCtrl,
      executeRequest.funct3, csrFile.io.frmOut)
  csrExecution.io.trapRequested := executeRequest.trapEnable || executeRequestFloatingTrap
  csrExecution.io.requestedTrapCause := Mux(executeRequestFloatingTrap,
    CsrCause.illegalInstruction.U(cfg.xlen.W), executeRequest.trapCause)
  csrExecution.io.mretRequested := executeRequest.mretEnable
  csrExecution.io.capture := serialExecuteAccept
  csrExecution.io.rs1Data := executeRequest.rs1Data
  csrExecution.io.zimm := executeRequest.rs1
  csrExecution.io.requestedCsrAddress := executeRequest.csrAddress
  csrExecution.io.pc := executeRequest.pc
  csrExecution.io.previousCsrValue := csrFile.io.readData

  val arithmeticHeadReady = arithmeticQueueNonEmpty && arithmeticValid(arithmeticHead) &&
    arithmeticEntries(arithmeticHead).completed
  val arithmeticHeadIllegal = arithmeticHeadReady && arithmeticEntries(arithmeticHead).illegal
  val arithmeticRetireFire = arithmeticHeadReady && executeMemoryReg.io.in.ready
  when(arithmeticRetireFire) {
    arithmeticValid(arithmeticHead) := false.B
    arithmeticHead := arithmeticHead + 1.U
  }
  when(arithmeticIssue && !arithmeticRetireFire) {
    arithmeticCount := arithmeticCount + 1.U
  }.elsewhen(!arithmeticIssue && arithmeticRetireFire) {
    arithmeticCount := arithmeticCount - 1.U
  }

  val executeOutputRequest = Wire(new DecodeExecutePayload(cfg))
  executeOutputRequest := executeRequestReg
  when(arithmeticHeadReady) { executeOutputRequest := arithmeticEntries(arithmeticHead).payload }
  when(directExecuteFire) { executeOutputRequest := executeInput }
  val executeBranchTarget = executeOutputRequest.pc + executeOutputRequest.immediate
  val executeOutputRs1Data = Mux(directExecuteFire, directRs1Data, executeOutputRequest.rs1Data)
  val executeOutputStoreData = Mux(directExecuteFire, directRs2Data, executeOutputRequest.storeData)
  val executeJalrTargetRaw = executeOutputRs1Data + executeOutputRequest.immediate
  val serialExecuteResult = integerAlu.io.result
  val serialBranchTaken = Mux(executeRequest.executionUnit === NpcExecutionUnit.integer,
    integerAlu.io.branchTaken, NpcBranchResult.notTaken)
  val directExecuteResult = integerAlu.io.result
  directForwardData := directExecuteResult
  serialForwardData := Mux(executeRequest.csrReadWritebackEnable,
    csrExecution.io.readData, serialExecuteResult)
  val executeAluResult = Mux(directExecuteFire, directExecuteResult,
    Mux(arithmeticHeadReady, arithmeticEntries(arithmeticHead).result, serialExecuteResult))
  val executeBranchTaken = Mux(directExecuteFire, integerAlu.io.branchTaken,
    Mux(arithmeticHeadReady, NpcBranchResult.notTaken, serialBranchTaken))
  val executeOutputIsControl = !directExecuteFire && !arithmeticHeadReady

  executeMemoryReg.io.flush := false.B
  executeMemoryReg.io.in.valid := directExecuteFire || arithmeticHeadReady || executeState === executeDone
  executeMemoryReg.io.in.bits.pc := executeOutputRequest.pc
  executeMemoryReg.io.in.bits.instruction := executeOutputRequest.instruction
  executeMemoryReg.io.in.bits.perfFetchCycles := executeOutputRequest.perfFetchCycles
  executeMemoryReg.io.in.bits.perfDecodeCycles := Mux(directExecuteFire,
    performanceCycle - executeOutputRequest.perfDecodeStartCycle, executeOutputRequest.perfDecodeCycles)
  executeMemoryReg.io.in.bits.perfExecuteCycles := Mux(directExecuteFire, 1.U(64.W),
    performanceCycle - executeOutputRequest.perfExecuteStartCycle)
  executeMemoryReg.io.in.bits.perfMemoryStartCycle := performanceCycle
  executeMemoryReg.io.in.bits.aluResult := executeAluResult
  executeMemoryReg.io.in.bits.branchTaken := executeBranchTaken
  executeMemoryReg.io.in.bits.branchTarget := executeBranchTarget
  executeMemoryReg.io.in.bits.jalrTarget := Cat(executeJalrTargetRaw(cfg.xlen - 1, 1), 0.U(1.W))
  executeMemoryReg.io.in.bits.storeData := executeOutputStoreData
  executeMemoryReg.io.in.bits.rd := executeOutputRequest.rd
  executeMemoryReg.io.in.bits.funct3 := executeOutputRequest.funct3
  executeMemoryReg.io.in.bits.branch := executeOutputRequest.branch
  executeMemoryReg.io.in.bits.loadEnable := executeOutputRequest.loadEnable
  executeMemoryReg.io.in.bits.writebackFromMemory := executeOutputRequest.writebackFromMemory
  executeMemoryReg.io.in.bits.storeEnable := executeOutputRequest.storeEnable
  executeMemoryReg.io.in.bits.registerWriteEnable := executeOutputRequest.registerWriteEnable && !arithmeticHeadIllegal
  executeMemoryReg.io.in.bits.floatRegisterWriteEnable := executeOutputRequest.floatRegisterWriteEnable && !arithmeticHeadIllegal
  executeMemoryReg.io.in.bits.floatingInstruction := executeOutputRequest.floatingInstruction && !arithmeticHeadIllegal
  executeMemoryReg.io.in.bits.floatingExceptionFlags := Mux(arithmeticHeadReady,
    arithmeticEntries(arithmeticHead).exceptionFlags, 0.U)
  executeMemoryReg.io.in.bits.csrReadWritebackEnable := executeOutputRequest.csrReadWritebackEnable
  executeMemoryReg.io.in.bits.csrAddress := Mux(executeOutputIsControl, csrExecution.io.csrAddress, 0.U)
  executeMemoryReg.io.in.bits.csrWriteEnable := Mux(executeOutputIsControl, csrExecution.io.csrWriteEnable, false.B)
  executeMemoryReg.io.in.bits.csrWriteData := Mux(executeOutputIsControl, csrExecution.io.csrWriteData, 0.U)
  executeMemoryReg.io.in.bits.csrAccessAllowed := Mux(executeOutputIsControl, csrExecution.io.accessAllowed, false.B)
  executeMemoryReg.io.in.bits.trapEnable := Mux(arithmeticHeadIllegal, true.B,
    Mux(executeOutputIsControl, csrExecution.io.trapEnable, false.B))
  executeMemoryReg.io.in.bits.trapCause := Mux(arithmeticHeadIllegal,
    CsrCause.illegalInstruction.U(cfg.xlen.W), Mux(executeOutputIsControl, csrExecution.io.trapCause, 0.U))
  executeMemoryReg.io.in.bits.trapEpc := Mux(arithmeticHeadIllegal, executeOutputRequest.pc,
    Mux(executeOutputIsControl, csrExecution.io.trapEpc, 0.U))
  executeMemoryReg.io.in.bits.mretEnable := Mux(executeOutputIsControl, csrExecution.io.mretEnable, false.B)
  executeMemoryReg.io.in.bits.csrReadData := Mux(executeOutputIsControl, csrExecution.io.readData, 0.U)
  val executeBranchRedirect = executeMemoryReg.io.in.bits.branch && executeMemoryReg.io.in.bits.branchTaken =/= 0.U
  executeRedirectValid := executeMemoryReg.io.in.fire && executeBranchRedirect
  executeRedirectTarget := Mux(executeMemoryReg.io.in.bits.branchTaken === 2.U,
    executeMemoryReg.io.in.bits.jalrTarget, executeMemoryReg.io.in.bits.branchTarget)

  def driveMemoryWritebackPayload(dst: MemoryWritebackPayload, src: ExecuteMemoryPayload, memData: UInt): Unit = {
    val branchNextPc = Mux(src.branchTaken === 2.U, src.jalrTarget, src.branchTarget)
    dst.pc := src.pc
    dst.instruction := src.instruction
    dst.perfFetchCycles := src.perfFetchCycles
    dst.perfDecodeCycles := src.perfDecodeCycles
    dst.perfExecuteCycles := src.perfExecuteCycles
    dst.perfMemoryCycles := performanceCycle - src.perfMemoryStartCycle
    dst.perfWritebackStartCycle := performanceCycle
    dst.nextPc := Mux(src.branch && src.branchTaken =/= 0.U, branchNextPc, src.pc + 4.U)
    dst.rd := src.rd
    dst.aluResult := src.aluResult
    dst.loadData := memData
    dst.csrReadData := src.csrReadData
    dst.writebackFromMemory := src.writebackFromMemory
    dst.registerWriteEnable := src.registerWriteEnable
    dst.floatRegisterWriteEnable := src.floatRegisterWriteEnable
    dst.floatingInstruction := src.floatingInstruction
    dst.floatingExceptionFlags := src.floatingExceptionFlags
    dst.csrReadWritebackEnable := src.csrReadWritebackEnable
    dst.csrAddress := src.csrAddress
    dst.csrWriteEnable := src.csrWriteEnable
    dst.csrWriteData := src.csrWriteData
    dst.csrAccessAllowed := src.csrAccessAllowed
    dst.trapEnable := src.trapEnable
    dst.trapCause := src.trapCause
    dst.trapEpc := src.trapEpc
    dst.mretEnable := src.mretEnable
  }

  memoryWritebackReg.io.flush := false.B
  memoryWritebackReg.io.in.valid := false.B
  memoryWritebackReg.io.in.bits := 0.U.asTypeOf(new MemoryWritebackPayload(cfg))
  executeMemoryReg.io.out.ready := false.B
  val memoryAccess = executeMemoryReg.io.out.bits.loadEnable || executeMemoryReg.io.out.bits.storeEnable
  val memoryStart = memoryState === memoryIdle && executeMemoryReg.io.out.fire && memoryAccess
  loadStoreUnit.io.start := memoryStart
  loadStoreUnit.io.addr := Mux(memoryStart, executeMemoryReg.io.out.bits.aluResult(31, 0), memoryRequestReg.aluResult(31, 0))
  loadStoreUnit.io.wdata := Mux(memoryStart, executeMemoryReg.io.out.bits.storeData, memoryRequestReg.storeData)
  loadStoreUnit.io.accessType := Mux(memoryStart, executeMemoryReg.io.out.bits.funct3, memoryRequestReg.funct3)
  loadStoreUnit.io.memRead := Mux(memoryStart, executeMemoryReg.io.out.bits.loadEnable, memoryRequestReg.loadEnable)
  loadStoreUnit.io.memWrite := Mux(memoryStart, executeMemoryReg.io.out.bits.storeEnable, memoryRequestReg.storeEnable)
  when(memoryState === memoryIdle) {
    when(executeMemoryReg.io.out.valid && memoryAccess) {
      executeMemoryReg.io.out.ready := !loadStoreUnit.io.busy
      when(executeMemoryReg.io.out.fire) {
        memoryRequestReg := executeMemoryReg.io.out.bits
        memoryState := memoryWait
      }
    }.otherwise {
      memoryWritebackReg.io.in.valid := executeMemoryReg.io.out.valid
      driveMemoryWritebackPayload(memoryWritebackReg.io.in.bits, executeMemoryReg.io.out.bits, 0.U(cfg.xlen.W))
      executeMemoryReg.io.out.ready := memoryWritebackReg.io.in.ready
    }
  }.otherwise {
    memoryWritebackReg.io.in.valid := !loadStoreUnit.io.busy
    driveMemoryWritebackPayload(memoryWritebackReg.io.in.bits, memoryRequestReg, loadStoreUnit.io.rdata)
    when(memoryWritebackReg.io.in.fire) { memoryState := memoryIdle }
  }

  memoryWritebackReg.io.out.ready := true.B
  val commitFire = memoryWritebackReg.io.out.fire
  val commitWriteData = Mux(memoryWritebackReg.io.out.bits.csrReadWritebackEnable,
    memoryWritebackReg.io.out.bits.csrReadData,
    Mux(memoryWritebackReg.io.out.bits.writebackFromMemory,
      memoryWritebackReg.io.out.bits.loadData, memoryWritebackReg.io.out.bits.aluResult))
  commitForwardData := commitWriteData
  registerFile.io.rd := memoryWritebackReg.io.out.bits.rd
  registerFile.io.writeData := commitWriteData
  registerFile.io.writeEnable := memoryWritebackReg.io.out.bits.registerWriteEnable
  registerFile.io.commit := commitFire
  floatingRegisterFile.foreach { fp =>
    fp.io.rd := memoryWritebackReg.io.out.bits.rd
    fp.io.writeData := Mux(memoryWritebackReg.io.out.bits.writebackFromMemory,
      memoryWritebackReg.io.out.bits.loadData, memoryWritebackReg.io.out.bits.aluResult)
    fp.io.writeEnable := memoryWritebackReg.io.out.bits.floatRegisterWriteEnable
    fp.io.commit := commitFire
  }
  csrFile.io.address := Mux(serialExecuteAccept, executeRequest.csrAddress,
    Mux(commitFire && memoryWritebackReg.io.out.bits.csrWriteEnable,
      memoryWritebackReg.io.out.bits.csrAddress, dispatch.csrAddress))
  csrFile.io.writeData := memoryWritebackReg.io.out.bits.csrWriteData
  csrFile.io.writeEnable := commitFire && memoryWritebackReg.io.out.bits.csrWriteEnable
  csrFile.io.accessAllowed := memoryWritebackReg.io.out.bits.csrAccessAllowed
  csrFile.io.externalInterrupt := io.interrupt
  csrFile.io.trapEnable := commitFire && memoryWritebackReg.io.out.bits.trapEnable
  csrFile.io.trapCause := memoryWritebackReg.io.out.bits.trapCause
  csrFile.io.trapEpc := memoryWritebackReg.io.out.bits.trapEpc
  csrFile.io.floatingCommit := commitFire && memoryWritebackReg.io.out.bits.floatingInstruction
  csrFile.io.floatingExceptionFlags := memoryWritebackReg.io.out.bits.floatingExceptionFlags
  commitRedirectValid := commitFire && (memoryWritebackReg.io.out.bits.trapEnable || memoryWritebackReg.io.out.bits.mretEnable)
  commitRedirectTarget := Mux(memoryWritebackReg.io.out.bits.trapEnable, csrFile.io.trapVector,
    csrFile.io.machineExceptionPc)
  val commitNextPc = Mux(memoryWritebackReg.io.out.bits.trapEnable, csrFile.io.trapVector,
    Mux(memoryWritebackReg.io.out.bits.mretEnable, csrFile.io.machineExceptionPc,
      memoryWritebackReg.io.out.bits.nextPc))
  when(io.dispatch.fire && (dispatch.trapEnable || dispatch.mretEnable)) {
    redirectBarrier := true.B
  }
  when(commitRedirectValid) { redirectBarrier := false.B }

  val commitValidDebug = RegNext(commitFire, false.B)
  val commitPcDebug = RegEnable(memoryWritebackReg.io.out.bits.pc, 0.U(cfg.xlen.W), commitFire)
  val commitInstDebug = RegEnable(memoryWritebackReg.io.out.bits.instruction, 0.U(32.W), commitFire)
  val commitNextPcDebug = RegEnable(commitNextPc, 0.U(cfg.xlen.W), commitFire)
  val commitFetchCyclesDebug = RegEnable(memoryWritebackReg.io.out.bits.perfFetchCycles, 0.U(64.W), commitFire)
  val commitDecodeCyclesDebug = RegEnable(memoryWritebackReg.io.out.bits.perfDecodeCycles, 0.U(64.W), commitFire)
  val commitExecuteCyclesDebug = RegEnable(memoryWritebackReg.io.out.bits.perfExecuteCycles, 0.U(64.W), commitFire)
  val commitMemoryCyclesDebug = RegEnable(memoryWritebackReg.io.out.bits.perfMemoryCycles, 0.U(64.W), commitFire)
  val commitWritebackCyclesDebug = RegEnable(performanceCycle - memoryWritebackReg.io.out.bits.perfWritebackStartCycle, 0.U(64.W), commitFire)
  val commitTrapEnDebug = RegNext(csrFile.io.trapEnable, false.B)
  val commitCsrAccessAllowedDebug = RegEnable(memoryWritebackReg.io.out.bits.csrAccessAllowed, false.B, commitFire)
  val commitCsrAddressDebug = RegEnable(memoryWritebackReg.io.out.bits.csrAddress, 0.U(12.W), commitFire)
  val commitTrapEpcDebug = RegEnable(memoryWritebackReg.io.out.bits.trapEpc, 0.U(cfg.xlen.W), commitFire)

  val idStallCycles = RegInit(0.U(64.W))
  val executeStallCycles = RegInit(0.U(64.W))
  val memoryStallCycles = RegInit(0.U(64.W))
  when(io.dispatch.valid && !io.dispatch.ready) { idStallCycles := idStallCycles + 1.U }
  when(decodeExecuteReg.io.out.valid && !decodeExecuteReg.io.out.ready) { executeStallCycles := executeStallCycles + 1.U }
  when((executeMemoryReg.io.out.valid && !executeMemoryReg.io.out.ready) || memoryState === memoryWait) {
    memoryStallCycles := memoryStallCycles + 1.U
  }

  io.redirectValid := frontendRedirectValid
  io.redirectTarget := frontendRedirectTarget
  io.debug.registers := registerFile.io.registersOut.get
  io.debug.floatingRegisters := floatingRegisterFile.map(_.io.registersOut)
    .getOrElse(VecInit(Seq.fill(32)(0.U(cfg.xlen.W))))
  io.debug.fcsr := csrFile.io.fcsrOut
  io.debug.mstatus := csrFile.io.mstatusOut
  io.debug.mcause := csrFile.io.mcauseOut
  io.debug.mtvec := csrFile.io.mtvecOut
  io.debug.decodeExecuteFire := decodeExecuteFire
  io.debug.executeMemoryFire := executeMemoryReg.io.in.fire
  io.debug.commitValid := commitValidDebug
  io.debug.mepc := csrFile.io.machineExceptionPc
  io.debug.executeTrapEnable := csrExecution.io.trapEnable
  io.debug.commitTrapEnable := commitTrapEnDebug
  io.debug.executeCsrEnable := executeRequestReg.csrEnable
  io.debug.commitCsrAllow := commitCsrAccessAllowedDebug
  io.debug.commitCsrAddress := commitCsrAddressDebug
  io.debug.commitTrapEpc := commitTrapEpcDebug
  io.debug.commitPc := commitPcDebug
  io.debug.commitInstruction := commitInstDebug
  io.debug.commitNextPc := commitNextPcDebug
  io.debug.cycleCount := performanceCycle
  io.debug.commitFetchCycles := commitFetchCyclesDebug
  io.debug.commitDecodeCycles := commitDecodeCyclesDebug
  io.debug.commitExecuteCycles := commitExecuteCyclesDebug
  io.debug.commitMemoryCycles := commitMemoryCyclesDebug
  io.debug.commitWritebackCycles := commitWritebackCyclesDebug
  io.debug.pipelineFeatures := Cat(pipelineConfig.forwarding.enableExecuteForwarding.B,
    pipelineConfig.forwarding.enableIdForwarding.B, pipelineConfig.enablePipeline.B)
  io.debug.idStallCycles := idStallCycles
  io.debug.executeStallCycles := executeStallCycles
  io.debug.memoryStallCycles := memoryStallCycles
  io.debug.coreBusy := busyAfterDecode || loadStoreUnit.io.busy
  io.debug.executeAluResult := serialExecuteResult
  io.debug.memoryResult := loadStoreUnit.io.rdata
  io.debug.dispatchBackpressured := io.dispatch.valid && !io.dispatch.ready
  io.debug.idExBackpressured := decodeExecuteReg.io.out.valid && !decodeExecuteReg.io.out.ready
  io.debug.exMemBackpressured := executeMemoryReg.io.out.valid && !executeMemoryReg.io.out.ready
  io.debug.memoryWaitingForLsu := memoryState === memoryWait
  io.debug.lsuTransactionActive := loadStoreUnit.io.busy
  io.debug.serialExecuteActive := executeState =/= executeIdle
}
