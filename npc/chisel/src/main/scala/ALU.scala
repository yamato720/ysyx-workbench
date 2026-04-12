package scpu

import chisel3._
import chisel3.util._


class ALU_Top(cfg: ISAConfig = ISAConfig(), USEDPI:Boolean = true) extends Module{
  val io = IO(new Bundle{
    val extSel = Input(UInt(ExtSelBits.extSelWidth.W))
    val a_in = Input(UInt(cfg.xlen.W))
    val b_in = Input(UInt(cfg.xlen.W))
    val alu_ctrl_in = Input(UInt(5.W))
    val tick_idex   = Input(Bool())
    val pc          = Input(UInt(cfg.xlen.W))
    val alu_result = Output(UInt(cfg.xlen.W))
    val branch_taken = Output(UInt(3.W))
  })

  val i_ALU_inst = Module(new ALU_I(Width = cfg.xlen))
  i_ALU_inst.io.a_in := io.a_in
  i_ALU_inst.io.b_in := io.b_in
  i_ALU_inst.io.alu_ctrl_in := io.alu_ctrl_in
  i_ALU_inst.io.tick_idex := io.tick_idex
  i_ALU_inst.io.pc := io.pc

  val i_alu_result = RegInit(0.U(cfg.xlen.W))
  val branch_taken = RegInit(0.U(cfg.xlen.W))

  i_alu_result := i_ALU_inst.io.alu_result
  branch_taken := i_ALU_inst.io.branch_taken

  val m_alu_result = RegInit(0.U(cfg.xlen.W))

  if(cfg.M){
    val m_ALU_inst = Module(new ALU_M(Width = cfg.xlen, USEDPI = USEDPI))
    m_ALU_inst.io.a_in := io.a_in
    m_ALU_inst.io.b_in := io.b_in
    m_ALU_inst.io.alu_ctrl_in := io.alu_ctrl_in
    m_ALU_inst.io.tick_idex := io.tick_idex

    m_alu_result := m_ALU_inst.io.alu_result
  }

  val sel_i = RegInit(UInt(1.W), 0.U)
  val sel_m = RegInit(UInt(1.W), 0.U)

  when(io.tick_idex){
    sel_i := io.extSel(ExtSelBits.I)
    if(cfg.M){
      sel_m := io.extSel(ExtSelBits.M)
    }
  }


  when(sel_i === 1.U){
    io.alu_result := i_alu_result
    io.branch_taken := branch_taken
  }.elsewhen(sel_m === 1.U){
    io.alu_result := m_alu_result
    io.branch_taken := 0.U  // M-extension指令不涉及分支，branch_taken输出为0
  }.otherwise{
    // 默认输出，可以根据需要设置为某个默认值或保持之前的值
    io.alu_result := 0.U
    io.branch_taken := 0.U
  }

}


class ALU_I(Width:Int = 32) extends Module{
  var io = IO(new Bundle{
    val a_in = Input(UInt(Width.W))
    val b_in = Input(UInt(Width.W))
    val alu_ctrl_in = Input(UInt(5.W))
    val tick_idex   = Input(Bool())
    val pc          = Input(UInt(Width.W))
    val alu_result = Output(UInt(Width.W))
    val branch_taken = Output(UInt(Width.W))
  })

  /*
  branch_taken:
  000: not taken
  001: taken pc + imm
  010: taken rs1 + imm
  */
  var a = RegInit(0.U(Width.W))
  var b = RegInit(0.U(Width.W))
  var alu_ctrl = RegInit(0.U(5.W))
  var pc_reg = RegInit(0.U(Width.W))

  // 创建内部寄存器来保存输出值
  val alu_result_reg = RegInit(0.U(Width.W))
  val branch_taken_reg = RegInit(0.U(3.W))

  // 将内部寄存器连接到output端口
  io.alu_result := alu_result_reg
  io.branch_taken := branch_taken_reg


