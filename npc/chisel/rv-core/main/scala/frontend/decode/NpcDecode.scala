package scpu

import chisel3._
import chisel3.util._

// ID 阶段输出的一整包控制信号。
//
// 这个 Bundle 相当于把以前分散的主控制信号、ALU 控制信号、
// CSR/异常相关控制信号收在一起，后面会被塞进 DecodeExecutePayload，
// 随流水线一起传到 EX/MEM/WB 阶段。
class NpcDecodeSignals(cfg: ISAConfig = ISAConfig()) extends Bundle {
  // 普通流水线控制：
  //   branch: 当前指令是否属于会改变 PC 的分支/跳转类
  //   load/store: 是否访问数据存储器
  //   writebackFromMemory: 写回数据是否来自 load 结果
  //   useImmediate: ALU 第二操作数是否选择立即数
  //   registerWriteEnable: 是否写整数寄存器 rd
  val branch = Bool()
  val loadEnable = Bool()
  val writebackFromMemory = Bool()
  val storeEnable = Bool()
  val useImmediate = Bool()
  val registerWriteEnable = Bool()
  val usesRs1 = Bool()
  val usesRs2 = Bool()
  // Binary32 的 FADD.S/FSUB.S/FMUL.S/FDIV.S 使用浮点寄存器源并写入浮点 rd。
  // 尽管与整数寄存器使用相同的指令位位置，浮点控制仍刻意与整数寄存器控制分离。
  val floatingOperation = Bool()
  val floatingInstruction = Bool()
  val floatRegisterWriteEnable = Bool()
  val usesFrs1 = Bool()
  val usesFrs2 = Bool()
  val usesFrs3 = Bool()

  // executionUnit 是这条指令唯一的 EX 结果生产者；aluCtrl 是该单元的
  // 已解码操作码，例如 ADD/SUB/BEQ/MUL。
  val executionUnit = UInt(NpcExecutionUnit.width.W)
  val aluCtrl = UInt(NpcAluOp.width.W)

  // 特权/CSR 相关控制：
  //   trapEnable: ECALL/EBREAK/非法指令等触发 trap
  //   trapCause: 写入 mcause 的异常原因编码
  //   mretEnable: MRET 返回
  //   csrEnable/csrOperation/csrUseImmediate: CSR 指令的读改写行为
  //   csrReadWritebackEnable: CSR 指令需要把旧 CSR 值写回 rd
  val privilegedInstruction = Bool()
  val trapEnable = Bool()
  val trapCause = UInt(cfg.xlen.W)
  val mretEnable = Bool()
  val csrEnable = Bool()
  val csrOperation = UInt(NpcCsrOp.width.W)
  val csrUseImmediate = Bool()
  val csrReadWritebackEnable = Bool()
}

class NpcDecodeUnit(cfg: ISAConfig = ISAConfig()) extends Module {
  val io = IO(new Bundle {
    // 输入是完整 32 位 RISC-V 指令。
    // 本模块直接对整条 instruction 做 BitPat 匹配，而不是先拆 opcode 再二级解码。
    val instruction = Input(UInt(32.W))

    // 输出是一整包控制字，NpcFrontend 会把它放进 dispatch payload。
    val signals = Output(new NpcDecodeSignals(cfg))
  })

  // 只在这个 class 作用域内引入指令 BitPat 名字，
  // 这样下面可以直接写 ADDI/LW/BEQ，而不用写 Instructions.ADDI。
  import Instructions._

  // 查表里常用 1 bit 常量：N=false/no，Y=true/yes。
  // 用 UInt 而不是 Bool，是因为 ListLookup 的一行需要统一成 List[UInt]。
  private val N = 0.U(1.W)
  private val Y = 1.U(1.W)

