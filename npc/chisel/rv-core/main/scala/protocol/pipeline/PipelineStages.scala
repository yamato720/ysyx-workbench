package scpu.protocol

import chisel3._
import chisel3.util._
import scpu.{ISAConfig, NpcAluOp, NpcExecutionUnit}

// PipelineRegister 是一个通用的就绪/有效流水寄存器。
//
// Chisel/硬件语义：
//   Decoupled[T] = { valid, ready, bits }
//     valid: 发送方拉高，表示 bits 里有有效数据
//     ready: 接收方拉高，表示这一拍可以接收
//     fire : valid && ready，表示这一拍真正完成一次传输
//
//   Flipped(Decoupled[T]) 用在接收端，把 valid/bits 变成输入，把 ready 变成输出。
//   普通 Decoupled[T] 用在发送端，把 valid/bits 变成输出，把 ready 变成输入。
//
// 这个模块实现一个单项弹性缓冲：
//   - 下游 ready 或当前为空时，上游可以写入
//   - flush 会直接清空 valid，常用于分支/异常冲刷流水级
class PipelineRegister[T <: Data](gen: T) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(gen))
    val out = Decoupled(gen)
    val flush = Input(Bool())
  })

  // validReg 表示 bitsReg 中是否保存着一条有效载荷。
  val validReg = RegInit(false.B)

  // chiselTypeOf(io.in.bits) 复制载荷的硬件类型。
  // 这里不能直接用 gen，因为 IO 经 Chisel 生成后类型信息更准确。
  // RegInit(0.U.asTypeOf(...)) 的效果是：创建同形状寄存器，并把所有字段初始化为 0。
  val bitsReg = RegInit(0.U.asTypeOf(chiselTypeOf(io.in.bits)))
  // ready 反压规则：
  //   - 缓冲为空：可以接收新数据
  //   - 缓冲非空但下游 ready：本拍会被取走，也可同时接收新数据
  io.in.ready := !validReg || io.out.ready

  // 输出端只反映本级寄存器中保存的数据。
  io.out.valid := validReg
  io.out.bits := bitsReg

  when(io.flush) {
    // flush 只清 valid，不必清 bits；valid=false 时 bits 不应被消费。
    validReg := false.B
  }.elsewhen(io.in.ready) {
    // 只有本级允许接收时才更新 valid/bits。
    // 如果 io.in.valid=false 且 io.in.ready=true，表示写入一个空泡。
    validReg := io.in.valid
    when(io.in.valid) {
      bitsReg := io.in.bits
    }
  }
}

// IF/ID 之间只需要保存 PC 和完整指令。
// 字段拆分放到 ID 级做，这样 IF 级不用理解 ISA。
class FetchDecodePayload(cfg: ISAConfig) extends Bundle {
  val pc = UInt(cfg.xlen.W)
  val instruction = UInt(32.W)
  // 仿真性能侧带：从 PC 生效到进入 IF/ID 的取指周期数，以及 ID 起点。
  val perfFetchCycles = UInt(64.W)
  val perfDecodeStartCycle = UInt(64.W)
}

/**
  * NPC 前端和后端之间的架构派发边界。
  *
  * 前端拥有取指和译码，后端拥有架构寄存器文件和执行流水线。这里刻意保存源寄存器
  * *编号* 而非源数值：未来可在这个 Decoupled 边界插入重命名、ROB 或发射阶段，
  * 无需让前端了解物理寄存器或推测数值。
  */
class DecodedDispatchPayload(cfg: ISAConfig) extends Bundle {
  val pc = UInt(cfg.xlen.W)
  val instruction = UInt(32.W)
  val perfFetchCycles = UInt(64.W)
  val perfDecodeStartCycle = UInt(64.W)

  val immediate = UInt(cfg.xlen.W)
  val rd = UInt(5.W)
  val rs1 = UInt(5.W)
  val rs2 = UInt(5.W)
  val rs3 = UInt(5.W)
  val funct3 = UInt(3.W)
  val funct7 = UInt(7.W)
  val csrAddress = UInt(12.W)

  val branch = Bool()
  val loadEnable = Bool()
  val writebackFromMemory = Bool()
  val storeEnable = Bool()
  val useImmediate = Bool()
  val registerWriteEnable = Bool()
  val usesRs1 = Bool()
  val usesRs2 = Bool()
  val executionUnit = UInt(NpcExecutionUnit.width.W)
  val aluCtrl = UInt(NpcAluOp.width.W)

  val privilegedInstruction = Bool()
  val trapEnable = Bool()
  val trapCause = UInt(cfg.xlen.W)
  val mretEnable = Bool()
  val csrEnable = Bool()
  val csrOperation = UInt(2.W)
  val csrUseImmediate = Bool()
  val csrReadWritebackEnable = Bool()

  val floatingOperation = Bool()
  val floatingInstruction = Bool()
  val floatRegisterWriteEnable = Bool()
  val usesFrs1 = Bool()
  val usesFrs2 = Bool()
  val usesFrs3 = Bool()
}