  when(io.tick_idex) {
    a := io.a_in
    b := io.b_in
    alu_ctrl := io.alu_ctrl_in
    pc_reg := io.pc
    // tick_idex时不更新输出寄存器，自动维持上一个值
  }.otherwise{
    when(alu_ctrl === "b00000".U) { // ADD
      val add_result = a +& b  // 使用 +& 进行 Width+1 位加法
      alu_result_reg := add_result(Width-1, 0)
      branch_taken_reg := 0.U
    }.elsewhen(alu_ctrl === "b00001".U) { // SUB
      val sub_result = a -& b  // 使用 -& 进行 Width+1 位减法
      alu_result_reg := sub_result(Width-1, 0)
      branch_taken_reg := 0.U
    }.elsewhen(alu_ctrl === "b00010".U) { // AND
      alu_result_reg := a & b
      branch_taken_reg := 0.U
    }.elsewhen(alu_ctrl === "b00011".U) { // A OR B
      alu_result_reg := a | b
      branch_taken_reg := 0.U
    }.elsewhen(alu_ctrl === "b00100".U) { // A XOR B
      alu_result_reg := a ^ b
      branch_taken_reg := 0.U
    }.elsewhen(alu_ctrl === "b00101".U) { // SLL
      alu_result_reg := a << b(log2Ceil(Width)-1, 0)
      branch_taken_reg := 0.U
    }.elsewhen(alu_ctrl === "b00110".U) { // SRL
      alu_result_reg := a >> b(log2Ceil(Width)-1, 0)
      branch_taken_reg := 0.U
    }.elsewhen(alu_ctrl === "b00111".U) { // SRA
      alu_result_reg := (a.asSInt >> b(log2Ceil(Width)-1, 0)).asUInt
      branch_taken_reg := 0.U
    }.elsewhen(alu_ctrl === "b01000".U){  // SLT
      val slt_result = a.asSInt < b.asSInt
      alu_result_reg := slt_result
      branch_taken_reg := 0.U
    }.elsewhen(alu_ctrl === "b01001".U) { // SLTU
      val sltu_result = a < b
      alu_result_reg := sltu_result
      branch_taken_reg := 0.U
    }.elsewhen(alu_ctrl === "b01010".U) { // BEQ
      val beq_result = a === b
      alu_result_reg := beq_result
      branch_taken_reg := Mux(beq_result === 1.U, "b001".U, 0.U)
    }.elsewhen(alu_ctrl === "b01011".U) { // BNE
      val bne_result = a =/= b
      alu_result_reg := bne_result
      branch_taken_reg := Mux(bne_result === 1.U, "b001".U, 0.U)
    }.elsewhen(alu_ctrl === "b01100".U) { // BLT
      val blt_result = a.asSInt < b.asSInt
      alu_result_reg := blt_result
      branch_taken_reg := Mux(blt_result === 1.U, "b001".U, 0.U)
    }.elsewhen(alu_ctrl === "b01101".U){ // BGE
      val bge_result = a.asSInt >= b.asSInt
      alu_result_reg := bge_result
      branch_taken_reg := Mux(bge_result === 1.U, "b001".U, 0.U)
    }.elsewhen(alu_ctrl === "b01110".U) { // BLTU
      val bltu_result = a < b
      alu_result_reg := bltu_result
      branch_taken_reg := Mux(bltu_result === 1.U, "b001".U, 0.U)
    }.elsewhen(alu_ctrl === "b01111".U){ // BGEU
      val bgeu_result = a >= b
      alu_result_reg := bgeu_result
      branch_taken_reg := Mux(bgeu_result === 1.U, "b001".U, 0.U)
    }.elsewhen(alu_ctrl === "b10000".U) { // AUIPC
      alu_result_reg := pc_reg + b
      branch_taken_reg := 0.U
    }.elsewhen(alu_ctrl === "b10001".U) { // LUI
      alu_result_reg := b
      branch_taken_reg := 0.U
    }.elsewhen(alu_ctrl === "b10010".U) { // JAL
      alu_result_reg := pc_reg + 4.U
      branch_taken_reg := "b001".U  // JAL uses PC+imm, same as Branch (select mux in1)
    }.elsewhen(alu_ctrl === "b10011".U) { // JALR
      alu_result_reg := pc_reg + 4.U
      branch_taken_reg := "b010".U  // JALR uses rs1+imm (select mux in2)
    }.otherwise{
      if(Width == 64){
        when(alu_ctrl === "b10100".U){ // ADDW
          val addw_result = (a(31,0) + b(31,0))(31,0)  // 32位加法，只取低32位
          val sign_extended = Cat(Fill(32, addw_result(31)), addw_result).asUInt  // 符号扩展
          alu_result_reg := sign_extended
          branch_taken_reg := 0.U
        }.elsewhen(alu_ctrl === "b10101".U){ // SUBW
          val subw_result = (a(31,0) - b(31,0))(31,0)  // 32位减法，只取低32位
          val sign_extended = Cat(Fill(32, subw_result(31)), subw_result).asUInt  // 符号扩展
          alu_result_reg := sign_extended
          branch_taken_reg := 0.U
        }.elsewhen(alu_ctrl === "b10110".U){ // SLLW
          val sllw_result = (a(31,0) << b(4,0))(31,0)  // 左移，只取低32位
          val sign_extended = Cat(Fill(32, sllw_result(31)), sllw_result).asUInt  // 符号扩展
          alu_result_reg := sign_extended
          branch_taken_reg := 0.U
        }.elsewhen(alu_ctrl === "b10111".U){ // SRLW
          val srlw_result = (a(31,0) >> b(4,0))(31,0)  // 逻辑右移，只取低32位
          val sign_extended = Cat(Fill(32, srlw_result(31)), srlw_result).asUInt  // 符号扩展
          alu_result_reg := sign_extended
          branch_taken_reg := 0.U
        }.elsewhen(alu_ctrl === "b11000".U){ // SRAW
          val sraw_result = (a(31,0).asSInt >> b(4,0)).asUInt(31,0)  // 算术右移，只取低32位
          val sign_extended = Cat(Fill(32, sraw_result(31)), sraw_result).asUInt  // 符号扩展
          alu_result_reg := sign_extended
          branch_taken_reg := 0.U
        }
      }
      // otherwise分支不需要了，寄存器会自动保持值
    }


  }
}