  // Field 给 decoded 这条 List 的每一列起名字。
  //
  // row(...) 返回的是 List[UInt]，本质上是“一行控制字”。
  // List 没有字段名，只能用下标访问；这里用 Field.xxx 避免裸写 decoded(7) 这种魔法数字。
  private object Field {
    val branch = 0
    val loadEnable = 1
    val writebackFromMemory = 2
    val storeEnable = 3
    val useImmediate = 4
    val registerWriteEnable = 5
    val usesRs1 = 6
    val usesRs2 = 7
    val executionUnit = 8
    val aluCtrl = 9
    val privilegedInstruction = 10
    val trapEnable = 11
    val trapCause = 12
    val mretEnable = 13
    val csrEnable = 14
    val csrOperation = 15
    val csrUseImmediate = 16
    val csrReadWritebackEnable = 17
    val floatingOperation = 18
    val floatingInstruction = 19
    val floatRegisterWriteEnable = 20
    val usesFrs1 = 21
    val usesFrs2 = 22
    val usesFrs3 = 23
  }

  // 构造一行控制字。
  //
  // Scala 的“带默认值参数”让每条指令只需要写自己关心的控制信号，
  // 没写的字段自动用安全默认值：
  //   - 大多数 enable 默认为 0
  //   - executionUnit 默认 integer ALU
  //   - aluCtrl 默认 ADD
  //
  // 注意：这个函数是 Scala/Chisel elaboration 时用来组织表项的，
  // 它本身不是寄存器，也不是一个单独硬件模块。
  private def row(
    branch: UInt = N,
    loadEnable: UInt = N,
    writebackFromMemory: UInt = N,
    storeEnable: UInt = N,
    useImmediate: UInt = N,
    registerWriteEnable: UInt = N,
    usesRs1: UInt = N,
    usesRs2: UInt = N,
    executionUnit: UInt = NpcExecutionUnit.integer,
    aluCtrl: UInt = NpcAluOp.Integer.ADD.asUInt,
    privilegedInstruction: UInt = N,
    trapEnable: UInt = N,
    trapCause: UInt = 0.U(cfg.xlen.W),
    mretEnable: UInt = N,
    csrEnable: UInt = N,
    csrOperation: UInt = NpcCsrOp.write,
    csrUseImmediate: UInt = N,
    csrReadWritebackEnable: UInt = N,
    floatingOperation: UInt = N,
    floatingInstruction: UInt = N,
    floatRegisterWriteEnable: UInt = N,
    usesFrs1: UInt = N,
    usesFrs2: UInt = N,
    usesFrs3: UInt = N
  ): List[UInt] = List(
    branch,
    loadEnable,
    writebackFromMemory,
    storeEnable,
    useImmediate,
    registerWriteEnable,
    usesRs1,
    usesRs2,
    executionUnit,
    aluCtrl,
    privilegedInstruction,
    trapEnable,
    trapCause,
    mretEnable,
    csrEnable,
    csrOperation,
    csrUseImmediate,
    csrReadWritebackEnable,
    floatingOperation,
    floatingInstruction,
    floatRegisterWriteEnable,
    usesFrs1,
    usesFrs2,
    usesFrs3
  )

  // 下面这些 helper 是给解码表用的“行模板”。
  // 比如所有 load 都需要：访存读、写回来自 memory、ALU 用 rs1+imm 算地址、写 rd。
  private def integerAlu(op: NpcAluOp.Integer.Type, useImm: UInt = N): List[UInt] =
    row(
      useImmediate = useImm,
      registerWriteEnable = Y,
      usesRs1 = Y,
      usesRs2 = Mux(useImm.asBool, N, Y),
      aluCtrl = op.asUInt
    )

  private def upperImmediateAlu(op: NpcAluOp.Integer.Type): List[UInt] =
    row(useImmediate = Y, registerWriteEnable = Y, aluCtrl = op.asUInt)

  private def load: List[UInt] =
    row(loadEnable = Y, writebackFromMemory = Y, useImmediate = Y, registerWriteEnable = Y, usesRs1 = Y,
      aluCtrl = NpcAluOp.Integer.ADD.asUInt)

  private def store: List[UInt] =
    row(storeEnable = Y, useImmediate = Y, usesRs1 = Y, usesRs2 = Y, aluCtrl = NpcAluOp.Integer.ADD.asUInt)

  private def branch(op: NpcAluOp.Integer.Type): List[UInt] =
    row(branch = Y, usesRs1 = Y, usesRs2 = Y, aluCtrl = op.asUInt)

  private def jump(op: NpcAluOp.Integer.Type, usesRs1: UInt = N): List[UInt] =
    row(branch = Y, registerWriteEnable = Y, usesRs1 = usesRs1, aluCtrl = op.asUInt)

