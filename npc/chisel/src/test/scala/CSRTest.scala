package scpu

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

// ─────────────────────────────────────────────────────────────────────────────
// CSR_Datapath: 包含完整 OpcodeCtrlTop + Priv_Exec + CSRs 的测试 DUT 模块
//
//   输入裸指令（32 位），内部提取 opcode/funct3/funct7/csr_addr/zimm。
//   无 InsBuffer / InsCacheL1 / ALU / DataMemory，只保留 CSR 全路径。
//
//   节拍语义（与流水线一致）：
//     tick_idex  ── Priv_Exec 锁存操作数（同时采样 CSRs 组合读旧值）
//     tick_memwb ── CSRs 实际写入（门控 csr_we / trap_en / mret_en）
// ─────────────────────────────────────────────────────────────────────────────
class CSR_Datapath(cfg: ISAConfig = ISAConfig()) extends Module {
  val io = IO(new Bundle {
    // ── 输入 ─────────────────────────────────────────────────────────────
    val instruction = Input(UInt(32.W))
    val rs1_data    = Input(UInt(cfg.xlen.W))
    val pc          = Input(UInt(cfg.xlen.W))
    val tick_idex   = Input(Bool())
    val tick_memwb  = Input(Bool())

    // ── OpcodeCtrlTop 控制信号透出（组合，即时有效）────────────────────
    val privSel     = Output(Bool())
    val trapEn_ctrl = Output(Bool())   // 原始组合输出（未锁存）
    val mretEn_ctrl = Output(Bool())
    val csrEn       = Output(Bool())
    val csrOp       = Output(UInt(2.W))
    val csrImm      = Output(Bool())
    val csrRegWrite = Output(Bool())
    val regWrite    = Output(Bool())

    // ── Priv_Exec 锁存后输出（tick_idex 后稳定）─────────────────────────
    val rd_wdata    = Output(UInt(cfg.xlen.W))   // CSR 旧值 → GPR rd
    val csr_wdata   = Output(UInt(cfg.xlen.W))   // 计算后待写入 CSR 的值
    val csr_we      = Output(Bool())             // csrEn && addrValid（未门控）
    val csr_allow   = Output(Bool())             // 地址合法标记
    val trap_en     = Output(Bool())             // r_trapEn（未门控）
    val mret_en     = Output(Bool())             // r_mretEn（未门控）

    // ── CSRs 当前值（组合读，始终有效）─────────────────────────────────
    val rdata       = Output(UInt(cfg.xlen.W))   // 当前 csr_addr 对应旧值
    val mtvec_out   = Output(UInt(cfg.xlen.W))
    val mepc_out    = Output(UInt(cfg.xlen.W))
  })

  // ── 指令字段提取（纯组合） ────────────────────────────────────────────
  val opcode   = io.instruction(6, 0)
  val funct3   = io.instruction(14, 12)
  val funct7   = io.instruction(31, 25)
  val csr_addr = io.instruction(31, 20)   // CSR 地址字段 inst[31:20]
  val zimm     = io.instruction(19, 15)   // rs1 字段 = uimm[4:0] for csrrwi/si/ci

  // ── OpcodeCtrlTop（完整搬入，验证指令分发） ──────────────────────────
  val ctrl = Module(new OpcodeCtrlTop(cfg = cfg))
  ctrl.io.opcode := opcode
  ctrl.io.funct3 := funct3
  ctrl.io.funct7 := funct7
  io.privSel     := ctrl.io.privSel
  io.trapEn_ctrl := ctrl.io.trapEn
  io.mretEn_ctrl := ctrl.io.mretEn
  io.csrEn       := ctrl.io.csrEn
  io.csrOp       := ctrl.io.csrOp
  io.csrImm      := ctrl.io.csrImm
  io.csrRegWrite := ctrl.io.csrRegWrite
  io.regWrite    := ctrl.io.regWrite

