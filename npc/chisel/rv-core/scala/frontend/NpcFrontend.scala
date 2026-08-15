package npc

import npc.ip.memory.MemoryFault

import chisel3._
import chisel3.util._
import npc.protocol._

/** NPC 的取指、译码与架构派发。
  *
  * 本模块刻意止于寄存器文件读取之前。因此其输出是未来重命名、ROB 或发射子系统
  * 可使用的稳定边界。
  */
class NpcFrontend(config: NpcConfig) extends Module {
  private val cfg = config.isa
  private val axiConfig = config.axi
  private val debugEnabled = config.debug.enableTopDebugIo

  val io = IO(new Bundle {
    val redirectValid = Input(Bool())
    val redirectTarget = Input(UInt(cfg.xlen.W))
    val fenceHold = Input(Bool())
    val dispatch = Decoupled(new DecodedDispatchPayload(cfg))
    // 两条已提交指令之间若要接收异步中断，后端以此作为 mepc。取指级至多
    // 缓冲一条尚未派发的指令，因此该值不会跳过任何未提交的架构指令。
    val interruptPc = Output(UInt(cfg.xlen.W))
    val axi = new AxiLiteMasterIO(axiConfig.addrWidth, axiConfig.dataWidth)
    val memoryFault = Output(new MemoryFault(axiConfig.addrWidth))

    val debug = Output(new NpcFrontendDebugBundle(cfg))
  })

  val pipelinedFetch = config.cache.accessMode == CacheAccessMode.PipelinedTwoCycle
  val fetchBufferIn = Wire(Decoupled(new FetchDecodePayload(cfg)))
  val fetchBufferOut = Wire(Decoupled(new FetchDecodePayload(cfg)))
  if (config.cache.instructionBuffer.enabled) {
    val instructionBuffer = Module(new InstructionBuffer(
      config.cache.instructionBuffer.entries,
      cfg,
      flowThrough = pipelinedFetch
    ))
    instructionBuffer.io.flush := io.redirectValid
    instructionBuffer.io.dropYounger := io.fenceHold
    instructionBuffer.io.in <> fetchBufferIn
    fetchBufferOut <> instructionBuffer.io.out
  } else {
    val fetchDecodeReg = Module(new PipelineRegister(new FetchDecodePayload(cfg)))
    fetchDecodeReg.io.flush := io.redirectValid
    fetchDecodeReg.io.in <> fetchBufferIn
    fetchBufferOut <> fetchDecodeReg.io.out
  }
  val programCounter = Module(new ProgramCounter(cfg.xlen, config.memory.resetVector))
  val decodeUnit = Module(new NpcDecodeUnit(cfg))

  val cycleCounter = if (debugEnabled) Some(RegInit(0.U(64.W))) else None
  cycleCounter.foreach(counter => counter := counter + 1.U)
  val performanceCycle = cycleCounter.getOrElse(0.U(64.W))

  val fetchInstruction = WireDefault(0x00000013.U(32.W))
  val fetchResponsePc = WireDefault(programCounter.io.pc(31, 0))
  val fetchResponseIssueCycle = WireDefault(performanceCycle)
  val fetchResponseValid = WireDefault(false.B)
  val fetchBusy = WireDefault(false.B)
  val fetchFault = WireDefault(0.U.asTypeOf(new MemoryFault(axiConfig.addrWidth)))
  val fetchPcForPrediction = if (cfg.xlen > axiConfig.addrWidth) {
    Cat(0.U((cfg.xlen - axiConfig.addrWidth).W), fetchResponsePc)
  } else fetchResponsePc
  val fetchIssuedPredictedNextPc = WireDefault(fetchPcForPrediction + 4.U)
  val fetchImmediate = RiscvImmediateGenerator(fetchInstruction, cfg.xlen)
  val fetchOpcode = fetchInstruction(6, 0)
  val fetchIsJal = fetchOpcode === "b1101111".U
  val fetchIsBackwardBranch = fetchOpcode === "b1100011".U && fetchImmediate(cfg.xlen - 1)
  // 只预测目标由当前指令字直接给出的控制流；JAL 必跳，条件分支只预测后向边。
  val fetchStaticPrediction = pipelinedFetch.B && (fetchIsJal || fetchIsBackwardBranch)
  val fetchPredictedNextPc = Mux(fetchStaticPrediction,
    fetchPcForPrediction + fetchImmediate, fetchIssuedPredictedNextPc)
  val fetchFlush = io.redirectValid || io.fenceHold
  val fetchRestartPc = Mux(io.redirectValid, io.redirectTarget(31, 0), fetchBufferOut.bits.pc + 4.U)
  val pcWriteEnable = WireDefault(false.B)
  programCounter.io.nextPc := Mux(io.redirectValid, io.redirectTarget,
    Mux(io.fenceHold, fetchBufferOut.bits.pc + 4.U,
      Mux(pipelinedFetch.B, fetchBufferIn.bits.predictedNextPc, programCounter.io.pcPlus4)))
  programCounter.io.writeEnable := pcWriteEnable