  private def multiply(op: NpcAluOp.MulDiv.Type): List[UInt] =
    row(
      registerWriteEnable = Y,
      usesRs1 = Y,
      usesRs2 = Y,
      executionUnit = NpcExecutionUnit.multiply,
      aluCtrl = op.asUInt
    )

  private def divide(op: NpcAluOp.MulDiv.Type): List[UInt] =
    row(
      registerWriteEnable = Y,
      usesRs1 = Y,
      usesRs2 = Y,
      executionUnit = NpcExecutionUnit.divide,
      aluCtrl = op.asUInt
    )

  private def floatingBinary(op: NpcAluOp.Floating.Type): List[UInt] =
    row(
      floatingOperation = Y,
      floatingInstruction = Y,
      floatRegisterWriteEnable = Y,
      usesFrs1 = Y,
      usesFrs2 = Y,
      executionUnit = NpcExecutionUnit.floating,
      aluCtrl = op.asUInt
    )

  private def floatingFma(op: NpcAluOp.Floating.Type): List[UInt] =
    row(floatingOperation = Y, floatingInstruction = Y, floatRegisterWriteEnable = Y,
      usesFrs1 = Y, usesFrs2 = Y, usesFrs3 = Y,
      executionUnit = NpcExecutionUnit.floating, aluCtrl = op.asUInt)

  private def floatingUnary(op: NpcAluOp.Floating.Type): List[UInt] =
    row(floatingOperation = Y, floatingInstruction = Y, floatRegisterWriteEnable = Y,
      usesFrs1 = Y, executionUnit = NpcExecutionUnit.floating, aluCtrl = op.asUInt)

  private def floatingToInteger(op: NpcAluOp.Floating.Type, binary: Boolean = false): List[UInt] =
    row(floatingOperation = Y, floatingInstruction = Y, registerWriteEnable = Y,
      usesFrs1 = Y, usesFrs2 = if (binary) Y else N,
      executionUnit = NpcExecutionUnit.floating, aluCtrl = op.asUInt)

  private def integerToFloating(op: NpcAluOp.Floating.Type): List[UInt] =
    row(floatingOperation = Y, floatingInstruction = Y, floatRegisterWriteEnable = Y,
      usesRs1 = Y, executionUnit = NpcExecutionUnit.floating, aluCtrl = op.asUInt)

  private def floatingLoad: List[UInt] =
    row(loadEnable = Y, writebackFromMemory = Y, useImmediate = Y, usesRs1 = Y,
      floatRegisterWriteEnable = Y, floatingInstruction = Y, aluCtrl = NpcAluOp.Integer.ADD.asUInt)

  private def floatingStore: List[UInt] =
    row(storeEnable = Y, useImmediate = Y, usesRs1 = Y, usesFrs2 = Y,
      floatingInstruction = Y, aluCtrl = NpcAluOp.Integer.ADD.asUInt)

  private def systemEcall: List[UInt] =
    row(privilegedInstruction = Y, trapEnable = Y, trapCause = CsrCause.machineEcall.U(cfg.xlen.W))

  private def systemBreakpoint: List[UInt] =
    row(privilegedInstruction = Y, trapEnable = Y, trapCause = CsrCause.breakpoint.U(cfg.xlen.W))

  private def systemMret: List[UInt] =
    row(privilegedInstruction = Y, mretEnable = Y)

  private def illegalInstruction: List[UInt] =
    row(privilegedInstruction = Y, trapEnable = Y, trapCause = CsrCause.illegalInstruction.U(cfg.xlen.W))

  private def csr(op: UInt, useImm: UInt): List[UInt] =
    row(
      registerWriteEnable = Y,
      usesRs1 = Mux(useImm.asBool, N, Y),
      privilegedInstruction = Y,
      csrEnable = Y,
      csrOperation = op,
      csrUseImmediate = useImm,
      csrReadWritebackEnable = Y
    )

