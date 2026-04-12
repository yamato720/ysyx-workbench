package scpu
import chisel3._
import chisel3.util._

class CPU(Debug:Boolean = false, useDPI:Boolean = false,
          cfg: ISAConfig = ISAConfig()
         ) extends Module {
  val io = IO(new Bundle{
    // Debug 端口：输出所有32个寄存器的值
    val regs_debug = if (Debug) Some(Output(Vec(32, UInt(cfg.xlen.W)))) else None
    val tick_pc_debug = if(Debug) Some(Output(Bool())) else None
    val tick_ifid_debug = if(Debug) Some(Output(Bool())) else None
    val tick_idex_debug = if(Debug) Some(Output(Bool())) else None
    val tick_exmem_debug = if(Debug) Some(Output(Bool())) else None
    val tick_memwb_debug = if(Debug) Some(Output(Bool())) else None
    val pc = if(Debug) Some(Output(UInt(cfg.xlen.W))) else None
    val index = if (Debug) Some(Output(UInt(log2Ceil(10).W))) else None
    val access_cnt = if (Debug) Some(Output(UInt((log2Ceil(10)+1).W))) else None
    val waitcycles = if (Debug) Some(Output(UInt(32.W))) else None
    val busy = if(Debug) Some(Output(Bool())) else None
    val instruction = if(Debug) Some(Output(UInt(32.W))) else None
    val imm = if(Debug) Some(Output(UInt(cfg.xlen.W))) else None
    val addr_low = if(Debug) Some(Output(UInt(cfg.xlen.W))) else None
    val addr_high = if(Debug) Some(Output(UInt(cfg.xlen.W))) else None
    val pc_base = if(Debug) Some(Output(UInt(cfg.xlen.W))) else None
    val ins_high = if(Debug) Some(Output(UInt(8.W))) else None
    val ins_low = if(Debug) Some(Output(UInt(8.W))) else None
    val regs_out = if (Debug) Some(Output(Vec(128, UInt(cfg.xlen.W)))) else None
    val opcode_out = if(Debug) Some(Output(UInt(7.W))) else None
    val alu_result_out = if(Debug) Some(Output(UInt(cfg.xlen.W))) else None
    val func3_out = if(Debug) Some(Output(UInt(3.W))) else None
    val func7_out = if(Debug) Some(Output(UInt(7.W))) else None
    val mem_result_out = if(Debug) Some(Output(UInt(cfg.xlen.W))) else None




  })

  // ── Pipeline 模块（不变）──────────────────────────────────────────────────
  val Metronome_inst      = Module(new Metronome(Width = cfg.xlen))
  val PC_Ctrl_inst        = Module(new PC_Ctrl(Width = cfg.xlen))
  val Decoder_inst        = Module(new Decoder())
  val OpcodeCtrlTop_inst  = Module(new OpcodeCtrlTop(cfg = cfg))
  val ImmGenerator_inst   = Module(new ImmGenerator(Width = cfg.xlen))
  val RegisterFile_inst   = Module(new RegisterFile(Width = cfg.xlen, Debug = Debug))
  val ALU_Top_inst        = Module(new ALU_Top(cfg = cfg))
  val ALU_Ctrl_Top_inst   = Module(new ALU_Ctrl_Top(cfg = cfg))
  val mux8_3_inst         = Module(new Mux8_3(Width = cfg.xlen))
  val and_gate_inst       = Module(new AndGate(Width = 3))
  val mux2_1_WriteBack    = Module(new Mux2_1(Width = cfg.xlen))
  val mux2_1_Rs2          = Module(new Mux2_1(Width = cfg.xlen))
  val adder2_1_forJALR    = Module(new Adder(Width = cfg.xlen))
  val adder2_1_ImmBranch  = Module(new Adder(Width = cfg.xlen))
  val PC_Align_inst       = Module(new PC_Align(Width = cfg.xlen))
  val Priv_Exec_inst      = Module(new Priv_Exec(cfg = cfg))
  val CSRs_inst           = Module(new CSRs(cfg = cfg))

  // ── AXI4-Lite 基础设施（替代 InsBuffer + DataMemory）─────────────────────
  val IFetchAXI_inst      = Module(new IFetchAXIAdapter())
  val LSUAXI_inst         = Module(new LSUAXIAdapter())
  val PMEMSlave_IF_inst   = Module(new AxiLiteDpiRamSlave())
  val Crossbar_inst       = Module(new AxiLiteCrossbar(32, 64, Seq(
    AxiLiteSlaveRange(0x80000000L, 0x10000000L),  // PMEM: 256MB
    AxiLiteSlaveRange(0xA0000000L, 0x02000000L)   // MMIO: 32MB
  )))
  val PMEMSlave_Data_inst = Module(new AxiLiteDpiRamSlave())
  val MMIOSlave_inst      = Module(new AxiLiteDpiMmioSlave())

  // ── Stall：任一 AXI master 忙则冻结流水线 ──────────────────────────────
  Metronome_inst.io.stuck := IFetchAXI_inst.io.busy || LSUAXI_inst.io.busy

  // ── PC 控制 ────────────────────────────────────────────────────────────
  PC_Ctrl_inst.io.next_pc     := mux8_3_inst.io.out
  PC_Ctrl_inst.io.pc_write_en := Metronome_inst.io.tick_pc

  // ── 取指 via AXI ──────────────────────────────────────────────────────
  IFetchAXI_inst.io.pc  := PC_Ctrl_inst.io.pc_out
  IFetchAXI_inst.io.axi <> PMEMSlave_IF_inst.io.axi

  // ── 译码 ──────────────────────────────────────────────────────────────
  Decoder_inst.io.instruction := IFetchAXI_inst.io.inst
  Decoder_inst.io.busy        := IFetchAXI_inst.io.busy
  Decoder_inst.io.tick_ifid   := Metronome_inst.io.tick_ifid

  // ── Opcode 控制 ───────────────────────────────────────────────────────
  OpcodeCtrlTop_inst.io.opcode := Decoder_inst.io.opcode
  OpcodeCtrlTop_inst.io.funct7 := Decoder_inst.io.funct7
  OpcodeCtrlTop_inst.io.funct3 := Decoder_inst.io.funct3
  OpcodeCtrlTop_inst.io.rs2    := Decoder_inst.io.rs2

  val privSel = OpcodeCtrlTop_inst.io.privSel

  // ── 特权/CSR 路径 ─────────────────────────────────────────────────────
  Priv_Exec_inst.io.csrEn     := OpcodeCtrlTop_inst.io.csrEn
  Priv_Exec_inst.io.csrOp     := OpcodeCtrlTop_inst.io.csrOp
  Priv_Exec_inst.io.csrImm    := OpcodeCtrlTop_inst.io.csrImm
  Priv_Exec_inst.io.trapEn    := OpcodeCtrlTop_inst.io.trapEn
  Priv_Exec_inst.io.mretEn    := OpcodeCtrlTop_inst.io.mretEn
  Priv_Exec_inst.io.tick_idex := Metronome_inst.io.tick_idex
  Priv_Exec_inst.io.rs1_data  := RegisterFile_inst.io.rs1_dout
  Priv_Exec_inst.io.zimm      := Decoder_inst.io.rs1
  Priv_Exec_inst.io.csr_addr  := Decoder_inst.io.csr_addr
  Priv_Exec_inst.io.pc        := PC_Ctrl_inst.io.pc_out
  Priv_Exec_inst.io.old_csr   := CSRs_inst.io.rdata

  CSRs_inst.io.addr       := Decoder_inst.io.csr_addr
  CSRs_inst.io.wdata      := Priv_Exec_inst.io.csr_wdata
  CSRs_inst.io.we         := Priv_Exec_inst.io.csr_we    && Metronome_inst.io.tick_memwb
  CSRs_inst.io.allow      := Priv_Exec_inst.io.csr_allow
  CSRs_inst.io.trap_en    := Priv_Exec_inst.io.trap_en   && Metronome_inst.io.tick_memwb
  CSRs_inst.io.trap_cause := Priv_Exec_inst.io.trap_cause
  CSRs_inst.io.trap_epc   := Priv_Exec_inst.io.trap_epc
  CSRs_inst.io.mret_en    := Priv_Exec_inst.io.mret_en   && Metronome_inst.io.tick_memwb

  // ── 寄存器堆 ──────────────────────────────────────────────────────────
  RegisterFile_inst.io.rs1        := Decoder_inst.io.rs1
  RegisterFile_inst.io.rs2        := Decoder_inst.io.rs2
  RegisterFile_inst.io.write_reg  := Decoder_inst.io.rd
  RegisterFile_inst.io.rd_write_din := Mux(OpcodeCtrlTop_inst.io.csrRegWrite,
                                           Priv_Exec_inst.io.rd_wdata,
                                           mux2_1_WriteBack.io.out)
  RegisterFile_inst.io.rd_write_en  := OpcodeCtrlTop_inst.io.regWrite
  RegisterFile_inst.io.tick_memwb   := Metronome_inst.io.tick_memwb

  // ── 立即数生成 ────────────────────────────────────────────────────────
  ImmGenerator_inst.io.instruction := IFetchAXI_inst.io.inst

  // ── JALR 目标（tick_idex 时锁存 rs1，防止 rd==rs1 冲突）──────────────
  val rs1_latch = RegInit(0.U(cfg.xlen.W))
  when(Metronome_inst.io.tick_idex) {
    rs1_latch := RegisterFile_inst.io.rs1_dout
  }
  adder2_1_forJALR.io.a := rs1_latch
  adder2_1_forJALR.io.b := ImmGenerator_inst.io.imm_out

  // ── ALU 源操作数选择 ──────────────────────────────────────────────────
  mux2_1_Rs2.io.sel := OpcodeCtrlTop_inst.io.aluSrc
  mux2_1_Rs2.io.in0 := RegisterFile_inst.io.rs2_dout
  mux2_1_Rs2.io.in1 := ImmGenerator_inst.io.imm_out

  // ── 分支目标 ──────────────────────────────────────────────────────────
  adder2_1_ImmBranch.io.a := PC_Ctrl_inst.io.pc_out
  adder2_1_ImmBranch.io.b := ImmGenerator_inst.io.imm_out

  // ── PC 对齐（JALR）──────────────────────────────────────────────────
  PC_Align_inst.io.pc_in := adder2_1_forJALR.io.sum

  // ── ALU ────────────────────────────────────────────────────────────────
  ALU_Top_inst.io.a_in        := RegisterFile_inst.io.rs1_dout
  ALU_Top_inst.io.b_in        := mux2_1_Rs2.io.out
  ALU_Top_inst.io.alu_ctrl_in := ALU_Ctrl_Top_inst.io.alu_ctrl
  ALU_Top_inst.io.tick_idex   := Metronome_inst.io.tick_idex
  ALU_Top_inst.io.pc          := PC_Ctrl_inst.io.pc_out
  ALU_Top_inst.io.extSel      := OpcodeCtrlTop_inst.io.extSel

  ALU_Ctrl_Top_inst.io.aluop  := OpcodeCtrlTop_inst.io.aluop
  ALU_Ctrl_Top_inst.io.funct3 := Decoder_inst.io.funct3
  ALU_Ctrl_Top_inst.io.extSel := OpcodeCtrlTop_inst.io.extSel

  // ── 分支判定 ──────────────────────────────────────────────────────────
  and_gate_inst.io.a := OpcodeCtrlTop_inst.io.branch
  and_gate_inst.io.b := ALU_Top_inst.io.branch_taken

  // ── Load/Store via AXI ────────────────────────────────────────────────
  val lsuStart = Metronome_inst.io.tick_device &&
                 (OpcodeCtrlTop_inst.io.memRead || OpcodeCtrlTop_inst.io.memWrite) &&
                 !privSel

  LSUAXI_inst.io.start      := lsuStart
  LSUAXI_inst.io.addr       := ALU_Top_inst.io.alu_result
  LSUAXI_inst.io.wdata      := RegisterFile_inst.io.rs2_dout
  LSUAXI_inst.io.accessType := Decoder_inst.io.funct3
  LSUAXI_inst.io.memRead    := OpcodeCtrlTop_inst.io.memRead  && !privSel
  LSUAXI_inst.io.memWrite   := OpcodeCtrlTop_inst.io.memWrite && !privSel

  // AXI 数据路径：LSU → Crossbar → PMEM slave + MMIO slave
  LSUAXI_inst.io.axi          <> Crossbar_inst.io.master
  Crossbar_inst.io.slaves(0)  <> PMEMSlave_Data_inst.io.axi
  Crossbar_inst.io.slaves(1)  <> MMIOSlave_inst.io.axi

  // ── Branch/Jump/Priv PC 控制 ──────────────────────────────────────────
  mux8_3_inst.io.sel := Mux(Priv_Exec_inst.io.trap_en,                                    3.U,
                        Mux(Priv_Exec_inst.io.mret_en,                                    4.U,
                        Mux(OpcodeCtrlTop_inst.io.branch && !privSel, ALU_Top_inst.io.branch_taken, 0.U)))
  mux8_3_inst.io.in0 := PC_Ctrl_inst.io.pc_plus4
  mux8_3_inst.io.in1 := adder2_1_ImmBranch.io.sum
  mux8_3_inst.io.in2 := adder2_1_forJALR.io.sum
  mux8_3_inst.io.in3 := CSRs_inst.io.mtvec_out
  mux8_3_inst.io.in4 := CSRs_inst.io.mepc_out
  mux8_3_inst.io.in5 := 0.U
  mux8_3_inst.io.in6 := 0.U
  mux8_3_inst.io.in7 := 0.U

  // ── Write-back mux ────────────────────────────────────────────────────
  mux2_1_WriteBack.io.sel := OpcodeCtrlTop_inst.io.memtoReg
  mux2_1_WriteBack.io.in0 := ALU_Top_inst.io.alu_result
  mux2_1_WriteBack.io.in1 := LSUAXI_inst.io.rdata

  // ── Debug 端口 ─────────────────────────────────────────────────────────
  if (Debug) {
    io.regs_debug.get        := RegisterFile_inst.io.regs_out.get
    io.tick_pc_debug.get     := Metronome_inst.io.tick_pc
    io.tick_ifid_debug.get   := Metronome_inst.io.tick_ifid
    io.tick_idex_debug.get   := Metronome_inst.io.tick_idex
    io.tick_exmem_debug.get  := Metronome_inst.io.tick_exmem
    io.tick_memwb_debug.get  := Metronome_inst.io.tick_memwb
    io.pc.get                := PC_Ctrl_inst.io.pc_out
    io.index.get             := 0.U
    io.access_cnt.get        := 0.U
    io.waitcycles.get        := 0.U
    io.busy.get              := IFetchAXI_inst.io.busy || LSUAXI_inst.io.busy
    io.instruction.get       := IFetchAXI_inst.io.inst
    io.imm.get               := ImmGenerator_inst.io.imm_out
    io.addr_low.get          := 0.U
    io.addr_high.get         := 0.U
    io.pc_base.get           := 0.U
    io.ins_high.get          := 0.U
    io.ins_low.get           := 0.U
    io.regs_out.get          := VecInit(Seq.fill(128)(0.U(cfg.xlen.W)))
    io.opcode_out.get        := Decoder_inst.io.opcode
    io.alu_result_out.get    := ALU_Top_inst.io.alu_result
    io.func3_out.get         := Decoder_inst.io.funct3
    io.func7_out.get         := Decoder_inst.io.funct7
    io.mem_result_out.get    := LSUAXI_inst.io.rdata
  }

}