class ALU_M(Width:Int = 32, USEDPI:Boolean = true) extends Module{
  val io = IO(new Bundle{
    val a_in = Input(UInt(Width.W))
    val b_in = Input(UInt(Width.W))
    val alu_ctrl_in = Input(UInt(5.W))
    val tick_idex   = Input(Bool())
    val alu_result = Output(UInt(Width.W))
  })

  val a = RegInit(0.U(Width.W))
  val b = RegInit(0.U(Width.W))
  val alu_ctrl = RegInit(0.U(5.W))

  // 创建内部寄存器来保存输出值
  val alu_result_reg = RegInit(0.U(Width.W))
  val alu_sel = RegInit(0.U(5.W))

  // 将内部寄存器连接到output端口
  io.alu_result := alu_result_reg
  if(USEDPI)
  {
    val alu_core = Module(new ALU_Core_M())
    alu_result_reg := alu_core.io.result
    alu_core.io.a := a
    alu_core.io.b := b
    alu_core.io.sel := alu_sel
    alu_core.io.clk := clock
    alu_core.io.rst := reset.asBool

    when(io.tick_idex){
      a := io.a_in
      b := io.b_in
      alu_ctrl := io.alu_ctrl_in
      if(Width == 64) {
        when(io.alu_ctrl_in === "b00000".U) { // MUL
          alu_sel := "b00001".U
        }.elsewhen(io.alu_ctrl_in === "b00001".U) { // MULH
          alu_sel := "b00011".U
        }.elsewhen(io.alu_ctrl_in === "b00010".U) { // MULHSU
          alu_sel := "b00101".U
        }.elsewhen(io.alu_ctrl_in === "b00011".U) { // MULHU
          alu_sel := "b00111".U
        }.elsewhen(io.alu_ctrl_in === "b00100".U) { // DIV
          alu_sel := "b01001".U
        }.elsewhen(io.alu_ctrl_in === "b00101".U) { // DIVU
          alu_sel := "b01011".U
        }.elsewhen(io.alu_ctrl_in === "b00110".U) { // REM
          alu_sel := "b01101".U
        }.elsewhen(io.alu_ctrl_in === "b00111".U) { // REMU
          alu_sel := "b01111".U
        }.elsewhen(io.alu_ctrl_in === "b01000".U) { // MULW
          alu_sel := "b10000".U
        }.elsewhen(io.alu_ctrl_in === "b01001".U) { // DIVW
          alu_sel := "b10001".U
        }.elsewhen(io.alu_ctrl_in === "b01010".U) { // DIVUW
          alu_sel := "b10100".U  // sel 20 → divuw_unit
        }.elsewhen(io.alu_ctrl_in === "b01011".U) { // REMW
          alu_sel := "b10010".U  // sel 18 → remw_unit
        }.elsewhen(io.alu_ctrl_in === "b01100".U) { // REMUW
          alu_sel := "b10011".U  // sel 19 → remuw_unit
        }
      }else{
        when(io.alu_ctrl_in === "b00000".U) { // MUL
          alu_sel := "b00000".U
        }.elsewhen(io.alu_ctrl_in === "b00001".U) { // MULH
          alu_sel := "b00010".U
        }.elsewhen(io.alu_ctrl_in === "b00010".U) { // MULHSU
          alu_sel := "b00100".U
        }.elsewhen(io.alu_ctrl_in === "b00011".U) { // MULHU
          alu_sel := "b00110".U
        }.elsewhen(io.alu_ctrl_in === "b00100".U) { // DIV
          alu_sel := "b01000".U
        }.elsewhen(io.alu_ctrl_in === "b00101".U) { // DIVU
          alu_sel := "b01010".U
        }.elsewhen(io.alu_ctrl_in === "b00110".U) { // REM
          alu_sel := "b01100".U
        }.elsewhen(io.alu_ctrl_in === "b00111".U) { // REMU
          alu_sel := "b01110".U
        }
      }
    }
  }




}