  // RV32I/RV64 公共基础指令表。
  //
  // 表项形如：
  //   ADDI -> integerAlu(NpcAluOp.Integer.ADD, Y)
  //
  // 左边 ADDI 是 Instructions 里定义的 BitPat 指令模式；
  // 右边是一行控制字。综合后可以理解成一个大 case/mux 解码逻辑。
  private val baseTable: Seq[(BitPat, List[UInt])] = Seq(
    // load/store:
    // 地址都由整数 ALU 做 rs1 + immediate，所以 aluCtrl 用 ADD。
    LB    -> load,
    LH    -> load,
    LW    -> load,
    LBU   -> load,
    LHU   -> load,
    SB    -> store,
    SH    -> store,
    SW    -> store,

    // I-type 整数运算：第二操作数来自立即数，所以 useImm=Y。
    ADDI  -> integerAlu(NpcAluOp.Integer.ADD, Y),
    SLTI  -> integerAlu(NpcAluOp.Integer.SLT, Y),
    SLTIU -> integerAlu(NpcAluOp.Integer.SLTU, Y),
    XORI  -> integerAlu(NpcAluOp.Integer.XOR, Y),
    ORI   -> integerAlu(NpcAluOp.Integer.OR, Y),
    ANDI  -> integerAlu(NpcAluOp.Integer.AND, Y),
    SLLI  -> integerAlu(NpcAluOp.Integer.SLL, Y),
    SRLI  -> integerAlu(NpcAluOp.Integer.SRL, Y),
    SRAI  -> integerAlu(NpcAluOp.Integer.SRA, Y),

    // R-type 整数运算：第二操作数来自 rs2，所以 useImm 默认是 N。
    ADD   -> integerAlu(NpcAluOp.Integer.ADD),
    SUB   -> integerAlu(NpcAluOp.Integer.SUB),
    SLL   -> integerAlu(NpcAluOp.Integer.SLL),
    SLT   -> integerAlu(NpcAluOp.Integer.SLT),
    SLTU  -> integerAlu(NpcAluOp.Integer.SLTU),
    XOR   -> integerAlu(NpcAluOp.Integer.XOR),
    SRL   -> integerAlu(NpcAluOp.Integer.SRL),
    SRA   -> integerAlu(NpcAluOp.Integer.SRA),
    OR    -> integerAlu(NpcAluOp.Integer.OR),
    AND   -> integerAlu(NpcAluOp.Integer.AND),

    // 分支比较由 IntegerAlu 根据 aluCtrl 完成，branch=Y 表示后续需要看 branchTaken。
    BEQ   -> branch(NpcAluOp.Integer.BEQ),
    BNE   -> branch(NpcAluOp.Integer.BNE),
    BLT   -> branch(NpcAluOp.Integer.BLT),
    BGE   -> branch(NpcAluOp.Integer.BGE),
    BLTU  -> branch(NpcAluOp.Integer.BLTU),
    BGEU  -> branch(NpcAluOp.Integer.BGEU),

    // JAL/JALR 也会改变 PC，同时还要写回 rd=pc+4，所以 jump 会打开 registerWriteEnable。
    JAL   -> jump(NpcAluOp.Integer.JAL),
    JALR  -> jump(NpcAluOp.Integer.JALR, Y),

    // LUI/AUIPC 使用 U-type 立即数；具体结果由 ALU 根据 aluCtrl 计算。
    LUI   -> upperImmediateAlu(NpcAluOp.Integer.LUI),
    AUIPC -> upperImmediateAlu(NpcAluOp.Integer.AUIPC),

    // 特权控制指令：ECALL/EBREAK 都触发同步异常；MRET 在提交点 redirect 回 mepc。
    // Zicsr CSR 读改写指令单独放入 zicsrTable，关闭扩展时会成为 illegal instruction。
    ECALL  -> systemEcall,
    EBREAK -> systemBreakpoint,
    MRET   -> systemMret
  )

  /** Zicsr CSR 指令表。cfg.Zicsr=false 时不加入 ListLookup，保证禁用语义可观察。 */
  private val zicsrTable: Seq[(BitPat, List[UInt])] =
    if (cfg.Zicsr) {
      Seq(
    CSRRW  -> csr(NpcCsrOp.write, N),
    CSRRS  -> csr(NpcCsrOp.set, N),
    CSRRC  -> csr(NpcCsrOp.clear, N),
    CSRRWI -> csr(NpcCsrOp.write, Y),
    CSRRSI -> csr(NpcCsrOp.set, Y),
    CSRRCI -> csr(NpcCsrOp.clear, Y)
      )
    } else {
      Seq.empty
    }

