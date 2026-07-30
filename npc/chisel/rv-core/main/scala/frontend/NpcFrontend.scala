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

  val fetchBufferIn = Wire(Decoupled(new FetchDecodePayload(cfg)))
  val fetchBufferOut = Wire(Decoupled(new FetchDecodePayload(cfg)))
  if (config.cache.instructionBuffer.enabled) {
    val instructionBuffer = Module(new InstructionBuffer(config.cache.instructionBuffer.entries, cfg))
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
  val instructionFetchUnit = Module(new IFetchAXIAdapter(
    axiConfig.addrWidth,
    axiConfig.dataWidth,
    config.pipeline.registerInitialFetchRequest
  ))
  val decodeUnit = Module(new NpcDecodeUnit(cfg))

  val cycleCounter = if (debugEnabled) Some(RegInit(0.U(64.W))) else None
  cycleCounter.foreach(counter => counter := counter + 1.U)
  val performanceCycle = cycleCounter.getOrElse(0.U(64.W))
  val fetchStartCycle = if (debugEnabled) Some(RegInit(0.U(64.W))) else None

  val pcWriteEnable = WireDefault(false.B)
  programCounter.io.nextPc := Mux(io.redirectValid, io.redirectTarget,
    Mux(io.fenceHold, fetchBufferOut.bits.pc + 4.U, programCounter.io.pcPlus4))
  programCounter.io.writeEnable := pcWriteEnable
  instructionFetchUnit.io.pc := programCounter.io.pc(31, 0)
  instructionFetchUnit.io.flush := io.redirectValid || io.fenceHold
  instructionFetchUnit.io.axi <> io.axi
  io.memoryFault := instructionFetchUnit.io.fault

  fetchBufferIn.valid := instructionFetchUnit.io.responseValid && !io.redirectValid && !io.fenceHold
  fetchBufferIn.bits.pc := programCounter.io.pc
  fetchBufferIn.bits.instruction := instructionFetchUnit.io.inst
  fetchBufferIn.bits.perfFetchCycles := (
    if (debugEnabled) performanceCycle - fetchStartCycle.get else 0.U(64.W)
  )
  fetchBufferIn.bits.perfDecodeStartCycle := performanceCycle
  instructionFetchUnit.io.responseReady := fetchBufferIn.ready && !io.redirectValid && !io.fenceHold

  pcWriteEnable := io.redirectValid || io.fenceHold || fetchBufferIn.fire
  fetchStartCycle.foreach { start =>
    when(pcWriteEnable) { start := performanceCycle }
  }

  val instruction = fetchBufferOut.bits.instruction
  val decodeSignals = decodeUnit.io.signals
  decodeUnit.io.instruction := instruction

  io.dispatch.valid := fetchBufferOut.valid && !io.redirectValid
  fetchBufferOut.ready := io.dispatch.ready && !io.redirectValid
  io.interruptPc := Mux(fetchBufferOut.valid, fetchBufferOut.bits.pc, programCounter.io.pc)
  io.dispatch.bits.pc := fetchBufferOut.bits.pc
  io.dispatch.bits.instruction := instruction
  io.dispatch.bits.perfFetchCycles := fetchBufferOut.bits.perfFetchCycles
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

  val fetchAxiWaitCycles = RegInit(0.U(64.W))
  val redirectFlushCount = RegInit(0.U(64.W))
  when(instructionFetchUnit.io.busy) { fetchAxiWaitCycles := fetchAxiWaitCycles + 1.U }
  when(io.redirectValid) { redirectFlushCount := redirectFlushCount + 1.U }

  io.debug.pcWriteEnable := pcWriteEnable
  io.debug.fetchDecodeFire := fetchBufferIn.fire
  io.debug.currentPc := programCounter.io.pc
  io.debug.nextArchitecturalPc := Mux(fetchBufferOut.valid,
    fetchBufferOut.bits.pc, programCounter.io.pc)
  io.debug.frontendInstruction := Mux(fetchBufferOut.valid, instruction, instructionFetchUnit.io.inst)
  io.debug.decodeImmediate := RiscvImmediateGenerator(instruction, cfg.xlen)
  io.debug.decodeOpcode := instruction(6, 0)
  io.debug.decodeFunct3 := instruction(14, 12)
  io.debug.decodeFunct7 := instruction(31, 25)
  io.debug.fetchAxiWaitCycles := fetchAxiWaitCycles
  io.debug.redirectFlushCount := redirectFlushCount
  io.debug.fetchBusy := instructionFetchUnit.io.busy
  io.debug.dispatchBackpressured := fetchBufferOut.valid && !fetchBufferOut.ready
}