// ─────────────────────────────────────────────────────────────────────────────
// Priv_Exec — 特权/Zicsr 执行单元
//
//   tick_idex 到来时锁存所有操作数，随后组合计算结果稳定输出。
//   外部（top.scala）自行用 tick_memwb 门控 csr_we / trap_en / mret_en。
//
//   Zicsr 开关（cfg.Zicsr）:
//     true  — 完整 CSRRW/CSRRS/CSRRC 及 immediate 变体
//     false — CSR 写通路编译期关闭（csr_we 恒 false）
//
//   csrOp:  00=write  01=set  10=clear
//   csrImm: 0=rs1  1=ZeroExt(zimm[4:0])
// ─────────────────────────────────────────────────────────────────────────────
class Priv_Exec(cfg: ISAConfig = ISAConfig()) extends Module {
  val io = IO(new Bundle {
    // ── 控制信号 ──────────────────────────────────────────────────────────
    val csrEn    = Input(Bool())
    val csrOp    = Input(UInt(2.W))
    val csrImm   = Input(Bool())
    val trapEn   = Input(Bool())
    val mretEn   = Input(Bool())

    // ── 时序 ──────────────────────────────────────────────────────────────
    val tick_idex = Input(Bool())   // 锁存操作数（EX 级入口）

    // ── 数据输入 ──────────────────────────────────────────────────────────
    val rs1_data = Input(UInt(cfg.xlen.W))
    val zimm     = Input(UInt(5.W))          // inst[19:15]
    val csr_addr = Input(UInt(12.W))         // inst[31:20]
    val pc       = Input(UInt(cfg.xlen.W))
    val old_csr  = Input(UInt(cfg.xlen.W))   // CSRs 组合读出旧值（tick_idex 时采样）

    // ── 稳定输出（外部用 tick_memwb 自行门控写使能）────────────────────────
    val csr_addr_out = Output(UInt(12.W))
    val csr_we       = Output(Bool())            // 需写 CSR（外部 && tick_memwb 后驱动 CSRs.we）
    val csr_wdata    = Output(UInt(cfg.xlen.W))
    val csr_allow    = Output(Bool())
    val trap_en      = Output(Bool())            // 需 trap（外部 && tick_memwb 后驱动 CSRs.trap_en）
    val trap_cause   = Output(UInt(cfg.xlen.W))
    val trap_epc     = Output(UInt(cfg.xlen.W))
    val mret_en      = Output(Bool())            // 需 mret（外部 && tick_memwb 后驱动 CSRs.mret_en）
    val rd_wdata     = Output(UInt(cfg.xlen.W))  // CSR 旧值 → rd
  })