  // 只在 RV64 配置下加入的指令。
  // cfg.xlen 是 Scala 生成参数，所以这里的 if 在 elaboration 时决定表里有没有这些项。
  private val rv64Table: Seq[(BitPat, List[UInt])] =
    if (cfg.xlen == 64) {
      Seq(
        LD     -> load,
        LWU    -> load,
        SD     -> store,
        ADDIW  -> integerAlu(NpcAluOp.Integer.ADDW, Y),
        SLLIW  -> integerAlu(NpcAluOp.Integer.SLLW, Y),
        SRLIW  -> integerAlu(NpcAluOp.Integer.SRLW, Y),
        SRAIW  -> integerAlu(NpcAluOp.Integer.SRAW, Y),
        ADDW   -> integerAlu(NpcAluOp.Integer.ADDW),
        SUBW   -> integerAlu(NpcAluOp.Integer.SUBW),
        SLLW   -> integerAlu(NpcAluOp.Integer.SLLW),
        SRLW   -> integerAlu(NpcAluOp.Integer.SRLW),
        SRAW   -> integerAlu(NpcAluOp.Integer.SRAW)
      )
    } else {
      Seq.empty
    }

  // M 扩展乘除法表。
  // cfg.M=false 时直接返回空表，相当于这些指令不会被本解码器匹配到。
  private val mTable: Seq[(BitPat, List[UInt])] =
    if (cfg.M) {
      val rv32M = Seq(
        MUL    -> multiply(NpcAluOp.MulDiv.MUL),
        MULH   -> multiply(NpcAluOp.MulDiv.MULH),
        MULHSU -> multiply(NpcAluOp.MulDiv.MULHSU),
        MULHU  -> multiply(NpcAluOp.MulDiv.MULHU),
        DIV    -> divide(NpcAluOp.MulDiv.DIV),
        DIVU   -> divide(NpcAluOp.MulDiv.DIVU),
        REM    -> divide(NpcAluOp.MulDiv.REM),
        REMU   -> divide(NpcAluOp.MulDiv.REMU)
      )

      if (cfg.xlen == 64) {
        rv32M ++ Seq(
          MULW  -> multiply(NpcAluOp.MulDiv.MULW),
          DIVW  -> divide(NpcAluOp.MulDiv.DIVW),
          DIVUW -> divide(NpcAluOp.MulDiv.DIVUW),
          REMW  -> divide(NpcAluOp.MulDiv.REMW),
          REMUW -> divide(NpcAluOp.MulDiv.REMUW)
        )
      } else {
        rv32M
      }
    } else {
      Seq.empty
    }

  private val fTable: Seq[(BitPat, List[UInt])] =
    if (cfg.F) {
      Seq(
        FLW -> floatingLoad,
        FSW -> floatingStore,
        FMADD_S -> floatingFma(NpcAluOp.Floating.FMADD),
        FMSUB_S -> floatingFma(NpcAluOp.Floating.FMSUB),
        FNMSUB_S -> floatingFma(NpcAluOp.Floating.FNMSUB),
        FNMADD_S -> floatingFma(NpcAluOp.Floating.FNMADD),
        FADD_S -> floatingBinary(NpcAluOp.Floating.FADD),
        FSUB_S -> floatingBinary(NpcAluOp.Floating.FSUB),
        FMUL_S -> floatingBinary(NpcAluOp.Floating.FMUL),
        FDIV_S -> floatingBinary(NpcAluOp.Floating.FDIV),
        FSQRT_S -> floatingUnary(NpcAluOp.Floating.FSQRT),
        FSGNJ_S -> floatingBinary(NpcAluOp.Floating.FSGNJ),
        FSGNJN_S -> floatingBinary(NpcAluOp.Floating.FSGNJN),
        FSGNJX_S -> floatingBinary(NpcAluOp.Floating.FSGNJX),
        FMIN_S -> floatingBinary(NpcAluOp.Floating.FMIN),
        FMAX_S -> floatingBinary(NpcAluOp.Floating.FMAX),
        FEQ_S -> floatingToInteger(NpcAluOp.Floating.FEQ, binary = true),
        FLT_S -> floatingToInteger(NpcAluOp.Floating.FLT, binary = true),
        FLE_S -> floatingToInteger(NpcAluOp.Floating.FLE, binary = true),
        FCVT_W_S -> floatingToInteger(NpcAluOp.Floating.FCVT_W),
        FCVT_WU_S -> floatingToInteger(NpcAluOp.Floating.FCVT_WU),
        FCVT_S_W -> integerToFloating(NpcAluOp.Floating.FCVT_S_W),
        FCVT_S_WU -> integerToFloating(NpcAluOp.Floating.FCVT_S_WU),
        FMV_X_W -> floatingToInteger(NpcAluOp.Floating.FMV_X_W),
        FCLASS_S -> floatingToInteger(NpcAluOp.Floating.FCLASS),
        FMV_W_X -> integerToFloating(NpcAluOp.Floating.FMV_W_X)
      ) ++ (if (cfg.xlen == 64) Seq(
        FCVT_L_S -> floatingToInteger(NpcAluOp.Floating.FCVT_L),
        FCVT_LU_S -> floatingToInteger(NpcAluOp.Floating.FCVT_LU),
        FCVT_S_L -> integerToFloating(NpcAluOp.Floating.FCVT_S_L),
        FCVT_S_LU -> integerToFloating(NpcAluOp.Floating.FCVT_S_LU)
      ) else Seq.empty)
    } else {
      Seq.empty
    }