  fetchBufferIn.valid := fetchResponseValid && !fetchFlush
  fetchBufferIn.bits.pc := Mux(pipelinedFetch.B, fetchResponsePc, programCounter.io.pc)
  fetchBufferIn.bits.instruction := fetchInstruction
  fetchBufferIn.bits.predictedNextPc := fetchPredictedNextPc
  fetchBufferIn.bits.perfFetchStartCycle := fetchResponseIssueCycle
  fetchBufferIn.bits.perfFetchCycles := performanceCycle - fetchResponseIssueCycle
  fetchBufferIn.bits.perfDecodeStartCycle := performanceCycle
  if (pipelinedFetch) {
    val instructionFetchUnit = Module(new PipelinedIFetchAXIAdapter(
      axiConfig.addrWidth, axiConfig.dataWidth, config.cache.pipelinedQueues.fetchDepth))
    instructionFetchUnit.io.pc := programCounter.io.pc(31, 0)
    instructionFetchUnit.io.restartPc := fetchRestartPc
    instructionFetchUnit.io.performanceCycle := performanceCycle
    instructionFetchUnit.io.predictionValid := fetchBufferIn.fire &&
      fetchStaticPrediction && fetchPredictedNextPc =/= fetchIssuedPredictedNextPc
    instructionFetchUnit.io.predictionTarget := fetchPredictedNextPc(axiConfig.addrWidth - 1, 0)
    instructionFetchUnit.io.flush := fetchFlush
    instructionFetchUnit.io.responseReady := fetchBufferIn.ready && !fetchFlush
    instructionFetchUnit.io.axi <> io.axi
    fetchInstruction := instructionFetchUnit.io.inst
    fetchResponsePc := instructionFetchUnit.io.responsePc
    fetchIssuedPredictedNextPc := (if (cfg.xlen > axiConfig.addrWidth) {
      Cat(0.U((cfg.xlen - axiConfig.addrWidth).W), instructionFetchUnit.io.responsePredictedNextPc)
    } else instructionFetchUnit.io.responsePredictedNextPc)
    fetchResponseIssueCycle := instructionFetchUnit.io.responseIssueCycle
    fetchResponseValid := instructionFetchUnit.io.responseValid
    fetchBusy := instructionFetchUnit.io.busy
    fetchFault := instructionFetchUnit.io.fault
  } else {
    val instructionFetchUnit = Module(new IFetchAXIAdapter(
      axiConfig.addrWidth, axiConfig.dataWidth, config.pipeline.registerInitialFetchRequest))
    instructionFetchUnit.io.pc := programCounter.io.pc(31, 0)
    instructionFetchUnit.io.performanceCycle := performanceCycle
    instructionFetchUnit.io.flush := fetchFlush
    instructionFetchUnit.io.responseReady := fetchBufferIn.ready && !fetchFlush
    instructionFetchUnit.io.axi <> io.axi
    fetchInstruction := instructionFetchUnit.io.inst
    fetchResponseValid := instructionFetchUnit.io.responseValid
    fetchBusy := instructionFetchUnit.io.busy
    fetchFault := instructionFetchUnit.io.fault
    fetchResponseIssueCycle := instructionFetchUnit.io.responseIssueCycle
  }
  io.memoryFault := fetchFault

  pcWriteEnable := io.redirectValid || io.fenceHold || fetchBufferIn.fire

  val instruction = fetchBufferOut.bits.instruction
  val decodeSignals = decodeUnit.io.signals
  decodeUnit.io.instruction := instruction