  // ── 合法 CSR 地址集 ──────────────────────────────────────────────────────
  val validAddrs = Seq(
    CSRMap.mvendorid, CSRMap.marchid, CSRMap.mimpid, CSRMap.mhartid,
    CSRMap.mstatus,   CSRMap.misa,    CSRMap.mie,     CSRMap.mtvec,
    CSRMap.mscratch,  CSRMap.mepc,    CSRMap.mcause,  CSRMap.mtval, CSRMap.mip
  ).map(_.U(12.W))

  // ── tick_idex：锁存所有输入 ───────────────────────────────────────────────
  val r_csrEn   = RegInit(false.B)
  val r_csrOp   = RegInit(0.U(2.W))
  val r_csrImm  = RegInit(false.B)
  val r_trapEn  = RegInit(false.B)
  val r_mretEn  = RegInit(false.B)
  val r_rs1     = RegInit(0.U(cfg.xlen.W))
  val r_zimm    = RegInit(0.U(5.W))
  val r_addr    = RegInit(0.U(12.W))
  val r_pc      = RegInit(0.U(cfg.xlen.W))
  val r_old_csr = RegInit(0.U(cfg.xlen.W))

  when(io.tick_idex) {
    r_csrEn   := io.csrEn
    r_csrOp   := io.csrOp
    r_csrImm  := io.csrImm
    r_trapEn  := io.trapEn
    r_mretEn  := io.mretEn
    r_rs1     := io.rs1_data
    r_zimm    := io.zimm
    r_addr    := io.csr_addr
    r_pc      := io.pc
    r_old_csr := io.old_csr
  }

  // ── 地址合法性 ───────────────────────────────────────────────────────────
  val addrValid = validAddrs.map(_ === r_addr).reduce(_ || _)

  // ── 稳定输出 ─────────────────────────────────────────────────────────────
  io.csr_addr_out := r_addr
  io.csr_allow    := addrValid
  io.rd_wdata     := r_old_csr
  io.trap_en      := r_trapEn
  io.trap_epc     := r_pc
  io.trap_cause   := 11.U(cfg.xlen.W)  // M-mode environment call
  io.mret_en      := r_mretEn

  if (cfg.Zicsr) {
    val src = Mux(r_csrImm,
      r_zimm.asTypeOf(UInt(cfg.xlen.W)),
      r_rs1
    )
    val wdata = WireDefault(0.U(cfg.xlen.W))
    when(r_csrOp === "b00".U) {
      wdata := src
    }.elsewhen(r_csrOp === "b01".U) {
      wdata := r_old_csr | src
    }.elsewhen(r_csrOp === "b10".U) {
      wdata := r_old_csr & ~src
    }
    io.csr_wdata := wdata
    io.csr_we    := r_csrEn && addrValid
  } else {
    io.csr_wdata := 0.U
    io.csr_we    := false.B
  }
}
