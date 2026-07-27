package npc

import chisel3._
import chisel3.util._
import npc.ip.arithmetic.ArithmeticResponse
import npc.ip.memory.MemoryFault
import npc.protocol._

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
  private val twoStageIntegerExecute = pipelineConfig.integerExecuteStages == 2
  private val twoStageSerialExecute = pipelineConfig.serialExecuteStages == 2
  private val threeStageSerialExecute = pipelineConfig.serialExecuteStages == 3
  private val pipelinedSerialExecute = pipelineConfig.serialExecuteStages >= 2
  private val separateSerialIntegerAlu = pipelineConfig.separateSerialIntegerAlu
  private val serialExecuteResultForwarding = pipelineConfig.serialExecuteResultForwarding
  private val operatorConfig = config.operators
  private val localM = cfg.M
  private val localF = cfg.F
  private val debugEnabled = config.debug.enableTopDebugIo
  private val axiConfig = config.axi
  private val arithmeticTagWidth =
    if (localM) operatorConfig.mulDiv.tagWidth
    else if (localF) operatorConfig.floating.tagWidth
    else 1
  require(!localM || !localF || operatorConfig.mulDiv.tagWidth == operatorConfig.floating.tagWidth,
    "Integer and floating arithmetic endpoints must use the same tagWidth")

  val io = IO(new Bundle {
    val interrupt = Input(Bool())
    val dispatch = Flipped(Decoupled(new DecodedDispatchPayload(cfg)))
    val axi = new AxiLiteMasterIO(axiConfig.addrWidth, axiConfig.dataWidth)
    val redirectValid = Output(Bool())
    val redirectTarget = Output(UInt(cfg.xlen.W))
    val memoryFault = Output(new MemoryFault(axiConfig.addrWidth))
    val debug = Output(new NpcBackendDebugBundle(cfg))
  })

  val decodeExecuteReg = Module(new PipelineRegister(new DecodeExecutePayload(cfg)))
  // 仅两拍整数路径使用的 EX0。它锁存已经完成的 GPR 前递和完整控制字，避免
  // usesRs2 -> forwarding -> IntegerAlu -> redirect 形成一条跨级组合链。
  val integerExecuteReg = Module(new PipelineRegister(new DecodeExecutePayload(cfg)))
  // 三拍串行路径先将已经锁存的串行请求送入 EX1，再由 EX2 生成完整结果载荷。这样
  // executeState 只驱动该窄控制级，不会直接进入 CSR/ALU 到 EX/MEM 的宽选择网络。
  val serialExecuteControlReg = Module(new PipelineRegister(new DecodeExecutePayload(cfg)))
  val serialExecuteResultReg = Module(new PipelineRegister(new ExecuteMemoryPayload(cfg)))
  val executeMemoryReg = Module(new PipelineRegister(new ExecuteMemoryPayload(cfg)))
  val memoryWritebackReg = Module(new PipelineRegister(new MemoryWritebackPayload(cfg)))
  // M/F 严格只保留一条在途指令。端点响应先进入该弹性寄存器，再和普通结果一样
  // 推入 EX/MEM；没有 tag 回填、完成 FIFO 或跨越 ID/EX 的完成状态反压。
  val arithmeticResponseReg = Module(new PipelineRegister(new ArithmeticResponse(cfg.xlen, arithmeticTagWidth)))
  // 已派发陷入或 mret 时阻止继续译码，直到该指令在提交阶段完成重定向。
  val redirectBarrier = RegInit(false.B)

  val debugCycleCounter = if (debugEnabled) Some(RegInit(0.U(64.W))) else None
  debugCycleCounter.foreach(counter => counter := counter + 1.U)
  val performanceCycle = debugCycleCounter.getOrElse(0.U(64.W))

  val executeRedirectValid = WireDefault(false.B)
  val executeRedirectTarget = WireDefault(0.U(cfg.xlen.W))
  // 两拍整数路径的分支在 EX/MEM 中保存后才允许改写前端 PC。该 pending 信号还会
  // 阻止同拍接收 EX0 的年轻指令，避免 redirect 冲刷与 EX/MEM 填充发生竞争。
  val executeMemoryRedirectPending = WireDefault(false.B)
  val commitRedirectValid = WireDefault(false.B)
  val commitRedirectTarget = WireDefault(0.U(cfg.xlen.W))
  val frontendRedirectValid = commitRedirectValid || executeRedirectValid
  val frontendRedirectTarget = Mux(commitRedirectValid, commitRedirectTarget, executeRedirectTarget)

  val registerFile = Module(new RegisterFile(width = cfg.xlen, debug = true))
  val floatingRegisterFile = if (localF) Some(Module(new FloatingRegisterFile(cfg.xlen))) else None
  val integerAlu = Module(new IntegerAlu(cfg.xlen))
  // 可选的串行 ALU 供 CSR、异常和 mret 路径使用。三拍模式下它只消费
  // serialExecuteControlReg，避免 executeState 经共享输入 mux 重新穿过结果级 ALU。
  val serialIntegerAlu = if (separateSerialIntegerAlu) Some(Module(new IntegerAlu(cfg.xlen))) else None
  val mulDivAlu = if (localM) Some(Module(new MulDivAlu(
    cfg.xlen, operatorConfig.mulDiv, operatorConfig.routes, components.arithmeticIp))) else None
  val floatingAlu = if (localF) Some(Module(new FloatingAlu(
    cfg.xlen, operatorConfig.floating, operatorConfig.routes, components.arithmeticIp))) else None
  val csrExecution = Module(new CsrExecution(cfg))
  val csrFile = Module(new CsrFile(cfg))
  val loadStoreUnit = Module(new LSUAXIAdapter(
    axiConfig.addrWidth,
    axiConfig.dataWidth,
    config.memory.mainMemoryBase,
    config.memory.mainMemorySize
  ))
  loadStoreUnit.io.axi <> io.axi
  io.memoryFault := loadStoreUnit.io.fault

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

  val executeIdle :: executeSerialDispatch :: executeDone :: executeArithmeticWait :: Nil = Enum(4)
  val executeState = RegInit(executeIdle)
  val executeRequestReg = Reg(new DecodeExecutePayload(cfg))
  val memoryIdle :: memoryWait :: Nil = Enum(2)
  val memoryState = RegInit(memoryIdle)
  val memoryRequestReg = Reg(new ExecuteMemoryPayload(cfg))

  val executeInput = decodeExecuteReg.io.out.bits
  integerExecuteReg.io.flush := frontendRedirectValid
  integerExecuteReg.io.in.valid := false.B
  integerExecuteReg.io.in.bits := executeInput
  integerExecuteReg.io.out.ready := false.B
  serialExecuteControlReg.io.flush := frontendRedirectValid
  serialExecuteControlReg.io.in.valid := false.B
  serialExecuteControlReg.io.in.bits := executeRequestReg
  serialExecuteControlReg.io.out.ready := false.B
  serialExecuteResultReg.io.flush := frontendRedirectValid
  serialExecuteResultReg.io.in.valid := false.B
  serialExecuteResultReg.io.in.bits := 0.U.asTypeOf(new ExecuteMemoryPayload(cfg))
  serialExecuteResultReg.io.out.ready := false.B
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
  val olderInstructionsDrained = (!twoStageIntegerExecute).B || !integerExecuteReg.io.out.valid
  val serialControlStagePending = threeStageSerialExecute.B && serialExecuteControlReg.io.out.valid
  val serialResultStagePending = pipelinedSerialExecute.B && serialExecuteResultReg.io.out.valid
  val serialOlderInstructionsDrained = olderInstructionsDrained && !serialControlStagePending &&
    !serialResultStagePending &&
    !executeMemoryReg.io.out.valid && memoryState === memoryIdle &&
    !memoryWritebackReg.io.out.valid && !loadStoreUnit.io.busy
  // 算术请求只会在全部旧指令排空后发射。响应路径由 arithmeticResponseReg 截断，
  // 因而 endpoint 的完成 valid 不会反压到 ID/EX 的完整载荷。
  val arithmeticUnitReady = Mux(
    executeInput.executionUnit === NpcExecutionUnit.multiply || executeInput.executionUnit === NpcExecutionUnit.divide,
    mulDivAlu.map(_.io.req.ready).getOrElse(false.B),
    Mux(executeInput.executionUnit === NpcExecutionUnit.floating,
      floatingAlu.map(_.io.req.ready).getOrElse(false.B), false.B)
  )
  val arithmeticCanAccept = executeState === executeIdle && arithmeticUnitReady && serialOlderInstructionsDrained
  val serialCanAccept = executeState === executeIdle &&
    (!pipelineMode || serialOlderInstructionsDrained)
  val directCanAccept = executeState === executeIdle &&
    !serialControlStagePending && !serialResultStagePending &&
    (if (twoStageIntegerExecute) integerExecuteReg.io.in.ready else executeMemoryReg.io.in.ready)
  val directExecuteWillFire = if (twoStageIntegerExecute) false.B else pipelineMode &&
    decodeExecuteReg.io.out.valid && !executeInputIsSerial && !executeInputIsArithmetic && directCanAccept

  // 下方组装 EX/WB 数据通路后这些值才有具体连接。在此声明连线可使候选项优先级
  // 独立于数据通路在源码中的书写顺序。
  val directForwardData = WireDefault(0.U(cfg.xlen.W))
  val serialForwardData = WireDefault(0.U(cfg.xlen.W))
  val commitForwardData = WireDefault(0.U(cfg.xlen.W))
  val executeMemoryForwardData = Mux(executeMemoryReg.io.out.bits.csrReadWritebackEnable,
    executeMemoryReg.io.out.bits.csrReadData, executeMemoryReg.io.out.bits.aluResult)
  val serialExecuteResultForwardData = Mux(serialExecuteResultReg.io.out.bits.csrReadWritebackEnable,
    serialExecuteResultReg.io.out.bits.csrReadData, serialExecuteResultReg.io.out.bits.aluResult)
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

  // 生产者从新到旧排列。EX0、串行 EX1 结果级都没有组合旁路，必须等结果进入
  // 既有 EX/MEM 前递点后，年轻消费者才能继续。
  val producerValid = Seq(
    decodeExecuteReg.io.out.valid,
    if (twoStageIntegerExecute) integerExecuteReg.io.out.valid else false.B,
    executeState =/= executeIdle,
    serialResultStagePending,
    executeMemoryReg.io.out.valid,
    memoryState === memoryWait,
    memoryWritebackReg.io.out.valid
  )
  val producerWritesRd = Seq(
    decodeExecuteReg.io.out.bits.registerWriteEnable,
    integerExecuteReg.io.out.bits.registerWriteEnable,
    executeRequestReg.registerWriteEnable,
    serialExecuteResultReg.io.out.bits.registerWriteEnable,
    executeMemoryReg.io.out.bits.registerWriteEnable,
    memoryRequestReg.registerWriteEnable,
    memoryWritebackReg.io.out.bits.registerWriteEnable
  )
  val producerRd = Seq(
    decodeExecuteReg.io.out.bits.rd,
    integerExecuteReg.io.out.bits.rd,
    executeRequestReg.rd,
    serialExecuteResultReg.io.out.bits.rd,
    executeMemoryReg.io.out.bits.rd,
    memoryRequestReg.rd,
    memoryWritebackReg.io.out.bits.rd
  )
  val producerWritesFloatingRd = Seq(
    decodeExecuteReg.io.out.bits.floatRegisterWriteEnable,
    integerExecuteReg.io.out.bits.floatRegisterWriteEnable,
    executeRequestReg.floatRegisterWriteEnable,
    serialExecuteResultReg.io.out.bits.floatRegisterWriteEnable,
    executeMemoryReg.io.out.bits.floatRegisterWriteEnable,
    memoryRequestReg.floatRegisterWriteEnable,
    memoryWritebackReg.io.out.bits.floatRegisterWriteEnable
  )

  val idCandidateAvailable = Seq(
    directExecuteWillFire && executeInput.executionUnit === NpcExecutionUnit.integer && !executeInput.writebackFromMemory,
    false.B,
    serialExecuteResultForwarding.B && !pipelinedSerialExecute.B && executeState === executeDone,
    serialExecuteResultForwarding.B && serialResultStagePending,
    !executeMemoryReg.io.out.bits.writebackFromMemory,
    memoryResponseAvailable,
    true.B
  )
  val dispatchIsArithmetic = dispatch.executionUnit === NpcExecutionUnit.multiply ||
    dispatch.executionUnit === NpcExecutionUnit.divide || dispatch.executionUnit === NpcExecutionUnit.floating
  val dispatchIsSerial = dispatchIsArithmetic ||
    dispatch.csrEnable || dispatch.trapEnable || dispatch.mretEnable
  val currentDecodeSlotCanAdvance = !decodeExecuteReg.io.out.valid ||
    (executeInputIsArithmetic && arithmeticCanAccept) ||
    (!executeInputIsSerial && !executeInputIsArithmetic && directCanAccept)
  val incomingCanExecuteDirectNextCycle = !twoStageIntegerExecute.B && pipelineMode && !dispatchIsSerial &&
    executeState === executeIdle && currentDecodeSlotCanAdvance
  val executeForwardNextCycleAvailable = Seq(
    incomingCanExecuteDirectNextCycle && directExecuteWillFire &&
      executeInput.executionUnit === NpcExecutionUnit.integer && !executeInput.writebackFromMemory,
    false.B,
    serialExecuteResultForwarding.B && !pipelinedSerialExecute.B && incomingCanExecuteDirectNextCycle &&
      executeState === executeDone && executeMemoryReg.io.in.ready,
    false.B,
    incomingCanExecuteDirectNextCycle && !executeMemoryReg.io.out.bits.writebackFromMemory,
    incomingCanExecuteDirectNextCycle && memoryResponseAvailable,
    false.B
  )
  val idCandidateData = Seq(
    directForwardData,
    0.U(cfg.xlen.W),
    serialForwardData,
    serialExecuteResultForwardData,
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

  for (index <- 0 until 7) {
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
  // 在 EX0/EX1/EX2，槽位零至三分别为当前 ID/EX、EX0、串行请求和串行结果级；它们
  // 都不能组合旁路到普通整数 ALU。更老结果保持由新到旧优先级。
  driveCandidate(forwardingUnit.io.executeCandidates(0), false.B, false.B, 0.U,
    0.U(cfg.xlen.W), false.B)
  driveCandidate(forwardingUnit.io.executeCandidates(1), false.B, false.B, 0.U,
    0.U(cfg.xlen.W), false.B)
  driveCandidate(forwardingUnit.io.executeCandidates(2), false.B, false.B, 0.U,
    0.U(cfg.xlen.W), false.B)
  driveCandidate(forwardingUnit.io.executeCandidates(3), false.B, false.B, 0.U,
    0.U(cfg.xlen.W), false.B)
  for (index <- 4 until 7) {
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
  val busyAfterDecode = decodeExecuteReg.io.out.valid || integerExecuteReg.io.out.valid ||
    serialExecuteControlReg.io.out.valid || serialExecuteResultReg.io.out.valid ||
    (executeState =/= executeIdle) || executeMemoryReg.io.out.valid ||
    (memoryState =/= memoryIdle) || memoryWritebackReg.io.out.valid || loadStoreUnit.io.busy
  // 本拍的算术发射不能同时给 ID/EX 填入更年轻的指令；这样 M/F 从请求到 EX/MEM
  // 均是单项按序路径，后续派发只会在响应被 EX/MEM 接收后恢复。
  val arithmeticWillIssue = decodeExecuteReg.io.out.valid && executeInputIsArithmetic && arithmeticCanAccept
  val decodeCanIssue = Mux(
    pipelineConfig.enablePipeline.B,
    !hazardUnit.io.stall && !floatingRawHazard && !redirectBarrier && !frontendRedirectValid &&
      executeState =/= executeArithmeticWait && !arithmeticWillIssue,
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

  // PipelineRegister 会把 out.ready 作为整个 DecodeExecutePayload 的更新许可。不要在
  // 这个边界继续接入来自外部 IP 响应侧的组合反馈，否则一个算术完成事件会扇出到 ID/EX
  // 的所有数据字段和后续 EX/MEM 选择逻辑，成为 FPGA 的关键路径。
  decodeExecuteReg.io.out.ready := Mux(executeInputIsArithmetic,
    arithmeticCanAccept,
    Mux(pipelineMode, Mux(executeInputIsSerial, serialCanAccept, directCanAccept), executeState === executeIdle))
  val decodeExecuteFire = decodeExecuteReg.io.out.fire
  val arithmeticIssue = decodeExecuteFire && executeInputIsArithmetic
  val serialExecuteAccept = decodeExecuteFire && !executeInputIsArithmetic &&
    (!pipelineMode || executeInputIsSerial)
  val directExecuteFire = decodeExecuteFire && pipelineMode && !executeInputIsSerial &&
    !executeInputIsArithmetic

  val directExecuteInput = Wire(new DecodeExecutePayload(cfg))
  directExecuteInput := executeInput
  if (twoStageIntegerExecute) {
    // EX0 接收时完成所有普通整数操作数选择。浮点 store 的 rs2 已由 FPR 在 ID
    // 读取，不能经过 GPR forwarding mux。
    integerExecuteReg.io.in.valid := decodeExecuteFire && !executeInputIsSerial && !executeInputIsArithmetic
    integerExecuteReg.io.in.bits := executeInput
    integerExecuteReg.io.in.bits.rs1Data := forwardingUnit.io.executeRs1Forwarded
    integerExecuteReg.io.in.bits.storeData := Mux(executeInput.usesFrs2,
      executeInput.storeData, forwardingUnit.io.executeRs2Forwarded)
    integerExecuteReg.io.in.bits.perfDecodeCycles := performanceCycle - executeInput.perfDecodeStartCycle
    integerExecuteReg.io.in.bits.perfExecuteStartCycle := performanceCycle
    // commit redirect 必须阻止已在 EX0 的错误路径进入 EX1；本拍由 EX1 自己产生的
    // 分支 redirect 改由 EX/MEM 输出产生，pending 时不能让错误路径进入 EX1。
    integerExecuteReg.io.out.ready := executeMemoryReg.io.in.ready && executeState === executeIdle &&
      !commitRedirectValid && !executeMemoryRedirectPending
    directExecuteInput := integerExecuteReg.io.out.bits
  }
  val directIntegerExecuteFire = if (twoStageIntegerExecute) integerExecuteReg.io.out.fire else directExecuteFire

  val executeRequest = Wire(new DecodeExecutePayload(cfg))
  executeRequest := executeRequestReg
  when(serialExecuteAccept) { executeRequest := executeInput }
  val serialComputeRequest = Wire(new DecodeExecutePayload(cfg))
  serialComputeRequest := executeRequest
  if (threeStageSerialExecute) {
    serialComputeRequest := serialExecuteControlReg.io.out.bits
  }
  val serialAluOperandB = Mux(serialComputeRequest.useImmediate,
    serialComputeRequest.immediate, serialComputeRequest.storeData)
  val directRs1Data = if (twoStageIntegerExecute) directExecuteInput.rs1Data else forwardingUnit.io.executeRs1Forwarded
  val directRs2Data = if (twoStageIntegerExecute) directExecuteInput.storeData else forwardingUnit.io.executeRs2Forwarded
  val directAluOperandB = Mux(directExecuteInput.useImmediate, directExecuteInput.immediate, directRs2Data)
  val arithmeticIssuePayload = Wire(new DecodeExecutePayload(cfg))
  arithmeticIssuePayload := executeInput
  arithmeticIssuePayload.perfDecodeCycles := performanceCycle - executeInput.perfDecodeStartCycle
  arithmeticIssuePayload.perfExecuteStartCycle := performanceCycle
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
    alu.io.req.bits.tag := 0.U
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
    alu.io.req.bits.tag := 0.U
  }

  val arithmeticResponseFromMulDiv = executeRequestReg.executionUnit === NpcExecutionUnit.multiply ||
    executeRequestReg.executionUnit === NpcExecutionUnit.divide
  val emptyArithmeticResponse = 0.U.asTypeOf(new ArithmeticResponse(cfg.xlen, arithmeticTagWidth))
  val mulDivResponse = mulDivAlu.map(_.io.resp.bits).getOrElse(emptyArithmeticResponse)
  val floatingResponse = floatingAlu.map(_.io.resp.bits).getOrElse(emptyArithmeticResponse)
  val mulDivResponseValid = mulDivAlu.map(_.io.resp.valid).getOrElse(false.B)
  val floatingResponseValid = floatingAlu.map(_.io.resp.valid).getOrElse(false.B)
  val arithmeticResponseActive = executeState === executeArithmeticWait
  val selectedArithmeticResponse = Mux(arithmeticResponseFromMulDiv, mulDivResponse, floatingResponse)
  val selectedArithmeticResponseValid = Mux(arithmeticResponseFromMulDiv,
    mulDivResponseValid, floatingResponseValid)
  arithmeticResponseReg.io.flush := false.B
  arithmeticResponseReg.io.in.valid := arithmeticResponseActive && selectedArithmeticResponseValid
  arithmeticResponseReg.io.in.bits := selectedArithmeticResponse
  // 只有保存了对应请求的端点可以推进响应；这样 M/F 之间不需要 RR 仲裁或 tag 回填。
  mulDivAlu.foreach(_.io.resp.ready := arithmeticResponseActive && arithmeticResponseFromMulDiv &&
    arithmeticResponseReg.io.in.ready)
  floatingAlu.foreach(_.io.resp.ready := arithmeticResponseActive && !arithmeticResponseFromMulDiv &&
    arithmeticResponseReg.io.in.ready)
  arithmeticResponseReg.io.out.ready := executeMemoryReg.io.in.ready && !executeMemoryRedirectPending
  val serialExecuteComplete = if (pipelinedSerialExecute) serialExecuteResultReg.io.in.fire
    else executeMemoryReg.io.in.fire

  when(serialExecuteAccept) {
    executeRequestReg := executeInput
    executeRequestReg.perfDecodeCycles := performanceCycle - executeInput.perfDecodeStartCycle
    executeRequestReg.perfExecuteStartCycle := performanceCycle
    executeState := (if (threeStageSerialExecute) executeSerialDispatch else executeDone)
  }.elsewhen(arithmeticIssue) {
    executeRequestReg := arithmeticIssuePayload
    executeState := executeArithmeticWait
  }.elsewhen(threeStageSerialExecute.B && executeState === executeSerialDispatch &&
    serialExecuteControlReg.io.in.fire) {
    executeState := executeDone
  }.elsewhen(executeState === executeArithmeticWait && arithmeticResponseReg.io.out.fire) {
    executeState := executeIdle
  }.elsewhen(executeState === executeDone && serialExecuteComplete) {
    executeState := executeIdle
  }

  if (separateSerialIntegerAlu) {
    integerAlu.io.a := directRs1Data
    integerAlu.io.b := directAluOperandB
    integerAlu.io.pc := directExecuteInput.pc
    integerAlu.io.control := directExecuteInput.aluCtrl

    val serialAlu = serialIntegerAlu.get
    serialAlu.io.a := serialComputeRequest.rs1Data
    serialAlu.io.b := serialAluOperandB
    serialAlu.io.pc := serialComputeRequest.pc
    serialAlu.io.control := serialComputeRequest.aluCtrl
  } else {
    integerAlu.io.a := Mux(directIntegerExecuteFire, directRs1Data, serialComputeRequest.rs1Data)
    integerAlu.io.b := Mux(directIntegerExecuteFire, directAluOperandB, serialAluOperandB)
    integerAlu.io.pc := Mux(directIntegerExecuteFire, directExecuteInput.pc, serialComputeRequest.pc)
    integerAlu.io.control := Mux(directIntegerExecuteFire, directExecuteInput.aluCtrl, serialComputeRequest.aluCtrl)
  }
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

  val arithmeticResponseAvailable = arithmeticResponseReg.io.out.valid
  val arithmeticResponseIllegal = arithmeticResponseAvailable && arithmeticResponseReg.io.out.bits.illegal

  val executeOutputRequest = Wire(new DecodeExecutePayload(cfg))
  executeOutputRequest := executeRequestReg
  when(directIntegerExecuteFire) { executeOutputRequest := directExecuteInput }
  val executeBranchTarget = executeOutputRequest.pc + executeOutputRequest.immediate
  val executeOutputRs1Data = Mux(directIntegerExecuteFire, directRs1Data, executeOutputRequest.rs1Data)
  // 浮点 store 通过 `storeData`（`usesFrs2`）携带源值；整数 store 才需要 EX
  // 前递。若统一使用整数 mux，浮点源会丢失，并可能以陈旧值覆盖相邻内存 lane。
  val directStoreData = Mux(executeOutputRequest.usesFrs2,
    executeOutputRequest.storeData, directRs2Data)
  val executeOutputStoreData = Mux(directIntegerExecuteFire, directStoreData,
    executeOutputRequest.storeData)
  val executeJalrTargetRaw = executeOutputRs1Data + executeOutputRequest.immediate
  val serialExecuteResult = serialIntegerAlu.map(_.io.result).getOrElse(integerAlu.io.result)
  val serialBranchTaken = Mux(serialComputeRequest.executionUnit === NpcExecutionUnit.integer,
    serialIntegerAlu.map(_.io.branchTaken).getOrElse(integerAlu.io.branchTaken), NpcBranchResult.notTaken)
  val directExecuteResult = integerAlu.io.result
  directForwardData := directExecuteResult
  serialForwardData := Mux(executeRequest.csrReadWritebackEnable,
    csrExecution.io.readData, serialExecuteResult)
  val executeAluResult = Mux(directIntegerExecuteFire, directExecuteResult,
    Mux(arithmeticResponseAvailable, arithmeticResponseReg.io.out.bits.result, serialExecuteResult))
  val executeBranchTaken = Mux(directIntegerExecuteFire, integerAlu.io.branchTaken,
    Mux(arithmeticResponseAvailable, NpcBranchResult.notTaken, serialBranchTaken))
  val executeOutputIsControl = !directIntegerExecuteFire && !arithmeticResponseAvailable

  def executeMemoryPayload(
    request: DecodeExecutePayload,
    aluResult: UInt,
    branchTaken: UInt,
    branchTarget: UInt,
    jalrTarget: UInt,
    storeData: UInt,
    perfDecodeCycles: UInt,
    perfExecuteCycles: UInt,
    arithmeticResponse: Bool,
    arithmeticIllegal: Bool,
    controlResult: Bool
  ): ExecuteMemoryPayload = {
    val payload = Wire(new ExecuteMemoryPayload(cfg))
    payload.pc := request.pc
    payload.instruction := request.instruction
    payload.perfFetchCycles := request.perfFetchCycles
    payload.perfDecodeCycles := perfDecodeCycles
    payload.perfExecuteCycles := perfExecuteCycles
    payload.perfMemoryStartCycle := performanceCycle
    payload.aluResult := aluResult
    payload.branchTaken := branchTaken
    payload.branchTarget := branchTarget
    payload.jalrTarget := jalrTarget
    payload.storeData := storeData
    payload.rd := request.rd
    payload.funct3 := request.funct3
    payload.branch := request.branch
    payload.loadEnable := request.loadEnable
    payload.writebackFromMemory := request.writebackFromMemory
    payload.storeEnable := request.storeEnable
    payload.registerWriteEnable := request.registerWriteEnable && !arithmeticIllegal
    payload.floatRegisterWriteEnable := request.floatRegisterWriteEnable && !arithmeticIllegal
    payload.floatingInstruction := request.floatingInstruction && !arithmeticIllegal
    payload.floatingExceptionFlags := Mux(arithmeticResponse,
      arithmeticResponseReg.io.out.bits.exceptionFlags, 0.U)
    payload.csrReadWritebackEnable := request.csrReadWritebackEnable
    payload.csrAddress := Mux(controlResult, csrExecution.io.csrAddress, 0.U)
    payload.csrWriteEnable := Mux(controlResult, csrExecution.io.csrWriteEnable, false.B)
    payload.csrWriteData := Mux(controlResult, csrExecution.io.csrWriteData, 0.U)
    payload.csrAccessAllowed := Mux(controlResult, csrExecution.io.accessAllowed, false.B)
    payload.trapEnable := Mux(arithmeticIllegal, true.B,
      Mux(controlResult, csrExecution.io.trapEnable, false.B))
    payload.trapCause := Mux(arithmeticIllegal,
      CsrCause.illegalInstruction.U(cfg.xlen.W), Mux(controlResult, csrExecution.io.trapCause, 0.U))
    payload.trapEpc := Mux(arithmeticIllegal, request.pc,
      Mux(controlResult, csrExecution.io.trapEpc, 0.U))
    payload.mretEnable := Mux(controlResult, csrExecution.io.mretEnable, false.B)
    payload.csrReadData := Mux(controlResult, csrExecution.io.readData, 0.U)
    payload
  }

  val computedExecuteMemory = executeMemoryPayload(
    executeOutputRequest,
    executeAluResult,
    executeBranchTaken,
    executeBranchTarget,
    Cat(executeJalrTargetRaw(cfg.xlen - 1, 1), 0.U(1.W)),
    executeOutputStoreData,
    Mux(directIntegerExecuteFire,
      (if (twoStageIntegerExecute) directExecuteInput.perfDecodeCycles
        else performanceCycle - executeOutputRequest.perfDecodeStartCycle), executeOutputRequest.perfDecodeCycles),
    Mux(directIntegerExecuteFire,
      (if (twoStageIntegerExecute) performanceCycle - directExecuteInput.perfExecuteStartCycle + 1.U
        else 1.U(64.W)), performanceCycle - executeOutputRequest.perfExecuteStartCycle),
    arithmeticResponseAvailable,
    arithmeticResponseIllegal,
    executeOutputIsControl
  )
  // 三拍串行执行的 EX2 只消费 serialExecuteControlReg 的输出。这里不能复用上面的
  // 通用选择网络，否则 executeState 会重新进入该宽 payload 的写入锥。
  val serialJalrTargetRaw = serialComputeRequest.rs1Data + serialComputeRequest.immediate
  val serialComputedExecuteMemory = executeMemoryPayload(
    serialComputeRequest,
    serialExecuteResult,
    serialBranchTaken,
    serialComputeRequest.pc + serialComputeRequest.immediate,
    Cat(serialJalrTargetRaw(cfg.xlen - 1, 1), 0.U(1.W)),
    serialComputeRequest.storeData,
    serialComputeRequest.perfDecodeCycles,
    performanceCycle - serialComputeRequest.perfExecuteStartCycle,
    false.B,
    false.B,
    true.B
  )

  if (threeStageSerialExecute) {
    serialExecuteControlReg.io.in.valid := executeState === executeSerialDispatch && !frontendRedirectValid
    serialExecuteControlReg.io.in.bits := executeRequestReg
    serialExecuteControlReg.io.out.ready := serialExecuteResultReg.io.in.ready
    serialExecuteResultReg.io.in.valid := serialExecuteControlReg.io.out.valid && !frontendRedirectValid
    serialExecuteResultReg.io.in.bits := serialComputedExecuteMemory
  } else {
    serialExecuteResultReg.io.in.valid := twoStageSerialExecute.B && executeState === executeDone &&
      !frontendRedirectValid
    serialExecuteResultReg.io.in.bits := computedExecuteMemory
  }
  serialExecuteResultReg.io.out.ready := executeMemoryReg.io.in.ready && !executeMemoryRedirectPending
  val serialExecuteResultAvailable = pipelinedSerialExecute.B && serialExecuteResultReg.io.out.valid
  val executeMemoryInput = Wire(new ExecuteMemoryPayload(cfg))
  executeMemoryInput := computedExecuteMemory
  when(serialExecuteResultAvailable) {
    executeMemoryInput := serialExecuteResultReg.io.out.bits
    // 串行结果级到 EX/MEM 的这一级也计入执行时间；若因 EX/MEM 反压停留，停顿另计。
    executeMemoryInput.perfExecuteCycles := serialExecuteResultReg.io.out.bits.perfExecuteCycles + 1.U
  }
  executeMemoryInput.perfMemoryStartCycle := performanceCycle
  executeMemoryReg.io.flush := false.B
  executeMemoryReg.io.in.valid := !executeMemoryRedirectPending &&
    (directIntegerExecuteFire || arithmeticResponseAvailable ||
      (if (pipelinedSerialExecute) serialExecuteResultAvailable else executeState === executeDone))
  executeMemoryReg.io.in.bits := executeMemoryInput
  if (twoStageIntegerExecute) {
    // EX1 的 ALU 比较和分支目标已在 EX/MEM 锁存。由该寄存器输出发起 redirect，
    // 将 IntegerAlu -> ProgramCounter 的组合链断在 EX/MEM 边界。
    executeMemoryRedirectPending := executeMemoryReg.io.out.valid &&
      executeMemoryReg.io.out.bits.branch && executeMemoryReg.io.out.bits.branchTaken =/= 0.U
    executeRedirectValid := executeMemoryReg.io.out.fire && executeMemoryRedirectPending
    executeRedirectTarget := Mux(executeMemoryReg.io.out.bits.branchTaken === 2.U,
      executeMemoryReg.io.out.bits.jalrTarget, executeMemoryReg.io.out.bits.branchTarget)
  } else {
    val executeBranchRedirect = executeMemoryReg.io.in.bits.branch &&
      executeMemoryReg.io.in.bits.branchTaken =/= 0.U
    executeRedirectValid := executeMemoryReg.io.in.fire && executeBranchRedirect
    executeRedirectTarget := Mux(executeMemoryReg.io.in.bits.branchTaken === 2.U,
      executeMemoryReg.io.in.bits.jalrTarget, executeMemoryReg.io.in.bits.branchTarget)
  }

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
  val idExBackpressured = decodeExecuteReg.io.out.valid && !decodeExecuteReg.io.out.ready
  val integerExecuteBackpressured = twoStageIntegerExecute.B && integerExecuteReg.io.out.valid &&
    !integerExecuteReg.io.out.ready
  val serialControlBackpressured = threeStageSerialExecute.B && serialExecuteControlReg.io.out.valid &&
    !serialExecuteControlReg.io.out.ready
  val serialExecuteBackpressured = pipelinedSerialExecute.B && serialExecuteResultReg.io.out.valid &&
    !serialExecuteResultReg.io.out.ready
  when(io.dispatch.valid && !io.dispatch.ready) { idStallCycles := idStallCycles + 1.U }
  when(idExBackpressured || integerExecuteBackpressured || serialControlBackpressured ||
    serialExecuteBackpressured) {
    executeStallCycles := executeStallCycles + 1.U
  }
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
  io.debug.idExBackpressured := idExBackpressured
  io.debug.integerExecuteBackpressured := integerExecuteBackpressured
  io.debug.exMemBackpressured := executeMemoryReg.io.out.valid && !executeMemoryReg.io.out.ready
  io.debug.memoryWaitingForLsu := memoryState === memoryWait
  io.debug.lsuTransactionActive := loadStoreUnit.io.busy
  io.debug.serialExecuteActive := executeState =/= executeIdle || serialControlStagePending ||
    serialResultStagePending
}