  io.dispatch.valid := fetchBufferOut.valid && !io.redirectValid
  fetchBufferOut.ready := io.dispatch.ready && !io.redirectValid
  io.interruptPc := Mux(fetchBufferOut.valid, fetchBufferOut.bits.pc, programCounter.io.pc)
  io.dispatch.bits.pc := fetchBufferOut.bits.pc
  io.dispatch.bits.instruction := instruction
  io.dispatch.bits.predictedNextPc := fetchBufferOut.bits.predictedNextPc
  io.dispatch.bits.perfFetchStartCycle := fetchBufferOut.bits.perfFetchStartCycle
  io.dispatch.bits.perfFetchCycles := fetchBufferOut.bits.perfFetchCycles
  // 保留取指响应进入 IF/ID 的时刻。若缓冲中已有更老指令，这段驻留必须归入
  // IF/ID，而不能在时间线中成为没有归属的空白周期。
  io.dispatch.bits.perfDecodeStartCycle := fetchBufferOut.bits.perfDecodeStartCycle
  io.dispatch.bits.immediate := RiscvImmediateGenerator(instruction, cfg.xlen)
  io.dispatch.bits.rd := instruction(11, 7)
  io.dispatch.bits.rs1 := instruction(19, 15)
  io.dispatch.bits.rs2 := instruction(24, 20)
  io.dispatch.bits.rs3 := instruction(31, 27)
  io.dispatch.bits.funct3 := instruction(14, 12)
  io.dispatch.bits.funct7 := instruction(31, 25)
  io.dispatch.bits.csrAddress := instruction(31, 20)
  io.dispatch.bits.branch := decodeSignals.branch
  io.dispatch.bits.loadEnable := decodeSignals.loadEnable
  io.dispatch.bits.writebackFromMemory := decodeSignals.writebackFromMemory
  io.dispatch.bits.storeEnable := decodeSignals.storeEnable
  io.dispatch.bits.useImmediate := decodeSignals.useImmediate
  io.dispatch.bits.registerWriteEnable := decodeSignals.registerWriteEnable
  io.dispatch.bits.usesRs1 := decodeSignals.usesRs1
  io.dispatch.bits.usesRs2 := decodeSignals.usesRs2
  io.dispatch.bits.executionUnit := decodeSignals.executionUnit
  io.dispatch.bits.aluCtrl := decodeSignals.aluCtrl
  io.dispatch.bits.privilegedInstruction := decodeSignals.privilegedInstruction
  io.dispatch.bits.trapEnable := decodeSignals.trapEnable
  io.dispatch.bits.trapCause := decodeSignals.trapCause
  io.dispatch.bits.mretEnable := decodeSignals.mretEnable
  io.dispatch.bits.csrEnable := decodeSignals.csrEnable
  io.dispatch.bits.csrOperation := decodeSignals.csrOperation
  io.dispatch.bits.csrUseImmediate := decodeSignals.csrUseImmediate
  io.dispatch.bits.csrReadWritebackEnable := decodeSignals.csrReadWritebackEnable
  io.dispatch.bits.floatingOperation := decodeSignals.floatingOperation
  io.dispatch.bits.floatingInstruction := decodeSignals.floatingInstruction
  io.dispatch.bits.floatRegisterWriteEnable := decodeSignals.floatRegisterWriteEnable
  io.dispatch.bits.usesFrs1 := decodeSignals.usesFrs1
  io.dispatch.bits.usesFrs2 := decodeSignals.usesFrs2
  io.dispatch.bits.usesFrs3 := decodeSignals.usesFrs3

  // 有在途取指并不表示前端停顿：两拍取指会持续保持请求队列。只有 IF/ID 两端
  // 都不能提供指令时，才是会阻止派发的取指缺口。
  val fetchStarvationCycles = RegInit(0.U(64.W))
  val fetchStarved = fetchBusy && !fetchBufferIn.valid && !fetchBufferOut.valid
  val redirectFlushCount = RegInit(0.U(64.W))
  when(fetchStarved) { fetchStarvationCycles := fetchStarvationCycles + 1.U }
  when(io.redirectValid) { redirectFlushCount := redirectFlushCount + 1.U }

  io.debug.pcWriteEnable := pcWriteEnable
  io.debug.fetchDecodeFire := fetchBufferIn.fire
  io.debug.currentPc := programCounter.io.pc
  io.debug.nextArchitecturalPc := Mux(fetchBufferOut.valid,
    fetchBufferOut.bits.pc, programCounter.io.pc)
  io.debug.frontendInstruction := Mux(fetchBufferOut.valid, instruction, fetchInstruction)
  io.debug.decodeImmediate := RiscvImmediateGenerator(instruction, cfg.xlen)
  io.debug.decodeOpcode := instruction(6, 0)
  io.debug.decodeFunct3 := instruction(14, 12)
  io.debug.decodeFunct7 := instruction(31, 25)
  io.debug.fetchStarvationCycles := fetchStarvationCycles
  io.debug.redirectFlushCount := redirectFlushCount
  io.debug.fetchBusy := fetchBusy
  io.debug.dispatchBackpressured := fetchBufferOut.valid && !fetchBufferOut.ready
}
