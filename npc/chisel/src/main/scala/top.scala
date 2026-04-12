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

  val Metronome_inst = Module(new Metronome(Width = cfg.xlen))
  val PC_Ctrl_inst = Module(new PC_Ctrl(Width = cfg.xlen))
  val Decoder_inst = Module(new Decoder())
  val InsBuffer_inst = Module(new InsBuffer(Width = cfg.xlen, BufferSize = 128, Debug = Debug))
  val InsCacheL1_inst = Module(new insCacheL1(useDPI = useDPI, initFile = if (useDPI) None else Some("init_data/program.hex")))
  val OpcodeCtrlTop_inst = Module(new OpcodeCtrlTop(cfg = cfg))
  val ImmGenerator_inst = Module(new ImmGenerator(Width = cfg.xlen))
  val RegisterFile_inst = Module(new RegisterFile(Width = cfg.xlen, Debug = Debug))
  val ALU_Top_inst = Module(new ALU_Top(cfg = cfg))
  val ALU_Ctrl_Top_inst = Module(new ALU_Ctrl_Top(cfg = cfg))
  val DataMemory_inst = Module(new DataMemory(Width = cfg.xlen))
  val IODistribute_inst = Module(new IO_Distribute(Width = cfg.xlen))
  val DataCacheL1_inst = Module(new dataCacheL1(useDPI = useDPI))
  val mux8_3_inst = Module(new Mux8_3(Width = cfg.xlen))  // use for branch target selection
  val and_gate_inst = Module(new AndGate(Width = 3)) // for branch taken decision
  val mux2_1_WriteBack = Module(new Mux2_1(Width = cfg.xlen)) // for alu result and data memory read data selection
  val mux2_1_Rs2 = Module(new Mux2_1(Width = cfg.xlen)) // for rs2 and immediate selection
  val adder2_1_forJALR = Module(new Adder(Width = cfg.xlen)) // for jalr target calculation
  val adder2_1_ImmBranch = Module(new Adder(Width = cfg.xlen)) // for Imm branch calculation
  // B-type 和 J-type 立即数已经在 ImmGenerator 中左移过了，不需要 LeftShifter
  val PC_Align_inst  = Module(new PC_Align(Width = cfg.xlen)) // align pc for instruction fetch
  val Priv_Exec_inst = Module(new Priv_Exec(cfg = cfg))
  val CSRs_inst      = Module(new CSRs(cfg = cfg))

  // InsBuffer busy → stall the super-cycle until instruction is delivered
  Metronome_inst.io.stuck := InsBuffer_inst.io.busy

  PC_Ctrl_inst.io.next_pc := mux8_3_inst.io.out
  PC_Ctrl_inst.io.pc_write_en := Metronome_inst.io.tick_pc

  InsCacheL1_inst.io.addra := InsBuffer_inst.io.addr_low
  InsCacheL1_inst.io.ena := !reset.asBool
  InsCacheL1_inst.io.wea := false.B
  InsCacheL1_inst.io.dina := 0.U
  InsCacheL1_inst.io.addrb := InsBuffer_inst.io.addr_high
  InsCacheL1_inst.io.enb := !reset.asBool
  InsCacheL1_inst.io.web := false.B
  InsCacheL1_inst.io.dinb := 0.U

  InsBuffer_inst.io.pc_in := PC_Ctrl_inst.io.pc_out
  InsBuffer_inst.io.ins_low := InsCacheL1_inst.io.douta
  InsBuffer_inst.io.ins_high := InsCacheL1_inst.io.doutb

  Decoder_inst.io.instruction := InsBuffer_inst.io.ins_out
  Decoder_inst.io.busy := InsBuffer_inst.io.busy
  Decoder_inst.io.tick_ifid := Metronome_inst.io.tick_ifid

  OpcodeCtrlTop_inst.io.opcode := Decoder_inst.io.opcode
  OpcodeCtrlTop_inst.io.funct7 := Decoder_inst.io.funct7
  OpcodeCtrlTop_inst.io.funct3 := Decoder_inst.io.funct3
  OpcodeCtrlTop_inst.io.rs2    := Decoder_inst.io.rs2

  // ── 路径选择：普通指令 vs 特权指令 ─────────────────────────────────────
  // privSel=1: ecall / mret / csr*  → 普通访存路径全部关闭，由特权路径接管
  // privSel=0: 普通指令              → 正常走 ALU + 访存流程
  val privSel = OpcodeCtrlTop_inst.io.privSel

  // ── 特权/CSR 路径 ──────────────────────────────────────────────────────────
  Priv_Exec_inst.io.csrEn     := OpcodeCtrlTop_inst.io.csrEn
  Priv_Exec_inst.io.csrOp     := OpcodeCtrlTop_inst.io.csrOp
  Priv_Exec_inst.io.csrImm    := OpcodeCtrlTop_inst.io.csrImm
  Priv_Exec_inst.io.trapEn    := OpcodeCtrlTop_inst.io.trapEn
  Priv_Exec_inst.io.mretEn    := OpcodeCtrlTop_inst.io.mretEn
  Priv_Exec_inst.io.tick_idex := Metronome_inst.io.tick_idex
  Priv_Exec_inst.io.rs1_data  := RegisterFile_inst.io.rs1_dout
  Priv_Exec_inst.io.zimm      := Decoder_inst.io.rs1        // inst[19:15]
  Priv_Exec_inst.io.csr_addr  := Decoder_inst.io.csr_addr   // inst[31:20]
  Priv_Exec_inst.io.pc        := PC_Ctrl_inst.io.pc_out
  Priv_Exec_inst.io.old_csr   := CSRs_inst.io.rdata         // 组合读出旧值

  // CSRs.addr 用 Decoder 锁存的 inst[31:20]（既用于组合读，也用于写地址判断）
  CSRs_inst.io.addr       := Decoder_inst.io.csr_addr
  CSRs_inst.io.wdata      := Priv_Exec_inst.io.csr_wdata
  CSRs_inst.io.we         := Priv_Exec_inst.io.csr_we    && Metronome_inst.io.tick_memwb
  CSRs_inst.io.allow      := Priv_Exec_inst.io.csr_allow
  CSRs_inst.io.trap_en    := Priv_Exec_inst.io.trap_en   && Metronome_inst.io.tick_memwb
  CSRs_inst.io.trap_cause := Priv_Exec_inst.io.trap_cause
  CSRs_inst.io.trap_epc   := Priv_Exec_inst.io.trap_epc
  CSRs_inst.io.mret_en    := Priv_Exec_inst.io.mret_en   && Metronome_inst.io.tick_memwb

  RegisterFile_inst.io.rs1 := Decoder_inst.io.rs1
  RegisterFile_inst.io.rs2 := Decoder_inst.io.rs2
  RegisterFile_inst.io.write_reg := Decoder_inst.io.rd
  // CSR 读出旧值直接写回 rd（优先于 ALU/mem 结果）
  RegisterFile_inst.io.rd_write_din := Mux(OpcodeCtrlTop_inst.io.csrRegWrite,
                                          Priv_Exec_inst.io.rd_wdata,
                                          mux2_1_WriteBack.io.out)
  RegisterFile_inst.io.rd_write_en := OpcodeCtrlTop_inst.io.regWrite
  RegisterFile_inst.io.tick_memwb := Metronome_inst.io.tick_memwb

  ImmGenerator_inst.io.instruction := InsBuffer_inst.io.ins_out

  // B-type 和 J-type 立即数已经在 ImmGenerator 中左移过了（末尾接 0.U(1.W)）
  // 不需要再次左移

  // JALR target: rs1 must be latched at tick_idex (before tick_memwb writes rd).
  // If rd==rs1 in JALR, tick_memwb updates rs1 before tick_pc of the next
  // super-cycle reads rs1_dout, producing the wrong jump target.
  val rs1_latch = RegInit(0.U(cfg.xlen.W))
  when(Metronome_inst.io.tick_idex) {
    rs1_latch := RegisterFile_inst.io.rs1_dout
  }
  adder2_1_forJALR.io.a := rs1_latch
  adder2_1_forJALR.io.b := ImmGenerator_inst.io.imm_out

  mux2_1_Rs2.io.sel := OpcodeCtrlTop_inst.io.aluSrc
  mux2_1_Rs2.io.in0 := RegisterFile_inst.io.rs2_dout
  mux2_1_Rs2.io.in1 := ImmGenerator_inst.io.imm_out

  adder2_1_ImmBranch.io.a := PC_Ctrl_inst.io.pc_out
  adder2_1_ImmBranch.io.b := ImmGenerator_inst.io.imm_out

  PC_Align_inst.io.pc_in := adder2_1_forJALR.io.sum

  ALU_Top_inst.io.a_in := RegisterFile_inst.io.rs1_dout
  ALU_Top_inst.io.b_in := mux2_1_Rs2.io.out
  ALU_Top_inst.io.alu_ctrl_in := ALU_Ctrl_Top_inst.io.alu_ctrl
  ALU_Top_inst.io.tick_idex := Metronome_inst.io.tick_idex
  ALU_Top_inst.io.pc := PC_Ctrl_inst.io.pc_out
  ALU_Top_inst.io.extSel := OpcodeCtrlTop_inst.io.extSel

  ALU_Ctrl_Top_inst.io.aluop := OpcodeCtrlTop_inst.io.aluop
  ALU_Ctrl_Top_inst.io.funct3 := Decoder_inst.io.funct3
  ALU_Ctrl_Top_inst.io.extSel := OpcodeCtrlTop_inst.io.extSel

  and_gate_inst.io.a := OpcodeCtrlTop_inst.io.branch
  and_gate_inst.io.b := ALU_Top_inst.io.branch_taken

  // ── 普通访存路径（privSel=0 时激活）────────────────────────────────────
  DataMemory_inst.io.mem_write_en := OpcodeCtrlTop_inst.io.memWrite && !privSel
  DataMemory_inst.io.mem_read_en  := OpcodeCtrlTop_inst.io.memRead  && !privSel
  DataMemory_inst.io.addr := ALU_Top_inst.io.alu_result
  DataMemory_inst.io.rs2_dout := RegisterFile_inst.io.rs2_dout
  DataMemory_inst.io.recv_data_a := DataCacheL1_inst.io.douta
  DataMemory_inst.io.recv_data_b := DataCacheL1_inst.io.doutb
  DataMemory_inst.io.access_type := Decoder_inst.io.funct3
  DataMemory_inst.io.tick_exmem := Metronome_inst.io.tick_exmem
  DataMemory_inst.io.tick_device := Metronome_inst.io.tick_device

  IODistribute_inst.io.alu_result := ALU_Top_inst.io.alu_result
  DataMemory_inst.io.selected := IODistribute_inst.io.enable

  DataCacheL1_inst.io.wea := DataMemory_inst.io.wea
  DataCacheL1_inst.io.addra := DataMemory_inst.io.addra
  DataCacheL1_inst.io.dina := DataMemory_inst.io.dina
  DataCacheL1_inst.io.ena := DataMemory_inst.io.ena
  DataCacheL1_inst.io.web := DataMemory_inst.io.web
  DataCacheL1_inst.io.addrb := DataMemory_inst.io.addrb
  DataCacheL1_inst.io.dinb := DataMemory_inst.io.dinb
  DataCacheL1_inst.io.enb := DataMemory_inst.io.enb

  // ── 普通指令 Branch/Jump 控制（privSel=0 时激活）──────────────────────
  // privSel=1 时强制 branch=false，防止 ecall/mret 误走分支路径
  // trap > mret > 普通 branch/jump
  mux8_3_inst.io.sel := Mux(Priv_Exec_inst.io.trap_en,                                    3.U,
                        Mux(Priv_Exec_inst.io.mret_en,                                    4.U,
                        Mux(OpcodeCtrlTop_inst.io.branch && !privSel, ALU_Top_inst.io.branch_taken, 0.U)))
  mux8_3_inst.io.in0 := PC_Ctrl_inst.io.pc_plus4          // sel=0: 顺序 PC+4
  mux8_3_inst.io.in1 := adder2_1_ImmBranch.io.sum         // sel=1: B/J 跳转目标
  mux8_3_inst.io.in2 := adder2_1_forJALR.io.sum           // sel=2: JALR 目标
  // ── 特权路径 PC 重定向槽（待特权模块接入）──────────────────────────────
  mux8_3_inst.io.in3 := CSRs_inst.io.mtvec_out   // ecall → trap handler
  mux8_3_inst.io.in4 := CSRs_inst.io.mepc_out    // mret  → return addr
  mux8_3_inst.io.in5 := 0.U
  mux8_3_inst.io.in6 := 0.U
  mux8_3_inst.io.in7 := 0.U

  mux2_1_WriteBack.io.sel := OpcodeCtrlTop_inst.io.memtoReg
  mux2_1_WriteBack.io.in0 := ALU_Top_inst.io.alu_result
  mux2_1_WriteBack.io.in1 := DataMemory_inst.io.result
 
  // 连接 Debug 端口：输出所有 32 个寄存器的值
  if (Debug) {
    io.regs_debug.get := RegisterFile_inst.io.regs_out.get
    io.tick_pc_debug.get := Metronome_inst.io.tick_pc
    io.tick_ifid_debug.get := Metronome_inst.io.tick_ifid
    io.tick_idex_debug.get := Metronome_inst.io.tick_idex
    io.tick_exmem_debug.get := Metronome_inst.io.tick_exmem
    io.tick_memwb_debug.get := Metronome_inst.io.tick_memwb
    io.pc.get := PC_Ctrl_inst.io.pc_out
    io.index.get := InsBuffer_inst.io.index.get
    io.access_cnt.get := InsBuffer_inst.io.access_cnt.get
    io.waitcycles.get := InsBuffer_inst.io.wait_cycle.get
    io.busy.get := InsBuffer_inst.io.busy
    io.instruction.get := InsBuffer_inst.io.ins_out
    io.imm.get := ImmGenerator_inst.io.imm_out
    io.addr_low.get := InsBuffer_inst.io.addr_low
    io.addr_high.get := InsBuffer_inst.io.addr_high
    io.pc_base.get := InsBuffer_inst.io.pc_base.get
    io.ins_high.get := InsBuffer_inst.io.ins_high
    io.ins_low.get := InsBuffer_inst.io.ins_low
    io.regs_out.get := InsBuffer_inst.io.regs_out.get
    io.opcode_out.get := Decoder_inst.io.opcode
    io.alu_result_out.get := ALU_Top_inst.io.alu_result
    io.func3_out.get := Decoder_inst.io.funct3
    io.func7_out.get := Decoder_inst.io.funct7
    io.mem_result_out.get := DataMemory_inst.io.result
  }

}