  // ── CSRs（寄存器组）─────────────────────────────────────────────────
  val csrs = Module(new CSRs(cfg = cfg))
  csrs.io.addr := csr_addr   // 组合读：地址即时导入

  // ── Priv_Exec（执行单元）────────────────────────────────────────────
  val priv = Module(new Priv_Exec(cfg = cfg))
  priv.io.csrEn     := ctrl.io.csrEn
  priv.io.csrOp     := ctrl.io.csrOp
  priv.io.csrImm    := ctrl.io.csrImm
  priv.io.trapEn    := ctrl.io.trapEn
  priv.io.mretEn    := ctrl.io.mretEn
  priv.io.tick_idex := io.tick_idex
  priv.io.rs1_data  := io.rs1_data
  priv.io.zimm      := zimm
  priv.io.csr_addr  := csr_addr
  priv.io.pc        := io.pc
  priv.io.old_csr   := csrs.io.rdata   // 组合读旧值，tick_idex 时被锁存

  // ── CSRs 写端口（tick_memwb 在此模块内门控）──────────────────────────
  csrs.io.wdata      := priv.io.csr_wdata
  csrs.io.we         := priv.io.csr_we   && io.tick_memwb
  csrs.io.allow      := priv.io.csr_allow
  csrs.io.trap_en    := priv.io.trap_en  && io.tick_memwb
  csrs.io.trap_cause := priv.io.trap_cause
  csrs.io.trap_epc   := priv.io.trap_epc
  csrs.io.mret_en    := priv.io.mret_en  && io.tick_memwb

  // ── IO 输出 ──────────────────────────────────────────────────────────
  io.rd_wdata   := priv.io.rd_wdata
  io.csr_wdata  := priv.io.csr_wdata
  io.csr_we     := priv.io.csr_we
  io.csr_allow  := priv.io.csr_allow
  io.trap_en    := priv.io.trap_en
  io.mret_en    := priv.io.mret_en
  io.rdata      := csrs.io.rdata
  io.mtvec_out  := csrs.io.mtvec_out
  io.mepc_out   := csrs.io.mepc_out
}


class CSRTest extends AnyFlatSpec with ChiselScalatestTester {

  // ── 辅助：构造 CSR 指令编码 ──────────────────────────────────────────
  //   csr:    12-bit CSR 地址
  //   rs1:    5-bit 寄存器号（或 uimm[4:0]，csrrxxi 变体）
  //   funct3: 001=csrrw 010=csrrs 011=csrrc 101=csrrwi 110=csrrsi 111=csrrci
  //   rd:     5-bit 目标寄存器
  def mkCsrInst(csr: Int, rs1: Int, funct3: Int, rd: Int): Long = {
    ((csr.toLong  & 0xFFF) << 20) |
    ((rs1.toLong  & 0x1F)  << 15) |
    ((funct3.toLong & 0x7) << 12) |
    ((rd.toLong   & 0x1F)  << 7)  |
    0x73L
  }

  val ECALL: Long = 0x00000073L   // ecall
  val MRET:  Long = 0x30200073L   // mret

  // 全扩展 64-bit（Zicsr=true 为默认）
  val cfg = ISAConfig(M = true)

  // ── tick 辅助 ─────────────────────────────────────────────────────────
  def pulseIdex(c: CSR_Datapath): Unit = {
    c.io.tick_idex.poke(true.B);  c.clock.step(1)
    c.io.tick_idex.poke(false.B); c.clock.step(1)
  }
  def pulseMemwb(c: CSR_Datapath): Unit = {
    c.io.tick_memwb.poke(true.B);  c.clock.step(1)
    c.io.tick_memwb.poke(false.B); c.clock.step(1)
  }

  // ── 测试组 ────────────────────────────────────────────────────────────
  behavior of "CSR_Datapath"