  // ListLookup 是 Chisel 常用的查表解码工具：
  //   第 1 个参数：要匹配的 key，这里是整条 instruction
  //   第 2 个参数：默认输出，没匹配到任何指令时使用 illegalInstruction
  //   第 3 个参数：匹配表，Seq(BitPat -> List[UInt])
  //
  // BitPat 允许指令模式里带 ?，所以可以直接用完整 instruction 匹配 ADDI/LW/BEQ 等模式。
  // 没有匹配到任何表项时，按 RISC-V 标准走 illegal instruction exception，
  // 而不是静默当成 NOP 吞掉。
  private val decoded = ListLookup(io.instruction, illegalInstruction,
    (baseTable ++ zicsrTable ++ rv64Table ++ mTable ++ fTable).toArray)

  // 把 decoded 这条 List 拆回有名字的 Bundle 字段。
  // asBool 用在那些原本是 1 bit enable 的字段上；aluCtrl/csrOperation/executionUnit 保持 UInt 编码。
  io.signals.branch := decoded(Field.branch).asBool
  io.signals.loadEnable := decoded(Field.loadEnable).asBool
  io.signals.writebackFromMemory := decoded(Field.writebackFromMemory).asBool
  io.signals.storeEnable := decoded(Field.storeEnable).asBool
  io.signals.useImmediate := decoded(Field.useImmediate).asBool
  io.signals.registerWriteEnable := decoded(Field.registerWriteEnable).asBool
  io.signals.usesRs1 := decoded(Field.usesRs1).asBool
  io.signals.usesRs2 := decoded(Field.usesRs2).asBool
  io.signals.executionUnit := decoded(Field.executionUnit)
  io.signals.aluCtrl := decoded(Field.aluCtrl)
  io.signals.privilegedInstruction := decoded(Field.privilegedInstruction).asBool
  io.signals.trapEnable := decoded(Field.trapEnable).asBool
  io.signals.trapCause := decoded(Field.trapCause)
  io.signals.mretEnable := decoded(Field.mretEnable).asBool
  io.signals.csrEnable := decoded(Field.csrEnable).asBool
  io.signals.csrOperation := decoded(Field.csrOperation)
  io.signals.csrUseImmediate := decoded(Field.csrUseImmediate).asBool
  io.signals.csrReadWritebackEnable := decoded(Field.csrReadWritebackEnable).asBool
  io.signals.floatingOperation := decoded(Field.floatingOperation).asBool
  io.signals.floatingInstruction := decoded(Field.floatingInstruction).asBool
  io.signals.floatRegisterWriteEnable := decoded(Field.floatRegisterWriteEnable).asBool
  io.signals.usesFrs1 := decoded(Field.usesFrs1).asBool
  io.signals.usesFrs2 := decoded(Field.usesFrs2).asBool
  io.signals.usesFrs3 := decoded(Field.usesFrs3).asBool
}