// ID/EX payload：ID 级已经完成寄存器读、立即数生成、控制信号生成。
// 后续 EX 级不再重新看 opcode/funct 产生控制信号，只消费这里携带的控制字。
class DecodeExecutePayload(cfg: ISAConfig) extends Bundle {
  val pc = UInt(cfg.xlen.W)
  val instruction = UInt(32.W)
  val perfFetchCycles = UInt(64.W)
  val perfDecodeStartCycle = UInt(64.W)
  val perfDecodeCycles = UInt(64.W)
  val perfExecuteStartCycle = UInt(64.W)
  val rs1Data = UInt(cfg.xlen.W)
  val storeData = UInt(cfg.xlen.W)
  val operandC = UInt(cfg.xlen.W)
  val immediate = UInt(cfg.xlen.W)
  val rd = UInt(5.W)
  val rs1 = UInt(5.W)
  val rs2 = UInt(5.W)
  val rs3 = UInt(5.W)
  // 源操作数使用元数据只在 ID 译码一次，随后由 RAW 冒险检测消费；它避免将立即数、
  // x0、LUI/JAL 和 CSR 立即数形式误判为寄存器依赖。
  val usesRs1 = Bool()
  val usesRs2 = Bool()
  val floatingOperation = Bool()
  val floatingInstruction = Bool()
  val floatRegisterWriteEnable = Bool()
  val usesFrs1 = Bool()
  val usesFrs2 = Bool()
  val usesFrs3 = Bool()
  val funct3 = UInt(3.W)
  val csrAddress = UInt(12.W)

  val branch = Bool()
  val loadEnable = Bool()
  val writebackFromMemory = Bool()
  val storeEnable = Bool()
  val useImmediate = Bool()
  val registerWriteEnable = Bool()
  val executionUnit = UInt(NpcExecutionUnit.width.W)
  val aluCtrl = UInt(NpcAluOp.width.W)

  val privilegedInstruction = Bool()
  val trapEnable = Bool()
  val trapCause = UInt(cfg.xlen.W)
  val mretEnable = Bool()
  val csrEnable = Bool()
  val csrOperation = UInt(2.W)
  val csrUseImmediate = Bool()
  val csrReadWritebackEnable = Bool()
}

// EX/MEM payload：EX 级输出 ALU/分支/CSR 计算结果。
// load/store 还没真正完成，MEM 级会用 aluResult 作为访存地址。
class ExecuteMemoryPayload(cfg: ISAConfig) extends Bundle {
  val pc = UInt(cfg.xlen.W)
  val instruction = UInt(32.W)
  val perfFetchCycles = UInt(64.W)
  val perfDecodeCycles = UInt(64.W)
  val perfExecuteCycles = UInt(64.W)
  val perfMemoryStartCycle = UInt(64.W)
  val aluResult = UInt(cfg.xlen.W)
  val branchTaken = UInt(3.W)
  val branchTarget = UInt(cfg.xlen.W)
  val jalrTarget = UInt(cfg.xlen.W)
  val storeData = UInt(cfg.xlen.W)
  val rd = UInt(5.W)
  val funct3 = UInt(3.W)

  val branch = Bool()
  val loadEnable = Bool()
  val writebackFromMemory = Bool()
  val storeEnable = Bool()
  val registerWriteEnable = Bool()
  val floatRegisterWriteEnable = Bool()
  val floatingInstruction = Bool()
  val floatingExceptionFlags = UInt(5.W)
  val csrReadWritebackEnable = Bool()

  val csrAddress = UInt(12.W)
  val csrWriteEnable = Bool()
  val csrWriteData = UInt(cfg.xlen.W)
  val csrAccessAllowed = Bool()
  val trapEnable = Bool()
  val trapCause = UInt(cfg.xlen.W)
  val trapEpc = UInt(cfg.xlen.W)
  val mretEnable = Bool()
  val csrReadData = UInt(cfg.xlen.W)
}

// MEM/WB payload：WB 级只负责最终提交。
// 对普通 ALU 指令写 aluResult；对 load 写 loadData；对 CSR 写 csrReadData。
class MemoryWritebackPayload(cfg: ISAConfig) extends Bundle {
  val pc = UInt(cfg.xlen.W)
  val instruction = UInt(32.W)
  val perfFetchCycles = UInt(64.W)
  val perfDecodeCycles = UInt(64.W)
  val perfExecuteCycles = UInt(64.W)
  val perfMemoryCycles = UInt(64.W)
  val perfWritebackStartCycle = UInt(64.W)
  val nextPc = UInt(cfg.xlen.W)
  val rd = UInt(5.W)
  val aluResult = UInt(cfg.xlen.W)
  val loadData = UInt(cfg.xlen.W)
  val csrReadData = UInt(cfg.xlen.W)
  val writebackFromMemory = Bool()
  val registerWriteEnable = Bool()
  val floatRegisterWriteEnable = Bool()
  val floatingInstruction = Bool()
  val floatingExceptionFlags = UInt(5.W)
  val csrReadWritebackEnable = Bool()

  val csrAddress = UInt(12.W)
  val csrWriteEnable = Bool()
  val csrWriteData = UInt(cfg.xlen.W)
  val csrAccessAllowed = Bool()
  val trapEnable = Bool()
  val trapCause = UInt(cfg.xlen.W)
  val trapEpc = UInt(cfg.xlen.W)
  val mretEnable = Bool()
}