  // ────────────────────────────────────────────────────────────────────────
  // 1. OpcodeCtrlTop 分发验证：CSRRW
  // ────────────────────────────────────────────────────────────────────────
  it should "CSRRW: OpcodeCtrlTop 正确分发 privSel/csrEn/csrOp/csrImm/csrRegWrite" in {
    test(new CSR_Datapath(cfg)).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      c.io.tick_idex.poke(false.B)
      c.io.tick_memwb.poke(false.B)

      // CSRRW x1, mtvec(0x305), x1  → funct3=001, csrOp=00, csrImm=false
      c.io.instruction.poke(mkCsrInst(0x305, 1, 1, 1).U)
      c.io.rs1_data.poke(0.U)
      c.io.pc.poke(0.U)
      c.clock.step(1)

      c.io.privSel.expect(true.B,     "CSRRW 应触发 privSel")
      c.io.csrEn.expect(true.B,       "CSRRW 应触发 csrEn")
      c.io.csrOp.expect(0.U,          "CSRRW csrOp=00 (write)")
      c.io.csrImm.expect(false.B,     "CSRRW 非立即数变体，csrImm=false")
      c.io.csrRegWrite.expect(true.B, "CSRRW 将旧 CSR 值写回 rd")
      c.io.trapEn_ctrl.expect(false.B,"CSRRW 不触发 trap")
      c.io.mretEn_ctrl.expect(false.B,"CSRRW 不触发 mret")
    }
  }

  // ────────────────────────────────────────────────────────────────────────
  // 2. OpcodeCtrlTop 分发验证：CSRRS / CSRRC csrOp 字段
  // ────────────────────────────────────────────────────────────────────────
  it should "CSRRS/CSRRC: OpcodeCtrlTop csrOp 分别为 01/10" in {
    test(new CSR_Datapath(cfg)).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      c.io.tick_idex.poke(false.B)
      c.io.tick_memwb.poke(false.B)
      c.io.rs1_data.poke(0.U)
      c.io.pc.poke(0.U)

      // CSRRS x1, mstatus(0x300), x1  → funct3=010
      c.io.instruction.poke(mkCsrInst(0x300, 1, 2, 1).U)
      c.clock.step(1)
      c.io.csrOp.expect(1.U, "CSRRS csrOp=01 (set)")
      c.io.csrImm.expect(false.B)

      // CSRRC x1, mstatus(0x300), x1  → funct3=011
      c.io.instruction.poke(mkCsrInst(0x300, 1, 3, 1).U)
      c.clock.step(1)
      c.io.csrOp.expect(2.U, "CSRRC csrOp=10 (clear)")
    }
  }

  // ────────────────────────────────────────────────────────────────────────
  // 3. OpcodeCtrlTop 分发验证：立即数变体（funct3 bit2=1 → csrImm=true）
  // ────────────────────────────────────────────────────────────────────────
  it should "CSRRWI/CSRRSI/CSRRCI: csrImm=true 且 csrOp 正确" in {
    test(new CSR_Datapath(cfg)).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      c.io.tick_idex.poke(false.B)
      c.io.tick_memwb.poke(false.B)
      c.io.rs1_data.poke(0.U)
      c.io.pc.poke(0.U)

      // CSRRWI x1, mtvec(0x305), uimm=7  → funct3=101
      c.io.instruction.poke(mkCsrInst(0x305, 7, 5, 1).U)
      c.clock.step(1)
      c.io.csrImm.expect(true.B, "CSRRWI csrImm=true")
      c.io.csrOp.expect(0.U,     "CSRRWI csrOp=00")

      // CSRRSI x1, mstatus(0x300), uimm=3  → funct3=110
      c.io.instruction.poke(mkCsrInst(0x300, 3, 6, 1).U)
      c.clock.step(1)
      c.io.csrImm.expect(true.B, "CSRRSI csrImm=true")
      c.io.csrOp.expect(1.U,     "CSRRSI csrOp=01")

      // CSRRCI x1, mstatus(0x300), uimm=1  → funct3=111
      c.io.instruction.poke(mkCsrInst(0x300, 1, 7, 1).U)
      c.clock.step(1)
      c.io.csrImm.expect(true.B, "CSRRCI csrImm=true")
      c.io.csrOp.expect(2.U,     "CSRRCI csrOp=10")
    }
  }

  // ────────────────────────────────────────────────────────────────────────
  // 4. OpcodeCtrlTop 分发验证：ECALL / MRET
  // ────────────────────────────────────────────────────────────────────────
  it should "ECALL/MRET: OpcodeCtrlTop 正确置 trapEn/mretEn，csrEn=false" in {
    test(new CSR_Datapath(cfg)).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      c.io.tick_idex.poke(false.B)
      c.io.tick_memwb.poke(false.B)
      c.io.rs1_data.poke(0.U)
      c.io.pc.poke(0.U)

      c.io.instruction.poke(ECALL.U)
      c.clock.step(1)
      c.io.privSel.expect(true.B,      "ECALL 触发 privSel")
      c.io.trapEn_ctrl.expect(true.B,  "ECALL 触发 trapEn")
      c.io.mretEn_ctrl.expect(false.B, "ECALL 不触发 mretEn")
      c.io.csrEn.expect(false.B,       "ECALL 不是 CSR 读写指令")

      c.io.instruction.poke(MRET.U)
      c.clock.step(1)
      c.io.privSel.expect(true.B,      "MRET 触发 privSel")
      c.io.mretEn_ctrl.expect(true.B,  "MRET 触发 mretEn")
      c.io.trapEn_ctrl.expect(false.B, "MRET 不触发 trapEn")
      c.io.csrEn.expect(false.B,       "MRET 不是 CSR 读写指令")
    }
  }

  // ────────────────────────────────────────────────────────────────────────
  // 5. 地址非法：csr_allow=false，csr_we=false，不写入任何 CSR
  // ────────────────────────────────────────────────────────────────────────
  it should "非法 CSR 地址: csr_allow=false, csr_we=false，寄存器不更新" in {
    test(new CSR_Datapath(cfg)).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      c.io.tick_idex.poke(false.B)
      c.io.tick_memwb.poke(false.B)

      // 地址 0x001 不在合法集合中
      c.io.instruction.poke(mkCsrInst(0x001, 1, 1, 1).U)
      c.io.rs1_data.poke(0xDEADBEEFL.U)
      c.io.pc.poke(0.U)
      pulseIdex(c)

      c.io.csr_allow.expect(false.B, "0x001 为非法地址，csr_allow=false")
      c.io.csr_we.expect(false.B,    "非法地址不应写入")

      // 即使 tick_memwb，CSRs 也不应改变（已由 csr_we=false 保护）
      pulseMemwb(c)
      c.io.instruction.poke(mkCsrInst(0x300, 0, 1, 0).U)   // 读 mstatus
      c.io.rs1_data.poke(0.U)
      c.clock.step(1)
      c.io.rdata.expect(0.U, "mstatus 不应被非法写入污染，仍为 0")
    }
  }

  // ────────────────────────────────────────────────────────────────────────
  // 6. CSRRW：写入 mtvec，验证旧值读回 rd，新值写入 CSR
  // ────────────────────────────────────────────────────────────────────────
  it should "CSRRW: rd ← 旧 CSR 值，新值写入 mtvec" in {
    test(new CSR_Datapath(cfg)).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      c.io.tick_idex.poke(false.B)
      c.io.tick_memwb.poke(false.B)

      val newMtvec = 0x80000000L

      // mtvec 初始为 0；CSRRW x1, mtvec(0x305), x1，写入 0x80000000
      c.io.instruction.poke(mkCsrInst(0x305, 1, 1, 1).U)
      c.io.rs1_data.poke(newMtvec.U)
      c.io.pc.poke(0.U)

      pulseIdex(c)   // 锁存：r_old_csr=0（旧 mtvec），r_rs1=0x80000000
      c.io.rd_wdata.expect(0.U,          "rd ← 旧 mtvec=0")
      c.io.csr_wdata.expect(newMtvec.U,  "待写入新值=0x80000000")
      c.io.csr_we.expect(true.B,         "地址合法且 csrEn，csr_we=true")
      c.io.csr_allow.expect(true.B,      "mtvec(0x305) 为合法地址")

      pulseMemwb(c)   // 提交写入

      // 读回验证（切换指令读 mtvec，不需要 tick_idex）
      c.io.instruction.poke(mkCsrInst(0x305, 0, 1, 0).U)   // CSRRW x0, mtvec, x0
      c.io.rs1_data.poke(0.U)
      c.clock.step(1)
      c.io.rdata.expect(newMtvec.U, s"提交后 mtvec 应为 0x${newMtvec.toHexString}")
    }
  }

  // ────────────────────────────────────────────────────────────────────────
  // 7. CSRRS：置位测试
  // ────────────────────────────────────────────────────────────────────────
  it should "CSRRS: mstatus |= rs1（置位）" in {
    test(new CSR_Datapath(cfg)).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      c.io.tick_idex.poke(false.B)
      c.io.tick_memwb.poke(false.B)
      c.io.pc.poke(0.U)

      // Step1：初始化 mstatus = 0x8（MIE，bit3）
      c.io.instruction.poke(mkCsrInst(0x300, 1, 1, 0).U)   // CSRRW x0, mstatus, x1
      c.io.rs1_data.poke(0x8L.U)
      pulseIdex(c); pulseMemwb(c)

      // Step2：CSRRS x1, mstatus, x1（rs1=0x80 → 置位 bit7=MPIE）
      c.io.instruction.poke(mkCsrInst(0x300, 1, 2, 1).U)
      c.io.rs1_data.poke(0x80L.U)
      pulseIdex(c)
      c.io.rd_wdata.expect(0x8L.U,   "rd ← 旧 mstatus=0x8")
      c.io.csr_wdata.expect(0x88L.U, "0x8 | 0x80 = 0x88")
      pulseMemwb(c)

      // 读回验证
      c.io.instruction.poke(mkCsrInst(0x300, 0, 1, 0).U)
      c.io.rs1_data.poke(0.U)
      c.clock.step(1)
      c.io.rdata.expect(0x88L.U, "mstatus 应为 0x88")
    }
  }

  // ────────────────────────────────────────────────────────────────────────
  // 8. CSRRC：清位测试
  // ────────────────────────────────────────────────────────────────────────
  it should "CSRRC: mstatus &= ~rs1（清位）" in {
    test(new CSR_Datapath(cfg)).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      c.io.tick_idex.poke(false.B)
      c.io.tick_memwb.poke(false.B)
      c.io.pc.poke(0.U)

      // 初始化 mstatus=0x88
      c.io.instruction.poke(mkCsrInst(0x300, 1, 1, 0).U)
      c.io.rs1_data.poke(0x88L.U)
      pulseIdex(c); pulseMemwb(c)

      // CSRRC x1, mstatus, x1（rs1=0x8 → 清除 bit3=MIE）
      c.io.instruction.poke(mkCsrInst(0x300, 1, 3, 1).U)
      c.io.rs1_data.poke(0x8L.U)
      pulseIdex(c)
      c.io.rd_wdata.expect(0x88L.U,  "rd ← 旧 mstatus=0x88")
      c.io.csr_wdata.expect(0x80L.U, "0x88 & ~0x8 = 0x80")
      pulseMemwb(c)

      c.io.instruction.poke(mkCsrInst(0x300, 0, 1, 0).U)
      c.io.rs1_data.poke(0.U)
      c.clock.step(1)
      c.io.rdata.expect(0x80L.U, "mstatus 应为 0x80")
    }
  }

  // ────────────────────────────────────────────────────────────────────────
  // 9. CSRRWI：立即数写入（rs1_data 应被忽略）
  // ────────────────────────────────────────────────────────────────────────
  it should "CSRRWI: 使用 uimm（忽略 rs1_data）写入 CSR" in {
    test(new CSR_Datapath(cfg)).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      c.io.tick_idex.poke(false.B)
      c.io.tick_memwb.poke(false.B)
      c.io.pc.poke(0.U)

      // CSRRWI x1, mtvec(0x305), uimm=7  (rs1 字段=7，rs1_data 应被忽略)
      c.io.instruction.poke(mkCsrInst(0x305, 7, 5, 1).U)
      c.io.rs1_data.poke(0xDEADBEEFL.U)   // 填入无关值，应被忽略
      pulseIdex(c)
      c.io.csr_wdata.expect(7.U,  "CSRRWI 写入值 = ZeroExt(uimm=7) = 7")
      c.io.csrImm.expect(true.B,  "csrImm=true（立即数变体）")
      pulseMemwb(c)

      c.io.instruction.poke(mkCsrInst(0x305, 0, 1, 0).U)
      c.io.rs1_data.poke(0.U)
      c.clock.step(1)
      c.io.rdata.expect(7.U, "mtvec 应为 7")
    }
  }

  // ────────────────────────────────────────────────────────────────────────
  // 10. CSRRSI：立即数置位
  // ────────────────────────────────────────────────────────────────────────
  it should "CSRRSI: mstatus |= uimm（立即数置位）" in {
    test(new CSR_Datapath(cfg)).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      c.io.tick_idex.poke(false.B)
      c.io.tick_memwb.poke(false.B)
      c.io.pc.poke(0.U)

      // 初始化 mstatus=0x4
      c.io.instruction.poke(mkCsrInst(0x300, 1, 1, 0).U)
      c.io.rs1_data.poke(0x4L.U)
      pulseIdex(c); pulseMemwb(c)

      // CSRRSI x1, mstatus, uimm=3  → mstatus = 0x4 | 0x3 = 0x7
      c.io.instruction.poke(mkCsrInst(0x300, 3, 6, 1).U)
      c.io.rs1_data.poke(0.U)
      pulseIdex(c)
      c.io.csr_wdata.expect(0x7L.U, "0x4 | ZeroExt(3) = 0x7")
      pulseMemwb(c)

      c.io.instruction.poke(mkCsrInst(0x300, 0, 1, 0).U)
      c.io.rs1_data.poke(0.U)
      c.clock.step(1)
      c.io.rdata.expect(0x7L.U, "mstatus 应为 0x7")
    }
  }

  // ────────────────────────────────────────────────────────────────────────
  // 11. ECALL：更新 mepc/mcause，mtvec_out 指向 trap handler
  // ────────────────────────────────────────────────────────────────────────
  it should "ECALL: mepc←pc, mcause←11, mtvec_out 有效" in {
    test(new CSR_Datapath(cfg)).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      c.io.tick_idex.poke(false.B)
      c.io.tick_memwb.poke(false.B)

      val mtvecVal = 0x80000000L
      val ecallPc  = 0x80000100L

      // 先设置 mtvec=0x80000000
      c.io.instruction.poke(mkCsrInst(0x305, 1, 1, 0).U)
      c.io.rs1_data.poke(mtvecVal.U)
      c.io.pc.poke(0.U)
      pulseIdex(c); pulseMemwb(c)

      // ECALL at pc=0x80000100
      c.io.instruction.poke(ECALL.U)
      c.io.rs1_data.poke(0.U)
      c.io.pc.poke(ecallPc.U)
      pulseIdex(c)
      c.io.trap_en.expect(true.B,          "ECALL: trap_en 锁存后为 true")
      c.io.mret_en.expect(false.B,         "ECALL: mret_en=false")
      c.io.mtvec_out.expect(mtvecVal.U,    "mtvec_out 应指向 trap handler 入口")
      pulseMemwb(c)

      c.io.mepc_out.expect(ecallPc.U,      "提交后 mepc 应保存 ECALL 所在 PC")
    }
  }

  // ────────────────────────────────────────────────────────────────────────
  // 12. MRET：mret_en 有效，mepc_out 为返回地址
  // ────────────────────────────────────────────────────────────────────────
  it should "MRET: mret_en 有效，mepc_out 即为返回地址" in {
    test(new CSR_Datapath(cfg)).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      c.io.tick_idex.poke(false.B)
      c.io.tick_memwb.poke(false.B)

      val mtvecVal = 0x80000000L
      val ecallPc  = 0x80000200L

      // Step1：设置 mtvec
      c.io.instruction.poke(mkCsrInst(0x305, 1, 1, 0).U)
      c.io.rs1_data.poke(mtvecVal.U)
      c.io.pc.poke(0.U)
      pulseIdex(c); pulseMemwb(c)

      // Step2：ECALL（设置 mepc）
      c.io.instruction.poke(ECALL.U)
      c.io.rs1_data.poke(0.U)
      c.io.pc.poke(ecallPc.U)
      pulseIdex(c); pulseMemwb(c)
      c.io.mepc_out.expect(ecallPc.U, "ecall 提交后 mepc=ecallPc")

      // Step3：MRET（在 trap handler 内执行）
      c.io.instruction.poke(MRET.U)
      c.io.rs1_data.poke(0.U)
      c.io.pc.poke(mtvecVal.U)
      pulseIdex(c)
      c.io.mret_en.expect(true.B,          "MRET 锁存后 mret_en=true")
      c.io.trap_en.expect(false.B,         "MRET 不触发 trap")
      c.io.mepc_out.expect(ecallPc.U,      "mepc_out=ecallPc（即返回地址）")
      pulseMemwb(c)
      c.io.mepc_out.expect(ecallPc.U,      "MRET 提交后 mepc 不变，仍为返回地址")
    }
  }

  // ────────────────────────────────────────────────────────────────────────
  // 13. 连续指令：CSRRW → CSRRS → CSRRC 链式执行（组合验证）
  // ────────────────────────────────────────────────────────────────────────
  it should "连续 CSRRW→CSRRS→CSRRC：链式执行结果正确" in {
    test(new CSR_Datapath(cfg)).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      c.io.tick_idex.poke(false.B)
      c.io.tick_memwb.poke(false.B)
      c.io.pc.poke(0.U)

      // 1) CSRRW mstatus=0xF0
      c.io.instruction.poke(mkCsrInst(0x300, 1, 1, 0).U)
      c.io.rs1_data.poke(0xF0L.U)
      pulseIdex(c); pulseMemwb(c)

      // 2) CSRRS mstatus |= 0x0F → 0xFF
      c.io.instruction.poke(mkCsrInst(0x300, 1, 2, 1).U)
      c.io.rs1_data.poke(0x0FL.U)
      pulseIdex(c)
      c.io.rd_wdata.expect(0xF0L.U,   "step2: rd=旧值=0xF0")
      c.io.csr_wdata.expect(0xFFL.U,  "step2: 0xF0|0x0F=0xFF")
      pulseMemwb(c)

      // 3) CSRRC mstatus &= ~0x55 → 0xFF & ~0x55 = 0xAA
      c.io.instruction.poke(mkCsrInst(0x300, 1, 3, 1).U)
      c.io.rs1_data.poke(0x55L.U)
      pulseIdex(c)
      c.io.rd_wdata.expect(0xFFL.U,   "step3: rd=旧值=0xFF")
      c.io.csr_wdata.expect(0xAAL.U,  "step3: 0xFF&~0x55=0xAA")
      pulseMemwb(c)

      // 读回最终值
      c.io.instruction.poke(mkCsrInst(0x300, 0, 1, 0).U)
      c.io.rs1_data.poke(0.U)
      c.clock.step(1)
      c.io.rdata.expect(0xAAL.U, "最终 mstatus=0xAA")
    }
  }
}
