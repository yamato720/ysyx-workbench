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
  private val directIntegerWritebackBypass = pipelineConfig.directIntegerWritebackBypass
  private val operatorConfig = config.operators
  private val localM = cfg.M
  private val debugEnabled = config.debug.enableTopDebugIo
  private val axiConfig = config.axi
  private val arithmeticTagWidth = if (localM) operatorConfig.mulDiv.tagWidth else 1

  val io = IO(new Bundle {
    val interrupt = Input(Bool())
    val interruptPc = Input(UInt(cfg.xlen.W))
    val dispatch = Flipped(Decoupled(new DecodedDispatchPayload(cfg)))
    val axi = new AxiLiteMasterIO(axiConfig.addrWidth, axiConfig.dataWidth)
    val redirectValid = Output(Bool())
    val redirectTarget = Output(UInt(cfg.xlen.W))
    val branchResolutionValid = Output(Bool())
    val branchResolutionPc = Output(UInt(cfg.xlen.W))
    val branchResolutionConditional = Output(Bool())
    val branchResolutionJalr = Output(Bool())
    val branchResolutionCall = Output(Bool())
    val branchResolutionReturn = Output(Bool())
    val branchResolutionTaken = Output(Bool())
    val branchResolutionTarget = Output(UInt(cfg.xlen.W))
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
  // M 扩展严格只保留一条在途指令。端点响应先进入该弹性寄存器，再和普通结果一样
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
  val integerAlu = Module(new IntegerAlu(cfg.xlen))
  // 可选的串行 ALU 供 CSR、异常和 mret 路径使用。三拍模式下它只消费
  // serialExecuteControlReg，避免 executeState 经共享输入 mux 重新穿过结果级 ALU。
  val serialIntegerAlu = if (separateSerialIntegerAlu) Some(Module(new IntegerAlu(cfg.xlen))) else None
  val mulDivAlu = if (localM) Some(Module(new MulDivAlu(
    cfg.xlen, operatorConfig.mulDiv, operatorConfig.routes, components.arithmeticIp))) else None
  val csrExecution = Module(new CsrExecution(cfg))
  val csrFile = Module(new CsrFile(cfg))
  val machineExternalInterruptPending = csrFile.io.machineExternalInterruptPending
  private val pipelinedMemory = config.cache.accessMode == CacheAccessMode.PipelinedTwoCycle
  private val outstandingCompletionForwarding = pipelinedMemory &&
    pipelineConfig.forwarding.enableOutstandingCompletionForwarding
  private val outstandingLoadDepth = 4
  require(!outstandingCompletionForwarding || arithmeticTagWidth >= 2,
    "Outstanding completion forwarding requires at least two arithmetic tag bits")
  val pipelinedMemoryStage = if (pipelinedMemory) Some(Module(new PipelinedMemoryStage(
    axiConfig.addrWidth,
    axiConfig.dataWidth,
    config.memory.mainMemoryBase,
    config.memory.mainMemorySize,
    config.cache.pipelinedQueues.memoryDepth,
    cfg = cfg,
    enableOutstandingCompletionForwarding = outstandingCompletionForwarding
  ))) else None
  val loadStoreUnit = if (!pipelinedMemory) Some(Module(new LSUAXIAdapter(
    axiConfig.addrWidth,
    axiConfig.dataWidth,
    config.memory.mainMemoryBase,
    config.memory.mainMemorySize
  ))) else None
  loadStoreUnit.foreach(_.io.axi <> io.axi)
  pipelinedMemoryStage.foreach(_.io.axi <> io.axi)
  val memoryStageBusy = WireDefault(false.B)
  val memoryStageRdata = WireDefault(0.U(cfg.xlen.W))
  val memoryStageFault = WireDefault(0.U.asTypeOf(new MemoryFault(axiConfig.addrWidth)))
  loadStoreUnit.foreach { lsu =>
    memoryStageBusy := lsu.io.busy
    memoryStageRdata := lsu.io.rdata
    memoryStageFault := lsu.io.fault
  }
  pipelinedMemoryStage.foreach { stage =>
    memoryStageBusy := stage.io.busy
    memoryStageRdata := stage.io.response.bits.loadData
    memoryStageFault := stage.io.fault
  }
  io.memoryFault := memoryStageFault

  val dispatch = io.dispatch.bits
  registerFile.io.rs1 := dispatch.rs1
  registerFile.io.rs2 := dispatch.rs2

  val executeIdle :: executeSerialDispatch :: executeDone :: executeArithmeticWait :: Nil = Enum(4)
  val executeState = RegInit(executeIdle)
  val executeRequestReg = Reg(new DecodeExecutePayload(cfg))
  val memoryIdle :: memoryWait :: Nil = Enum(2)
  val memoryState = RegInit(memoryIdle)
  val memoryRequestReg = Reg(new ExecuteMemoryPayload(cfg))
  // 流水 MEM 的四项 load 结果尚未提交时，记录其 rd；只有对应结果进入 WB 后
  // 才允许依赖者读取寄存器文件，独立指令仍可继续进入访存队列。
  val pipelinedLoadScoreboardValid = RegInit(VecInit(Seq.fill(4)(false.B)))
  val pipelinedLoadScoreboardRd = RegInit(VecInit(Seq.fill(4)(0.U(5.W))))
  val pipelinedLoadScoreboardCount = RegInit(0.U(3.W))
  val pipelinedLoadAllocate = WireDefault(false.B)
  val pipelinedLoadAllocateRd = WireDefault(0.U(5.W))
  val pipelinedLoadCommit = WireDefault(false.B)
  // 流水 MEM 的 order FIFO 也会暂存普通 ALU/JAL 写回项。它们离开 EX/MEM 后
  // 仍未进入 WB，必须和 load 一样阻挡同 rd 的年轻指令，避免读到寄存器旧值。
  val pipelinedMemoryWriteScoreboardValid = RegInit(VecInit(Seq.fill(outstandingLoadDepth)(false.B)))
  val pipelinedMemoryWriteScoreboardRd = RegInit(VecInit(Seq.fill(outstandingLoadDepth)(0.U(5.W))))
  val pipelinedMemoryWriteScoreboardCount = RegInit(0.U(3.W))
  val pipelinedMemoryWriteAllocate = WireDefault(false.B)
  val pipelinedMemoryWriteAllocateRd = WireDefault(0.U(5.W))
  val pipelinedMemoryWriteComplete = WireDefault(false.B)

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
  val executeInputIsArithmetic = executeInput.executionUnit === NpcExecutionUnit.multiply ||
      executeInput.executionUnit === NpcExecutionUnit.divide
  val executeInputIsSerial = executeInput.csrEnable || executeInput.trapEnable || executeInput.mretEnable ||
    false.B
  val pipelineMode = pipelineConfig.enablePipeline.B
  val olderInstructionsDrained = (!twoStageIntegerExecute).B || !integerExecuteReg.io.out.valid
  val serialControlStagePending = threeStageSerialExecute.B && serialExecuteControlReg.io.out.valid
  val serialResultStagePending = pipelinedSerialExecute.B && serialExecuteResultReg.io.out.valid
  val serialOlderInstructionsDrained = olderInstructionsDrained && !serialControlStagePending &&
    !serialResultStagePending &&
    !executeMemoryReg.io.out.valid && memoryState === memoryIdle &&
    !memoryWritebackReg.io.out.valid && !memoryStageBusy
  // 关闭完成表时，算术请求仍按旧路径等待全部旧指令排空。开启后请求先分配 MEM
  // 完成槽位，M 扩展的 tag 指向该槽位，因而可与较老访存重叠。
  val arithmeticUnitReady = mulDivAlu.map(_.io.req.ready).getOrElse(false.B)
  val outstandingArithmeticSlotReady = pipelinedMemoryStage.map(_.io.arithmeticSlotAvailable).getOrElse(false.B)
  val arithmeticCanAccept = executeState === executeIdle && arithmeticUnitReady && Mux(
    outstandingCompletionForwarding.B,
    outstandingArithmeticSlotReady && !executeMemoryReg.io.out.valid,
    serialOlderInstructionsDrained)
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
  val pipelinedMemoryResponseAvailable = pipelinedMemoryStage.map(stage =>
    stage.io.response.valid && stage.io.response.bits.writebackFromMemory).getOrElse(false.B)
  val memoryResponseAvailable = if (pipelinedMemory) {
    pipelinedMemoryResponseAvailable && memoryWritebackReg.io.in.ready
  } else {
    memoryState === memoryWait && !memoryStageBusy && memoryWritebackReg.io.in.ready
  }

  private val producerCount = 11
  val forwardingUnit = Module(new ForwardingUnit(cfg.xlen, producerCount))
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

  val hazardUnit = Module(new HazardUnit(producerCount))
  hazardUnit.io.enableInterlock := pipelineConfig.enableInterlock.B
  hazardUnit.io.enableIdForwarding := pipelineConfig.forwarding.enableIdForwarding.B
  hazardUnit.io.enableExecuteForwarding := pipelineConfig.forwarding.enableExecuteForwarding.B
  hazardUnit.io.usesRs1 := dispatch.usesRs1
  hazardUnit.io.usesRs2 := dispatch.usesRs2
  hazardUnit.io.rs1 := dispatch.rs1
  hazardUnit.io.rs2 := dispatch.rs2

  // 生产者从新到旧排列。完成表位于 EX/MEM 与 WB 之间；其四项候选即使未完成
  // 也必须保留为生产者，以阻断对同 rd 的更老值的错误选择。
  val completionCandidates = (0 until outstandingLoadDepth).map { index =>
    pipelinedMemoryStage.map(_.io.completionCandidates(index)).getOrElse(
      0.U.asTypeOf(new OutstandingCompletionCandidate(cfg.xlen)))
  }
  val producerValid = Seq(
    decodeExecuteReg.io.out.valid,
    if (twoStageIntegerExecute) integerExecuteReg.io.out.valid else false.B,
    executeState =/= executeIdle,
    serialResultStagePending,
    executeMemoryReg.io.out.valid
  ) ++ completionCandidates.map(candidate => outstandingCompletionForwarding.B && candidate.valid) ++ Seq(
    memoryState === memoryWait && !pipelinedMemory.B,
    memoryWritebackReg.io.out.valid
  )
  val producerWritesRd = Seq(
    decodeExecuteReg.io.out.bits.registerWriteEnable,
    integerExecuteReg.io.out.bits.registerWriteEnable,
    executeRequestReg.registerWriteEnable,
    serialExecuteResultReg.io.out.bits.registerWriteEnable,
    executeMemoryReg.io.out.bits.registerWriteEnable
  ) ++ completionCandidates.map(_.writesRd) ++ Seq(
    memoryRequestReg.registerWriteEnable,
    memoryWritebackReg.io.out.bits.registerWriteEnable
  )
  val producerRd = Seq(
    decodeExecuteReg.io.out.bits.rd,
    integerExecuteReg.io.out.bits.rd,
    executeRequestReg.rd,
    serialExecuteResultReg.io.out.bits.rd,
    executeMemoryReg.io.out.bits.rd
  ) ++ completionCandidates.map(_.rd) ++ Seq(
    memoryRequestReg.rd,
    memoryWritebackReg.io.out.bits.rd
  )
  val idCandidateAvailable = Seq(
    directExecuteWillFire && executeInput.executionUnit === NpcExecutionUnit.integer && !executeInput.writebackFromMemory,
    false.B,
    serialExecuteResultForwarding.B && !pipelinedSerialExecute.B && executeState === executeDone,
    serialExecuteResultForwarding.B && serialResultStagePending,
    !executeMemoryReg.io.out.bits.writebackFromMemory
  ) ++ completionCandidates.map(_.dataValid) ++ Seq(
    memoryResponseAvailable,
    true.B
  )
  val dispatchIsArithmetic = dispatch.executionUnit === NpcExecutionUnit.multiply ||
    dispatch.executionUnit === NpcExecutionUnit.divide
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
    incomingCanExecuteDirectNextCycle && !executeMemoryReg.io.out.bits.writebackFromMemory
  ) ++ completionCandidates.map(_.dataValid) ++ Seq(
    incomingCanExecuteDirectNextCycle && memoryResponseAvailable,
    false.B
  )
  val idCandidateData = Seq(
    directForwardData,
    0.U(cfg.xlen.W),
    serialForwardData,
    serialExecuteResultForwardData,
    executeMemoryForwardData
  ) ++ completionCandidates.map(_.data) ++ Seq(
    memoryStageRdata,
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

  for (index <- 0 until producerCount) {
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
  for (index <- 4 until producerCount) {
    driveCandidate(
      forwardingUnit.io.executeCandidates(index),
      producerValid(index),
      producerWritesRd(index),
      producerRd(index),
      idCandidateData(index),
      idCandidateAvailable(index)
    )
  }
  val pipelinedLoadResponseReady = pipelinedMemoryStage.map(stage =>
    stage.io.response.valid && stage.io.response.bits.writebackFromMemory).getOrElse(false.B)
  val pipelinedLoadResponseRd = pipelinedMemoryStage.map(_.io.response.bits.rd).getOrElse(0.U(5.W))
  val pipelinedLoadHazard = if (pipelinedMemory && !outstandingCompletionForwarding) {
    val rs1Hazard = dispatch.usesRs1 && dispatch.rs1 =/= 0.U &&
      pipelinedLoadScoreboardValid.zip(pipelinedLoadScoreboardRd).map {
        case (valid, rd) => valid && rd === dispatch.rs1
      }.reduce(_ || _) && !(pipelinedLoadResponseReady && pipelinedLoadResponseRd === dispatch.rs1)
    val rs2Hazard = dispatch.usesRs2 && dispatch.rs2 =/= 0.U &&
      pipelinedLoadScoreboardValid.zip(pipelinedLoadScoreboardRd).map {
        case (valid, rd) => valid && rd === dispatch.rs2
      }.reduce(_ || _) && !(pipelinedLoadResponseReady && pipelinedLoadResponseRd === dispatch.rs2)
    rs1Hazard || rs2Hazard
  } else false.B
  val pipelinedMemoryWriteHazard = if (pipelinedMemory && !outstandingCompletionForwarding) {
    val rs1Hazard = dispatch.usesRs1 && dispatch.rs1 =/= 0.U &&
      pipelinedMemoryWriteScoreboardValid.zip(pipelinedMemoryWriteScoreboardRd).map {
        case (valid, rd) => valid && rd === dispatch.rs1
      }.reduce(_ || _)
    val rs2Hazard = dispatch.usesRs2 && dispatch.rs2 =/= 0.U &&
      pipelinedMemoryWriteScoreboardValid.zip(pipelinedMemoryWriteScoreboardRd).map {
        case (valid, rd) => valid && rd === dispatch.rs2
      }.reduce(_ || _)
    rs1Hazard || rs2Hazard
  } else false.B
  val busyAfterDecode = decodeExecuteReg.io.out.valid || integerExecuteReg.io.out.valid ||
    serialExecuteControlReg.io.out.valid || serialExecuteResultReg.io.out.valid ||
    (executeState =/= executeIdle) || executeMemoryReg.io.out.valid ||
    (memoryState =/= memoryIdle && !pipelinedMemory.B) || memoryWritebackReg.io.out.valid || memoryStageBusy
  val decodeCanIssue = Mux(
    pipelineConfig.enablePipeline.B,
    !hazardUnit.io.stall && !pipelinedLoadHazard &&
      !pipelinedMemoryWriteHazard &&
      !redirectBarrier && !machineExternalInterruptPending && !frontendRedirectValid &&
      executeState =/= executeArithmeticWait,
    !busyAfterDecode && !machineExternalInterruptPending && !frontendRedirectValid
  )
  decodeExecuteReg.io.flush := frontendRedirectValid
  decodeExecuteReg.io.in.valid := io.dispatch.valid && decodeCanIssue
  io.dispatch.ready := decodeCanIssue && decodeExecuteReg.io.in.ready
  decodeExecuteReg.io.in.bits.pc := dispatch.pc
  decodeExecuteReg.io.in.bits.instruction := dispatch.instruction
  decodeExecuteReg.io.in.bits.predictedNextPc := dispatch.predictedNextPc
  decodeExecuteReg.io.in.bits.perfFetchStartCycle := dispatch.perfFetchStartCycle
  decodeExecuteReg.io.in.bits.perfFetchCycles := dispatch.perfFetchCycles
  decodeExecuteReg.io.in.bits.perfDecodeStartCycle := dispatch.perfDecodeStartCycle
  decodeExecuteReg.io.in.bits.perfDecodeCycles := 0.U
  // 单级 EX 的 ID/EX 在本拍锁存，ALU 组合逻辑从下一拍开始。性能时间线必须把
  // EX 起点放在该寄存器边界之后，不能与刚完成的 ID 驻留重叠。
  decodeExecuteReg.io.in.bits.perfExecuteStartCycle := Mux(
    twoStageIntegerExecute.B, 0.U(64.W), performanceCycle + 1.U(64.W))
  decodeExecuteReg.io.in.bits.rs1Data := forwardingUnit.io.idRs1Forwarded
  decodeExecuteReg.io.in.bits.storeData := forwardingUnit.io.idRs2Forwarded
  decodeExecuteReg.io.in.bits.operandC := 0.U
  decodeExecuteReg.io.in.bits.immediate := dispatch.immediate
  decodeExecuteReg.io.in.bits.rd := dispatch.rd
  decodeExecuteReg.io.in.bits.rs1 := dispatch.rs1
  decodeExecuteReg.io.in.bits.rs2 := dispatch.rs2
  decodeExecuteReg.io.in.bits.rs3 := dispatch.rs3
  decodeExecuteReg.io.in.bits.usesRs1 := dispatch.usesRs1
  decodeExecuteReg.io.in.bits.usesRs2 := dispatch.usesRs2
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
    // EX0 接收时完成普通整数操作数选择。
    integerExecuteReg.io.in.valid := decodeExecuteFire && !executeInputIsSerial && !executeInputIsArithmetic
    integerExecuteReg.io.in.bits := executeInput
    integerExecuteReg.io.in.bits.rs1Data := forwardingUnit.io.executeRs1Forwarded
    integerExecuteReg.io.in.bits.storeData := forwardingUnit.io.executeRs2Forwarded
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
    alu.io.req.bits.pc := executeInput.pc
    alu.io.req.bits.instruction := executeInput.instruction
    alu.io.req.bits.tag := Mux(outstandingCompletionForwarding.B,
      pipelinedMemoryStage.map(_.io.arithmeticAllocateTag).getOrElse(0.U), 0.U)
  }

  val emptyArithmeticResponse = 0.U.asTypeOf(new ArithmeticResponse(cfg.xlen, arithmeticTagWidth))
  val mulDivResponse = mulDivAlu.map(_.io.resp.bits).getOrElse(emptyArithmeticResponse)
  val mulDivResponseValid = mulDivAlu.map(_.io.resp.valid).getOrElse(false.B)
  pipelinedMemoryStage.foreach { stage =>
    stage.io.arithmeticCompletion(0).valid := outstandingCompletionForwarding.B && mulDivResponseValid
    stage.io.arithmeticCompletion(0).bits.tag := mulDivResponse.tag
    stage.io.arithmeticCompletion(0).bits.result := mulDivResponse.result
    stage.io.arithmeticCompletion(0).bits.illegal := mulDivResponse.illegal
    stage.io.arithmeticCompletion(1).valid := false.B
    stage.io.arithmeticCompletion(1).bits := 0.U.asTypeOf(stage.io.arithmeticCompletion(1).bits)
  }
  val arithmeticResponseActive = executeState === executeArithmeticWait && !outstandingCompletionForwarding.B
  arithmeticResponseReg.io.flush := false.B
  arithmeticResponseReg.io.in.valid := arithmeticResponseActive && mulDivResponseValid
  arithmeticResponseReg.io.in.bits := mulDivResponse
  // 关闭时维持已锁存请求的单响应路径；开启时由完成表按 tag 回填。
  val mulDivCompletionReady = pipelinedMemoryStage.map(_.io.arithmeticCompletion(0).ready).getOrElse(false.B)
  mulDivAlu.foreach(_.io.resp.ready := Mux(outstandingCompletionForwarding.B, mulDivCompletionReady,
    arithmeticResponseActive && arithmeticResponseReg.io.in.ready))
  arithmeticResponseReg.io.out.ready := executeMemoryReg.io.in.ready && !executeMemoryRedirectPending
  val serialExecuteComplete = if (pipelinedSerialExecute) serialExecuteResultReg.io.in.fire
    else executeMemoryReg.io.in.fire

  when(serialExecuteAccept) {
    executeRequestReg := executeInput
    executeRequestReg.perfDecodeCycles := performanceCycle - executeInput.perfDecodeStartCycle
    executeRequestReg.perfExecuteStartCycle := performanceCycle
    executeState := (if (threeStageSerialExecute) executeSerialDispatch else executeDone)
  }.elsewhen(arithmeticIssue) {
    when(!outstandingCompletionForwarding.B) {
      executeRequestReg := arithmeticIssuePayload
      executeState := executeArithmeticWait
    }
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
  csrExecution.io.trapRequested := executeRequest.trapEnable
  csrExecution.io.requestedTrapCause := executeRequest.trapCause
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
  val executeOutputStoreData = Mux(directIntegerExecuteFire, directRs2Data,
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
    payload.predictedNextPc := request.predictedNextPc
    payload.perfFetchStartCycle := request.perfFetchStartCycle
    payload.perfFetchCycles := request.perfFetchCycles
    payload.perfDecodeStartCycle := request.perfDecodeStartCycle
    payload.perfDecodeCycles := perfDecodeCycles
    payload.perfExecuteStartCycle := request.perfExecuteStartCycle
    payload.perfExecuteCycles := perfExecuteCycles
    payload.perfMemoryStartCycle := performanceCycle
    payload.perfMemoryQueueStartCycle := performanceCycle
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
      (if (twoStageIntegerExecute) performanceCycle - directExecuteInput.perfExecuteStartCycle
        else 1.U(64.W)), performanceCycle - executeOutputRequest.perfExecuteStartCycle),
    arithmeticResponseAvailable,
    arithmeticResponseIllegal,
    executeOutputIsControl
  )
  // M/F 在发射拍就把完整架构载荷写入完成表；结果、异常标志和 illegal 随后仅按 tag
  // 回填该槽位，因此响应无需重新穿过 EX/MEM。
  val outstandingArithmeticPayload = executeMemoryPayload(
    arithmeticIssuePayload,
    0.U(cfg.xlen.W),
    NpcBranchResult.notTaken,
    arithmeticIssuePayload.pc + arithmeticIssuePayload.immediate,
    Cat((arithmeticIssuePayload.rs1Data + arithmeticIssuePayload.immediate)(cfg.xlen - 1, 1), 0.U(1.W)),
    arithmeticIssuePayload.storeData,
    arithmeticIssuePayload.perfDecodeCycles,
    0.U,
    false.B,
    false.B,
    false.B
  )
  pipelinedMemoryStage.foreach { stage =>
    stage.io.arithmeticRequest.valid := outstandingCompletionForwarding.B && arithmeticIssue
    stage.io.arithmeticRequest.bits := outstandingArithmeticPayload
  }
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
  executeMemoryInput.perfMemoryQueueStartCycle := performanceCycle
  // 该旁路只能处理会写 GPR 的纯整数指令。FENCE、分支、访存和串行控制仍必须
  // 经过 EX/MEM，保持维护、redirect 与异常的原有顺序边界。
  // 这里故意只观察已经锁存的 ID/EX 载荷和寄存器状态，不能使用
  // `directIntegerExecuteFire`。后者经 EX/MEM 的 ready 反馈形成组合环。
  val directIntegerWritebackCandidate = directIntegerWritebackBypass.B && !twoStageIntegerExecute.B &&
    pipelineMode && decodeExecuteReg.io.out.valid && !executeInputIsSerial && !executeInputIsArithmetic &&
    executeInput.registerWriteEnable && !executeInput.branch && !executeInput.loadEnable &&
    !executeInput.storeEnable && executeState === executeIdle && !serialControlStagePending &&
    !serialResultStagePending && !executeMemoryReg.io.out.valid && !commitRedirectValid
  val directIntegerWriteback = WireDefault(false.B)
  executeMemoryReg.io.flush := false.B
  executeMemoryReg.io.in.valid := !directIntegerWriteback && !executeMemoryRedirectPending &&
    (directIntegerExecuteFire || arithmeticResponseAvailable ||
      (if (pipelinedSerialExecute) serialExecuteResultAvailable else executeState === executeDone))
  executeMemoryReg.io.in.bits := executeMemoryInput
  if (twoStageIntegerExecute) {
    // 两级整数路径不做前端预测。EX/MEM 中的已决控制流只有实际跳转时才恢复，
    // 保持该路径原有的 IntegerAlu -> EX/MEM -> ProgramCounter 时序边界。
    executeMemoryRedirectPending := executeMemoryReg.io.out.valid &&
      executeMemoryReg.io.out.bits.branch && executeMemoryReg.io.out.bits.branchTaken =/= 0.U
    executeRedirectValid := executeMemoryReg.io.out.fire && executeMemoryRedirectPending
    executeRedirectTarget := Mux(executeMemoryReg.io.out.bits.branchTaken === 2.U,
      executeMemoryReg.io.out.bits.jalrTarget, executeMemoryReg.io.out.bits.branchTarget)
  } else {
    val executeActualNextPc = Mux(executeMemoryReg.io.in.bits.branchTaken =/= 0.U,
      Mux(executeMemoryReg.io.in.bits.branchTaken === 2.U,
        executeMemoryReg.io.in.bits.jalrTarget, executeMemoryReg.io.in.bits.branchTarget),
      executeMemoryReg.io.in.bits.pc + 4.U)
    val executeBranchRecovery = executeMemoryReg.io.in.bits.branch &&
      executeActualNextPc =/= executeMemoryReg.io.in.bits.predictedNextPc
    executeRedirectValid := executeMemoryReg.io.in.fire && executeBranchRecovery
    executeRedirectTarget := executeActualNextPc
  }

  def driveMemoryWritebackPayload(dst: MemoryWritebackPayload, src: ExecuteMemoryPayload, memData: UInt): Unit = {
    val branchNextPc = Mux(src.branchTaken === 2.U, src.jalrTarget, src.branchTarget)
    dst.pc := src.pc
    dst.instruction := src.instruction
    dst.perfFetchStartCycle := src.perfFetchStartCycle
    dst.perfFetchCycles := src.perfFetchCycles
    dst.perfDecodeStartCycle := src.perfDecodeStartCycle
    dst.perfDecodeCycles := src.perfDecodeCycles
    dst.perfExecuteStartCycle := src.perfExecuteStartCycle
    dst.perfExecuteCycles := src.perfExecuteCycles
    dst.perfMemoryStartCycle := src.perfMemoryStartCycle
    dst.perfMemoryCycles := performanceCycle - src.perfMemoryStartCycle
    dst.perfMemoryQueueStartCycle := src.perfMemoryQueueStartCycle
    dst.perfMemoryServiceStartCycle := src.perfMemoryStartCycle
    dst.perfMemoryQueueCycles := 0.U
    dst.perfMemoryServiceCycles := performanceCycle - src.perfMemoryStartCycle
    dst.perfWritebackStartCycle := performanceCycle
    dst.nextPc := Mux(src.branch && src.branchTaken =/= 0.U, branchNextPc, src.pc + 4.U)
    dst.rd := src.rd
    dst.aluResult := src.aluResult
    dst.storeData := src.storeData
    dst.storeEnable := src.storeEnable
    dst.storeAccessType := src.funct3
    dst.loadData := memData
    dst.csrReadData := src.csrReadData
    dst.writebackFromMemory := src.writebackFromMemory
    dst.registerWriteEnable := src.registerWriteEnable
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
  if (pipelinedMemory) {
    val stage = pipelinedMemoryStage.get
    stage.io.cycle := performanceCycle
    stage.io.flush := false.B
    // Scoreboard 满时只阻止新的 load 进入 MEM；已有 store 和非访存指令仍可
    // 保持队列顺序，避免四项 outstanding 被错误地退化成单项阻塞。
    val loadSlotAvailable = outstandingCompletionForwarding.B ||
      pipelinedLoadScoreboardCount =/= outstandingLoadDepth.U || pipelinedLoadCommit
    val memoryStageInputAllowed = !memoryAccess || !executeMemoryReg.io.out.bits.loadEnable ||
      loadSlotAvailable
    // 只有 MEM 的所有队列均已排空时，非访存项才能直通 MEM/WB。若存在较老 load/store，
    // 它仍必须进入 orderQueue，不能在提交顺序上越过较老访存或其可能产生的 fault。
    val nonMemoryWritebackBypass = !outstandingCompletionForwarding.B &&
      executeMemoryReg.io.out.valid && !memoryAccess && stage.io.retirementDrained
    // EX->WB 只在没有旧的 EX/MEM 或 MEM 项时可用。MEM/WB 即使正在提交更老的
    // 普通整数项也能同拍接收新项，PipelineRegister 会在该时钟边界保持提交顺序。
    directIntegerWriteback := directIntegerWritebackCandidate && stage.io.retirementDrained &&
      !stage.io.response.valid && memoryWritebackReg.io.in.ready
    executeMemoryReg.io.out.ready := Mux(nonMemoryWritebackBypass,
      memoryWritebackReg.io.in.ready, stage.io.request.ready && memoryStageInputAllowed)
    stage.io.request.valid := executeMemoryReg.io.out.valid && !nonMemoryWritebackBypass &&
      memoryStageInputAllowed
    stage.io.request.bits := executeMemoryReg.io.out.bits
    stage.io.response.ready := memoryWritebackReg.io.in.ready
    when(directIntegerWriteback) {
      memoryWritebackReg.io.in.valid := true.B
      driveMemoryWritebackPayload(memoryWritebackReg.io.in.bits, executeMemoryInput, 0.U(cfg.xlen.W))
      memoryWritebackReg.io.in.bits.perfMemoryCycles := 0.U
      memoryWritebackReg.io.in.bits.perfMemoryQueueCycles := 0.U
      memoryWritebackReg.io.in.bits.perfMemoryServiceCycles := 0.U
    }.elsewhen(nonMemoryWritebackBypass) {
      memoryWritebackReg.io.in.valid := true.B
      driveMemoryWritebackPayload(memoryWritebackReg.io.in.bits, executeMemoryReg.io.out.bits,
        0.U(cfg.xlen.W))
      // 该路径不占用 D$、AXI 或 MEM 完成 FIFO；MEM service 和 queue 均应保持为零。
      memoryWritebackReg.io.in.bits.perfMemoryCycles := 0.U
      memoryWritebackReg.io.in.bits.perfMemoryQueueCycles := 0.U
      memoryWritebackReg.io.in.bits.perfMemoryServiceCycles := 0.U
    }.otherwise {
      memoryWritebackReg.io.in.valid := stage.io.response.valid
      memoryWritebackReg.io.in.bits := stage.io.response.bits
    }
    pipelinedLoadAllocate := stage.io.request.fire &&
      stage.io.request.bits.loadEnable && stage.io.request.bits.registerWriteEnable &&
      stage.io.request.bits.rd =/= 0.U
    pipelinedLoadAllocateRd := stage.io.request.bits.rd
    pipelinedMemoryWriteAllocate := stage.io.request.fire &&
      stage.io.request.bits.registerWriteEnable && stage.io.request.bits.rd =/= 0.U
    pipelinedMemoryWriteAllocateRd := stage.io.request.bits.rd
    pipelinedMemoryWriteComplete := stage.io.response.fire &&
      stage.io.response.bits.registerWriteEnable && stage.io.response.bits.rd =/= 0.U
  } else {
    val lsu = loadStoreUnit.get
    val memoryStart = memoryState === memoryIdle && executeMemoryReg.io.out.fire && memoryAccess
    lsu.io.start := memoryStart
    lsu.io.addr := Mux(memoryStart, executeMemoryReg.io.out.bits.aluResult(31, 0), memoryRequestReg.aluResult(31, 0))
    lsu.io.wdata := Mux(memoryStart, executeMemoryReg.io.out.bits.storeData, memoryRequestReg.storeData)
    lsu.io.accessType := Mux(memoryStart, executeMemoryReg.io.out.bits.funct3, memoryRequestReg.funct3)
    lsu.io.memRead := Mux(memoryStart, executeMemoryReg.io.out.bits.loadEnable, memoryRequestReg.loadEnable)
    lsu.io.memWrite := Mux(memoryStart, executeMemoryReg.io.out.bits.storeEnable, memoryRequestReg.storeEnable)
    when(memoryState === memoryIdle) {
      when(executeMemoryReg.io.out.valid && memoryAccess) {
        executeMemoryReg.io.out.ready := !lsu.io.busy
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
      memoryWritebackReg.io.in.valid := !lsu.io.busy
      driveMemoryWritebackPayload(memoryWritebackReg.io.in.bits, memoryRequestReg, lsu.io.rdata)
      when(memoryWritebackReg.io.in.fire) { memoryState := memoryIdle }
    }
  }

  memoryWritebackReg.io.out.ready := true.B
  val commitFire = memoryWritebackReg.io.out.fire
  pipelinedLoadCommit := pipelinedMemory.B && commitFire &&
    memoryWritebackReg.io.out.bits.writebackFromMemory &&
    memoryWritebackReg.io.out.bits.registerWriteEnable &&
    memoryWritebackReg.io.out.bits.rd =/= 0.U
  when(pipelinedMemory.B) {
    when(pipelinedLoadCommit) {
      for (index <- 0 until outstandingLoadDepth - 1) {
        pipelinedLoadScoreboardValid(index) := pipelinedLoadScoreboardValid(index + 1)
        pipelinedLoadScoreboardRd(index) := pipelinedLoadScoreboardRd(index + 1)
      }
      pipelinedLoadScoreboardValid(outstandingLoadDepth - 1) := false.B
      when(pipelinedLoadAllocate) {
        // 同拍提交一项并接收一项时，队列长度不变；新项占据提交后队尾。
        when(pipelinedLoadScoreboardCount =/= 0.U) {
          val appendIndex = (pipelinedLoadScoreboardCount - 1.U)(1, 0)
          pipelinedLoadScoreboardValid(appendIndex) := true.B
          pipelinedLoadScoreboardRd(appendIndex) := pipelinedLoadAllocateRd
        }
      }.otherwise {
        pipelinedLoadScoreboardCount := pipelinedLoadScoreboardCount - 1.U
      }
    }.elsewhen(pipelinedLoadAllocate) {
      // 未满时 count 只能是 0..3，因此低两位就是合法的 Vec 索引。
      pipelinedLoadScoreboardValid(pipelinedLoadScoreboardCount(1, 0)) := true.B
      pipelinedLoadScoreboardRd(pipelinedLoadScoreboardCount(1, 0)) := pipelinedLoadAllocateRd
      pipelinedLoadScoreboardCount := pipelinedLoadScoreboardCount + 1.U
    }

    when(pipelinedMemoryWriteComplete) {
      for (index <- 0 until outstandingLoadDepth - 1) {
        pipelinedMemoryWriteScoreboardValid(index) := pipelinedMemoryWriteScoreboardValid(index + 1)
        pipelinedMemoryWriteScoreboardRd(index) := pipelinedMemoryWriteScoreboardRd(index + 1)
      }
      pipelinedMemoryWriteScoreboardValid(outstandingLoadDepth - 1) := false.B
      when(pipelinedMemoryWriteAllocate) {
        // 同拍完成一项并接收一项时，写回 scoreboard 保持原长度，新项进入队尾。
        when(pipelinedMemoryWriteScoreboardCount =/= 0.U) {
          val appendIndex = (pipelinedMemoryWriteScoreboardCount - 1.U)(1, 0)
          pipelinedMemoryWriteScoreboardValid(appendIndex) := true.B
          pipelinedMemoryWriteScoreboardRd(appendIndex) := pipelinedMemoryWriteAllocateRd
        }
      }.otherwise {
        pipelinedMemoryWriteScoreboardCount := pipelinedMemoryWriteScoreboardCount - 1.U
      }
    }.elsewhen(pipelinedMemoryWriteAllocate) {
      // order FIFO 未满时 count 只能是 0..3，低两位可直接作为 Vec 索引。
      pipelinedMemoryWriteScoreboardValid(pipelinedMemoryWriteScoreboardCount(1, 0)) := true.B
      pipelinedMemoryWriteScoreboardRd(pipelinedMemoryWriteScoreboardCount(1, 0)) :=
        pipelinedMemoryWriteAllocateRd
      pipelinedMemoryWriteScoreboardCount := pipelinedMemoryWriteScoreboardCount + 1.U
    }
  }
  val commitWriteData = Mux(memoryWritebackReg.io.out.bits.csrReadWritebackEnable,
    memoryWritebackReg.io.out.bits.csrReadData,
    Mux(memoryWritebackReg.io.out.bits.writebackFromMemory,
      memoryWritebackReg.io.out.bits.loadData, memoryWritebackReg.io.out.bits.aluResult))
  commitForwardData := commitWriteData
  registerFile.io.rd := memoryWritebackReg.io.out.bits.rd
  registerFile.io.writeData := commitWriteData
  registerFile.io.writeEnable := memoryWritebackReg.io.out.bits.registerWriteEnable
  registerFile.io.commit := commitFire
  csrFile.io.address := Mux(serialExecuteAccept, executeRequest.csrAddress,
    Mux(commitFire && memoryWritebackReg.io.out.bits.csrWriteEnable,
      memoryWritebackReg.io.out.bits.csrAddress, dispatch.csrAddress))
  csrFile.io.writeData := memoryWritebackReg.io.out.bits.csrWriteData
  csrFile.io.writeEnable := commitFire && memoryWritebackReg.io.out.bits.csrWriteEnable
  csrFile.io.accessAllowed := memoryWritebackReg.io.out.bits.csrAccessAllowed
  csrFile.io.externalInterrupt := io.interrupt
  // 除 WB 外的旧指令均已完成。若 WB 有效，它会在此拍成为最后一条提交；
  // 若 WB 也为空，中断可直接发生在前端给出的下一条指令边界。
  val interruptPipelineDrained = !decodeExecuteReg.io.out.valid && !integerExecuteReg.io.out.valid &&
    !serialExecuteControlReg.io.out.valid && !serialExecuteResultReg.io.out.valid &&
    !arithmeticResponseReg.io.out.valid && executeState === executeIdle &&
    !executeMemoryReg.io.out.valid && memoryState === memoryIdle && !memoryStageBusy
  val commitSynchronousTrap = memoryWritebackReg.io.out.bits.trapEnable
  val commitMret = memoryWritebackReg.io.out.bits.mretEnable
  val commitExternalInterrupt = commitFire && machineExternalInterruptPending &&
    interruptPipelineDrained && !commitSynchronousTrap && !commitMret
  val idleExternalInterrupt = !memoryWritebackReg.io.out.valid && machineExternalInterruptPending &&
    interruptPipelineDrained
  val takeExternalInterrupt = commitExternalInterrupt || idleExternalInterrupt
  val machineExternalInterruptCause =
    ((BigInt(1) << (cfg.xlen - 1)) | BigInt(CsrCause.machineExternalInterrupt)).U(cfg.xlen.W)

  csrFile.io.trapEnable := (commitFire && commitSynchronousTrap) || takeExternalInterrupt
  csrFile.io.trapCause := Mux(takeExternalInterrupt, machineExternalInterruptCause,
    memoryWritebackReg.io.out.bits.trapCause)
  csrFile.io.trapEpc := Mux(takeExternalInterrupt,
    Mux(commitExternalInterrupt, memoryWritebackReg.io.out.bits.nextPc, io.interruptPc),
    memoryWritebackReg.io.out.bits.trapEpc)
  csrFile.io.mret := commitFire && commitMret
  commitRedirectValid := takeExternalInterrupt || (commitFire && (commitSynchronousTrap || commitMret))
  commitRedirectTarget := Mux(takeExternalInterrupt, csrFile.io.externalInterruptTrapVector,
    Mux(commitSynchronousTrap, csrFile.io.trapVector, csrFile.io.machineExceptionPc))
  val commitNextPc = Mux(takeExternalInterrupt, csrFile.io.externalInterruptTrapVector,
    Mux(commitSynchronousTrap, csrFile.io.trapVector,
      Mux(commitMret, csrFile.io.machineExceptionPc,
      memoryWritebackReg.io.out.bits.nextPc))
  )
  // 较早的 branch 可能在较年轻的 trap/mret 已进入 ID/EX 后发出 redirect。后者会被
  // 冲刷，因此不能保留其 dispatch 时建立的屏障，否则 redirect 后的取指流会永久阻塞。
  when(frontendRedirectValid) {
    redirectBarrier := false.B
  }.elsewhen(io.dispatch.fire && (dispatch.trapEnable || dispatch.mretEnable)) {
    redirectBarrier := true.B
  }

  val commitValidDebug = RegNext(commitFire, false.B)
  val commitPcDebug = RegEnable(memoryWritebackReg.io.out.bits.pc, 0.U(cfg.xlen.W), commitFire)
  val commitInstDebug = RegEnable(memoryWritebackReg.io.out.bits.instruction, 0.U(32.W), commitFire)
  val commitNextPcDebug = RegEnable(commitNextPc, 0.U(cfg.xlen.W), commitFire)
  val commitStore = commitFire && memoryWritebackReg.io.out.bits.storeEnable
  val commitStoreValidDebug = RegNext(commitStore, false.B)
  val commitStoreAddressDebug = RegEnable(
    memoryWritebackReg.io.out.bits.aluResult, 0.U(cfg.xlen.W), commitStore)
  val commitStoreMaskDebug = RegEnable(AxiLiteWstrb.genStrb(
    memoryWritebackReg.io.out.bits.storeAccessType,
    memoryWritebackReg.io.out.bits.aluResult(log2Ceil(cfg.xlen / 8) - 1, 0), cfg.xlen),
    0.U((cfg.xlen / 8).W), commitStore)
  val commitStoreDataDebug = RegEnable(AxiLiteWstrb.alignData(
    memoryWritebackReg.io.out.bits.storeData,
    memoryWritebackReg.io.out.bits.aluResult(log2Ceil(cfg.xlen / 8) - 1, 0), cfg.xlen),
    0.U(cfg.xlen.W), commitStore)
  val commitFetchCyclesDebug = RegEnable(memoryWritebackReg.io.out.bits.perfFetchCycles, 0.U(64.W), commitFire)
  val commitFetchStartCycleDebug = RegEnable(
    memoryWritebackReg.io.out.bits.perfFetchStartCycle, 0.U(64.W), commitFire)
  val commitDecodeStartCycleDebug = RegEnable(
    memoryWritebackReg.io.out.bits.perfDecodeStartCycle, 0.U(64.W), commitFire)
  val commitExecuteStartCycleDebug = RegEnable(
    memoryWritebackReg.io.out.bits.perfExecuteStartCycle, 0.U(64.W), commitFire)
  val commitMemoryStartCycleDebug = RegEnable(
    memoryWritebackReg.io.out.bits.perfMemoryStartCycle, 0.U(64.W), commitFire)
  val commitMemoryQueueStartCycleDebug = RegEnable(
    memoryWritebackReg.io.out.bits.perfMemoryQueueStartCycle, 0.U(64.W), commitFire)
  val commitMemoryServiceStartCycleDebug = RegEnable(
    memoryWritebackReg.io.out.bits.perfMemoryServiceStartCycle, 0.U(64.W), commitFire)
  val commitWritebackStartCycleDebug = RegEnable(
    memoryWritebackReg.io.out.bits.perfWritebackStartCycle, 0.U(64.W), commitFire)
  val commitDecodeCyclesDebug = RegEnable(memoryWritebackReg.io.out.bits.perfDecodeCycles, 0.U(64.W), commitFire)
  val commitExecuteCyclesDebug = RegEnable(memoryWritebackReg.io.out.bits.perfExecuteCycles, 0.U(64.W), commitFire)
  val commitMemoryCyclesDebug = RegEnable(memoryWritebackReg.io.out.bits.perfMemoryCycles, 0.U(64.W), commitFire)
  val commitMemoryQueueCyclesDebug = RegEnable(
    memoryWritebackReg.io.out.bits.perfMemoryQueueCycles, 0.U(64.W), commitFire)
  val commitMemoryServiceCyclesDebug = RegEnable(
    memoryWritebackReg.io.out.bits.perfMemoryServiceCycles, 0.U(64.W), commitFire)
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
  when((executeMemoryReg.io.out.valid && !executeMemoryReg.io.out.ready) ||
    (memoryState === memoryWait && !pipelinedMemory.B) ||
    (pipelinedMemory.B && memoryStageBusy)) {
    memoryStallCycles := memoryStallCycles + 1.U
  }

  io.redirectValid := frontendRedirectValid
  io.redirectTarget := frontendRedirectTarget
  // 仅一拍 EX 的条件分支和 JALR 会在这一拍得到最终 next-PC；前端在下一拍更新方向或
  // 目标表。两拍 EX 路径没有推测取指，保持零输出以避免把更晚的恢复事件误当作训练样本。
  io.branchResolutionValid := !twoStageIntegerExecute.B && directIntegerExecuteFire &&
    executeOutputRequest.branch
  io.branchResolutionPc := executeOutputRequest.pc
  io.branchResolutionConditional := executeOutputRequest.instruction(6, 0) === "b1100011".U
  io.branchResolutionJalr := executeOutputRequest.instruction(6, 0) === "b1100111".U
  val resolvedRd = executeOutputRequest.instruction(11, 7)
  val resolvedRs1 = executeOutputRequest.instruction(19, 15)
  val resolvedJal = executeOutputRequest.instruction(6, 0) === "b1101111".U
  io.branchResolutionCall := (resolvedJal || io.branchResolutionJalr) &&
    (resolvedRd === 1.U || resolvedRd === 5.U)
  io.branchResolutionReturn := io.branchResolutionJalr && resolvedRd === 0.U &&
    (resolvedRs1 === 1.U || resolvedRs1 === 5.U) && executeOutputRequest.instruction(31, 20) === 0.U
  io.branchResolutionTaken := executeBranchTaken =/= NpcBranchResult.notTaken
  io.branchResolutionTarget := Mux(executeBranchTaken === NpcBranchResult.rs1Immediate,
    computedExecuteMemory.jalrTarget, computedExecuteMemory.branchTarget)
  io.debug.registers := registerFile.io.registersOut.get
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
  io.debug.commitStoreValid := commitStoreValidDebug
  io.debug.commitStoreAddress := commitStoreAddressDebug
  io.debug.commitStoreData := commitStoreDataDebug
  io.debug.commitStoreMask := commitStoreMaskDebug
  io.debug.sampleCommitValid := commitFire
  io.debug.sampleCommitPc := memoryWritebackReg.io.out.bits.pc
  io.debug.sampleCommitInstruction := memoryWritebackReg.io.out.bits.instruction
  io.debug.sampleCommitNextPc := commitNextPc
  io.debug.sampleFetchCycles := memoryWritebackReg.io.out.bits.perfFetchCycles
  io.debug.sampleDecodeCycles := memoryWritebackReg.io.out.bits.perfDecodeCycles
  io.debug.sampleExecuteCycles := memoryWritebackReg.io.out.bits.perfExecuteCycles
  io.debug.sampleMemoryCycles := memoryWritebackReg.io.out.bits.perfMemoryCycles
  io.debug.sampleMemoryQueueStartCycle := memoryWritebackReg.io.out.bits.perfMemoryQueueStartCycle
  io.debug.sampleMemoryServiceStartCycle := memoryWritebackReg.io.out.bits.perfMemoryServiceStartCycle
  io.debug.sampleMemoryQueueCycles := memoryWritebackReg.io.out.bits.perfMemoryQueueCycles
  io.debug.sampleMemoryServiceCycles := memoryWritebackReg.io.out.bits.perfMemoryServiceCycles
  io.debug.sampleWritebackCycles := performanceCycle - memoryWritebackReg.io.out.bits.perfWritebackStartCycle
  // `mtestexit` is an FPGA runtime ABI, not a RISC-V exception. It is
  // deliberately derived from the commit payload, so an EBREAK remains an
  // ordinary synchronous breakpoint trap and cannot reset the FPGA core.
  io.debug.completionCommitValid := commitFire &&
    memoryWritebackReg.io.out.bits.csrWriteEnable &&
    memoryWritebackReg.io.out.bits.csrAccessAllowed &&
    memoryWritebackReg.io.out.bits.csrAddress === CsrAddress.mtestexit.U
  io.debug.completionCommitPc := memoryWritebackReg.io.out.bits.pc
  io.debug.completionCommitNextPc := commitNextPc
  io.debug.cycleCount := performanceCycle
  io.debug.commitFetchCycles := commitFetchCyclesDebug
  io.debug.commitFetchStartCycle := commitFetchStartCycleDebug
  io.debug.commitDecodeStartCycle := commitDecodeStartCycleDebug
  io.debug.commitExecuteStartCycle := commitExecuteStartCycleDebug
  io.debug.commitMemoryStartCycle := commitMemoryStartCycleDebug
  io.debug.commitMemoryQueueStartCycle := commitMemoryQueueStartCycleDebug
  io.debug.commitMemoryServiceStartCycle := commitMemoryServiceStartCycleDebug
  io.debug.commitWritebackStartCycle := commitWritebackStartCycleDebug
  io.debug.commitDecodeCycles := commitDecodeCyclesDebug
  io.debug.commitExecuteCycles := commitExecuteCyclesDebug
  io.debug.commitMemoryCycles := commitMemoryCyclesDebug
  io.debug.commitMemoryQueueCycles := commitMemoryQueueCyclesDebug
  io.debug.commitMemoryServiceCycles := commitMemoryServiceCyclesDebug
  io.debug.commitWritebackCycles := commitWritebackCyclesDebug
  io.debug.pipelineFeatures := Cat(pipelineConfig.forwarding.enableExecuteForwarding.B,
    pipelineConfig.forwarding.enableIdForwarding.B, pipelineConfig.enablePipeline.B)
  io.debug.idStallCycles := idStallCycles
  io.debug.executeStallCycles := executeStallCycles
  io.debug.memoryStallCycles := memoryStallCycles
  io.debug.coreBusy := busyAfterDecode || memoryStageBusy
  io.debug.executeAluResult := serialExecuteResult
  io.debug.memoryResult := memoryStageRdata
  io.debug.dispatchBackpressured := io.dispatch.valid && !io.dispatch.ready
  io.debug.idExBackpressured := idExBackpressured
  io.debug.integerExecuteBackpressured := integerExecuteBackpressured
  io.debug.exMemBackpressured := executeMemoryReg.io.out.valid && !executeMemoryReg.io.out.ready
  io.debug.memoryWaitingForLsu := (memoryState === memoryWait && !pipelinedMemory.B) ||
    (pipelinedMemory.B && memoryStageBusy)
  io.debug.lsuTransactionActive := memoryStageBusy
  io.debug.serialExecuteActive := executeState =/= executeIdle || serialControlStagePending ||
    serialResultStagePending
}
