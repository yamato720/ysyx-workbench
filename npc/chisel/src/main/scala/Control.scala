package scpu

import chisel3._
import chisel3.util._




//
//class ALU_Ctrl(Width:Int = 32) extends Module{
//  val io = IO(new Bundle() {
//    val aluop = Input(UInt(4.W))
//    val funct7 = Input(UInt(7.W))
//    val funct3 = Input(UInt(3.W))
//    val alu_ctrl = Output(UInt(5.W))
//  })
//
//  when (io.aluop === "b0000".U){
//    // aluop = 0000: load/store ADD
//    io.alu_ctrl := "b00000".U
//  }.elsewhen(io.aluop === "b0001".U){
//    // aluop = 0001: branch
//    when (io.funct3 === "b000".U){
//      io.alu_ctrl := "b01010".U  // BEQ
//    }.elsewhen(io.funct3 === "b001".U){
//      io.alu_ctrl := "b01011".U  // BNE
//    }.elsewhen(io.funct3 === "b100".U){
//      io.alu_ctrl := "b01100".U  // BLT
//    }.elsewhen(io.funct3 === "b101".U){
//      io.alu_ctrl := "b01101".U  // BGE
//    }.elsewhen(io.funct3 === "b110".U){
//      io.alu_ctrl := "b01110".U  // BLTU
//    }.elsewhen(io.funct3 === "b111".U){
//      io.alu_ctrl := "b01111".U  // BGEU
//    }.otherwise{
//      io.alu_ctrl := "b00000".U
//    }
//  }.elsewhen(io.aluop === "b0010".U) {
//    // aluop = 0010: R-type 32
//    when(Cat(io.funct7(5), io.funct3) === "b0000".U) {
//      io.alu_ctrl := "b00000".U // ADD
//    }.elsewhen(Cat(io.funct7(5), io.funct3) === "b1000".U){
//      io.alu_ctrl := "b00001".U // SUB
//    }.elsewhen(Cat(io.funct7(5), io.funct3) === "b0001".U){
//      io.alu_ctrl := "b00101".U // SLL
//    }.elsewhen(Cat(io.funct7(5), io.funct3) === "b0010".U){
//      io.alu_ctrl := "b01000".U // SLT
//    }.elsewhen(Cat(io.funct7(5), io.funct3) === "b0011".U){
//      io.alu_ctrl := "b01001".U // SLTU
//    }.elsewhen(Cat(io.funct7(5), io.funct3) === "b0100".U){
//      io.alu_ctrl := "b00100".U // XOR
//    }.elsewhen(Cat(io.funct7(5), io.funct3) === "b0101".U) {
//      io.alu_ctrl := "b00110".U // SRL
//    }.elsewhen(Cat(io.funct7(5), io.funct3) === "b1101".U) {
//      io.alu_ctrl := "b00111".U // SRA
//    }.elsewhen(Cat(io.funct7(5), io.funct3) === "b0110".U){
//      io.alu_ctrl := "b00011".U // OR
//    }.elsewhen(Cat(io.funct7(5), io.funct3) === "b0111".U){
//      io.alu_ctrl := "b00010".U // AND
//    }.otherwise{
//      io.alu_ctrl := "b00000".U
//    }
//  }.elsewhen(io.aluop === "b0011".U){
//    // aluop = 0011: I-type ALU operations 32
//    when (io.funct3 === "b000".U){
//      io.alu_ctrl := "b00000".U  // ADDI
//    }.elsewhen(io.funct3 === "b010".U){
//      io.alu_ctrl := "b01000".U  // SLTI
//    }.elsewhen(io.funct3 === "b011".U){
//      io.alu_ctrl := "b01001".U  // SLTIU
//    }.elsewhen(io.funct3 === "b100".U){
//      io.alu_ctrl := "b00100".U  // XORI
//    }.elsewhen(io.funct3 === "b110".U){
//      io.alu_ctrl := "b00011".U  // ORI
//    }.elsewhen(io.funct3 === "b111".U){
//      io.alu_ctrl := "b00010".U  // ANDI
//    }.elsewhen(io.funct3 === "b001".U){
//      io.alu_ctrl := "b00101".U  // SLLI
//    }.elsewhen(io.funct3 === "b101".U){
//      when (io.funct7(5) === "b0".U){
//        io.alu_ctrl := "b00110".U  // SRLI
//      }.otherwise{
//        io.alu_ctrl := "b00111".U  // SRAI
//      }
//    }.otherwise{
//      io.alu_ctrl := "b00000".U
//    }
//  }.elsewhen(io.aluop === "b0100".U){
//    // aluop = 0100: LUI
//    io.alu_ctrl := "b10001".U
//  }.elsewhen(io.aluop === "b0101".U){
//    // aluop = 0101: AUIPC
//    io.alu_ctrl := "b10000".U
//  }.elsewhen(io.aluop === "b0110".U){
//    // aluop = 0110: JALR
//    io.alu_ctrl := "b10011".U
//  }.elsewhen(io.aluop === "b0111".U){
//    // aluop = 0111: JAL
//    io.alu_ctrl := "b10010".U
//  }.elsewhen(io.aluop === "b1000".U) {
//    // aluop = 1000: R-type 64 (Word operations)
//    when(Cat(io.funct7(5), io.funct3) === "b0000".U) {
//      io.alu_ctrl := "b10100".U // ADDW
//    }.elsewhen(Cat(io.funct7(5), io.funct3) === "b1000".U){
//      io.alu_ctrl := "b10101".U // SUBW
//    }.elsewhen(Cat(io.funct7(5), io.funct3) === "b0001".U) {
//      io.alu_ctrl := "b10110".U // SLLW
//    }.elsewhen(Cat(io.funct7(5), io.funct3) === "b0101".U) {
//      io.alu_ctrl := "b10111".U // SRLW
//    }.elsewhen(Cat(io.funct7(5), io.funct3) === "b1101".U) {
//      io.alu_ctrl := "b11000".U // SRAW
//    }.otherwise{
//      io.alu_ctrl := "b00000".U
//    }
//  }.elsewhen(io.aluop === "b1001".U){
//    // aluop = 1001: I-type ALU operations 64 (Word immediate operations)
//    when (io.funct3 === "b000".U){
//      io.alu_ctrl := "b10100".U  // ADDIW
//    }.elsewhen(io.funct3 === "b001".U){
//      io.alu_ctrl := "b10110".U  // SLLIW
//    }.elsewhen(io.funct3 === "b101".U){
//      when (io.funct7(5) === "b0".U){
//        io.alu_ctrl := "b10111".U  // SRLIW
//      }.otherwise{
//        io.alu_ctrl := "b11000".U  // SRAIW
//      }
//    }.otherwise{
//      io.alu_ctrl := "b00000".U
//    }
//  }.otherwise{
//    io.alu_ctrl := "b00000".U
//  }
//}

class ALU_Ctrl_Top(Width:Int=32, M_Extension:Boolean = false) extends Module{
  val Sel_num = if (M_Extension) 2 else 1
  val io = IO(new Bundle() {
    val aluop = Input(UInt(4.W))
    val funct3 = Input(UInt(3.W))
    val extSel = Input(UInt(Sel_num.W))
    val alu_ctrl = Output(UInt(5.W))
  })

  val i_alu_ctrl_out = WireDefault(0.U(5.W))
  val m_alu_ctrl_out = WireDefault(0.U(5.W))
  val i_sel = WireDefault(1.U)
  val m_sel = WireDefault(0.U)

  val alu_ctrl_i = Module(new ALU_Ctrl_I(Width))

  alu_ctrl_i.io.aluop := io.aluop
  alu_ctrl_i.io.funct3 := io.funct3
  i_alu_ctrl_out := alu_ctrl_i.io.alu_ctrl
  i_sel := io.extSel(0)

  if(M_Extension){
    val alu_ctrl_m = Module(new ALU_Ctrl_M(Width))

    alu_ctrl_m.io.aluop := io.aluop
    alu_ctrl_m.io.funct3 := io.funct3

    m_alu_ctrl_out := alu_ctrl_m.io.alu_ctrl
    m_sel := io.extSel(1)
  }


  when(i_sel === 1.U){
    io.alu_ctrl := i_alu_ctrl_out
  }.elsewhen(m_sel === 1.U){
    io.alu_ctrl := m_alu_ctrl_out
  }.otherwise{
    io.alu_ctrl := 0.U
  }
}

class ALU_Ctrl_M(Width:Int = 32) extends Module{
  val io = IO(new Bundle() {
    val aluop = Input(UInt(4.W))
    val funct3 = Input(UInt(3.W))
    val alu_ctrl = Output(UInt(5.W))
  })
  when(io.aluop === "b1010".U){ // M-extension MUL/DIV/REM
    when (io.funct3 === "b000".U){
      io.alu_ctrl := "b00000".U  // MUL
    }.elsewhen(io.funct3 === "b001".U){
      io.alu_ctrl := "b00001".U  // MULH
    }.elsewhen(io.funct3 === "b010".U){
      io.alu_ctrl := "b00010".U  // MULHSU
    }.elsewhen(io.funct3 === "b011".U){
      io.alu_ctrl := "b00011".U  // MULHU
    }.elsewhen(io.funct3 === "b100".U){
      io.alu_ctrl := "b00100".U  // DIV
    }.elsewhen(io.funct3 === "b101".U){
      io.alu_ctrl := "b00101".U  // DIVU
    }.elsewhen(io.funct3 === "b110".U){
      io.alu_ctrl := "b00110".U  // REM
    }.elsewhen(io.funct3 === "b111".U){
      io.alu_ctrl := "b00111".U  // REMU (reusing an unused code)
    }.otherwise{
      io.alu_ctrl := "b00000".U
    }
  }.otherwise{
    if(Width == 64){
      when(io.aluop === "b1011".U){ // M-extension MULW/DIVW/REMW
        when (io.funct3 === "b000".U){
          io.alu_ctrl := "b01000".U  // MULW
        }.elsewhen(io.funct3 === "b100".U){
          io.alu_ctrl := "b01001".U  // DIVW
        }.elsewhen(io.funct3 === "b101".U){
          io.alu_ctrl := "b01010".U  // DIVUW
        }.elsewhen(io.funct3 === "b110".U){
          io.alu_ctrl := "b01011".U  // REMW
        }.elsewhen(io.funct3 === "b111".U) {
          io.alu_ctrl := "b01100".U // REMUW
        }.otherwise{
          io.alu_ctrl := "b00000".U
        }
      }.otherwise{
        io.alu_ctrl := "b00000".U
      }
    } else {
      io.alu_ctrl := "b00000".U
    }
  }


}



class ALU_Ctrl_I(Width:Int = 32) extends Module{
  val io = IO(new Bundle() {
    val aluop = Input(UInt(4.W))
    val funct3 = Input(UInt(3.W))
    val alu_ctrl = Output(UInt(5.W))
  })

  val alu_ctrl = WireDefault(UInt(5.W), 0.U)
  io.alu_ctrl := alu_ctrl

  when (io.aluop === "b0000".U){
    // aluop = 0000: load/store ADD
    alu_ctrl := "b00000".U
  }.elsewhen(io.aluop === "b0001".U){
    // aluop = 0011: I-type ALU operations 32
    when (io.funct3 === "b000".U){
      alu_ctrl := "b00000".U  // ADDI
    }.elsewhen(io.funct3 === "b010".U){
      alu_ctrl := "b01000".U  // SLTI
    }.elsewhen(io.funct3 === "b011".U){
      alu_ctrl := "b01001".U  // SLTIU
    }.elsewhen(io.funct3 === "b100".U){
      alu_ctrl := "b00100".U  // XORI
    }.elsewhen(io.funct3 === "b110".U){
      alu_ctrl := "b00011".U  // ORI
    }.elsewhen(io.funct3 === "b111".U){
      alu_ctrl := "b00010".U  // ANDI
    }.elsewhen(io.funct3 === "b001".U){
      alu_ctrl := "b00101".U  // SLLI
    }.elsewhen(io.funct3 === "b101".U){
      alu_ctrl := "b00110".U
    }
  }.elsewhen(io.aluop === "b0010".U){
    alu_ctrl := "b00111".U  // SRAI (funct7区分SRLI/SRAI)
  }.elsewhen(io.aluop === "b0011".U){
    // aluop = 0001: branch
    when (io.funct3 === "b000".U){
      alu_ctrl := "b01010".U  // BEQ
    }.elsewhen(io.funct3 === "b001".U){
      alu_ctrl := "b01011".U  // BNE
    }.elsewhen(io.funct3 === "b100".U){
      alu_ctrl := "b01100".U  // BLT
    }.elsewhen(io.funct3 === "b101".U){
      alu_ctrl := "b01101".U  // BGE
    }.elsewhen(io.funct3 === "b110".U){
      alu_ctrl := "b01110".U  // BLTU
    }.elsewhen(io.funct3 === "b111".U){
      alu_ctrl := "b01111".U  // BGEU
    }
  }.elsewhen(io.aluop === "b0100".U) {
    // aluop = 0010: R-type 32
    when(io.funct3 === "b000".U) {
      alu_ctrl := "b00000".U // ADD
    }.elsewhen(io.funct3 === "b001".U){
      alu_ctrl := "b00101".U // SLL
    }.elsewhen(io.funct3 === "b010".U){
      alu_ctrl := "b01000".U // SLT
    }.elsewhen(io.funct3 === "b011".U){
      alu_ctrl := "b01001".U // SLTU
    }.elsewhen(io.funct3 === "b100".U){
      alu_ctrl := "b00100".U // XOR
    }.elsewhen(io.funct3 === "b101".U) {
      alu_ctrl := "b00110".U // SRL
    }.elsewhen(io.funct3 === "b110".U){
      alu_ctrl := "b00011".U // OR
    }.elsewhen(io.funct3 === "b111".U){
      alu_ctrl := "b00010".U // AND
    }
  }.elsewhen(io.aluop === "b0101".U){
    when(io.funct3 === "b000".U){
      io.alu_ctrl := "b00001".U // SUB (R-type区分ADD/SUB)
    }.elsewhen(io.funct3 === "b101".U){
      io.alu_ctrl := "b00111".U // SRA (R-type区分SRL/SRA)
    }
  }.elsewhen(io.aluop === "b0110".U){
    // aluop = 0111: JAL
    io.alu_ctrl := "b10010".U
  }.elsewhen(io.aluop === "b0111".U){
    // aluop = 0110: JALR
    io.alu_ctrl := "b10011".U
  }.elsewhen(io.aluop === "b1000".U){
    // aluop = 0101: AUIPC
    io.alu_ctrl := "b10000".U
  }.elsewhen(io.aluop === "b1001".U){
    // aluop = 0100: LUI
    io.alu_ctrl := "b10001".U
  }.otherwise{
    if(Width == 64){
      when(io.aluop === "b1100".U) {
        // aluop = 1100: R-type 64 (Word operations) ADDW/SLLW/SRLW
        when(io.funct3 === "b000".U) {
          io.alu_ctrl := "b10100".U // ADDW
        }.elsewhen(io.funct3 === "b001".U) {
          io.alu_ctrl := "b10110".U // SLLW
        }.elsewhen(io.funct3 === "b101".U) {
          io.alu_ctrl := "b10111".U // SRLW
        }
      }.elsewhen(io.aluop === "b1101".U){
        // aluop = 1101: R-type 64 SUBW/SRAW
        when(io.funct3 === "b000".U) {
          io.alu_ctrl := "b10101".U // SUBW
        }.elsewhen(io.funct3 === "b101".U) {
          io.alu_ctrl := "b11000".U // SRAW
        }
      }.elsewhen(io.aluop === "b1010".U) {
        // aluop = 1001: I-type ALU operations 64 (Word immediate operations)
        when(io.funct3 === "b000".U) {
          io.alu_ctrl := "b10100".U // ADDIW
        }.elsewhen(io.funct3 === "b001".U) {
          io.alu_ctrl := "b10110".U // SLLIW
        }.elsewhen(io.funct3 === "b101".U) {
          io.alu_ctrl := "b10111".U // SRLIW
        }
      }.elsewhen(io.aluop === "b1011".U){
        io.alu_ctrl := "b11000".U // SRAIW
      }
    }
    else {
      io.alu_ctrl := "b00000".U
    }
  }
}


class ImmGenerator(Width: Int = 32) extends Module {
  val io = IO(new Bundle {
    val instruction = Input(UInt(32.W))
    val imm_out = Output(UInt(Width.W))
  })
  val imm_out_reg = RegInit(0.U(Width.W))
  io.imm_out := imm_out_reg

  when(reset.asBool){
    io.imm_out := 0.U
  }.otherwise{
    when(io.instruction(6, 0) === "b0010011".U || io.instruction(6, 0) === "b0000011".U || io.instruction(6, 0) === "b1100111".U ||
         (if(Width == 64) io.instruction(6, 0) === "b0011011".U else false.B)){ // I-type (including RV64I ADDIW/SLLIW/etc)
      imm_out_reg := Cat(Fill(Width - 12, io.instruction(31)), io.instruction(31,20))
    }.elsewhen(io.instruction(6, 0) === "b0100011".U){ // S-type
      imm_out_reg := Cat(Fill(Width - 12, io.instruction(31)), io.instruction(31,25), io.instruction(11,7))
    }.elsewhen(io.instruction(6, 0) === "b1100011".U){ // B-type
      imm_out_reg := Cat(Fill(Width - 13, io.instruction(31)), io.instruction(31), io.instruction(7), io.instruction(30,25), io.instruction(11,8), 0.U(1.W))
    }.elsewhen(io.instruction(6, 0) === "b0110111".U || io.instruction(6, 0) === "b0010111".U){ // U-type
      if(Width > 32){
        imm_out_reg := Cat(Fill(Width - 32, io.instruction(31)), io.instruction(31,12), Fill(12, 0.U))
      }else{
        imm_out_reg := Cat(io.instruction(31,12), Fill(12, 0.U))
      }
    }.elsewhen(io.instruction(6, 0) === "b1101111".U){ // J-type
      imm_out_reg := Cat(Fill(Width - 21, io.instruction(31)), io.instruction(31), io.instruction(19,12), io.instruction(20), io.instruction(30,21), 0.U(1.W))
    }
  }
}



class Metronome(Width:Int = 32, Debug:Boolean = false) extends Module {
  val io = IO(new Bundle {
    val stuck = Input(Bool())
    val tick_pc = Output(Bool())
    val tick_ifid = Output(Bool())
    val tick_idex = Output(Bool())
    val tick_exmem = Output(Bool())
    val tick_memwb = Output(Bool())
    val tick_device = Output(Bool())

    val debug_cycleCNT = if (Debug) Some(Output(UInt(5.W))) else None
  })
  /*
  PC: 5周期
  ID: 5周期
  EX: 5周期
  MEM:5/9周期 32-bit/64-bit
  WB: 5周期

  */

  val cycleMax = if (Width == 32) 25 else 33
  val tick_pc_reg = RegInit(false.B)
  val tick_ifid_reg = RegInit(false.B)
  val tick_idex_reg = RegInit(false.B)
  val tick_exmem_reg = RegInit(false.B)
  val tick_memwb_reg = RegInit(false.B)
  val tick_device_reg = RegInit(false.B)
  io.tick_pc := tick_pc_reg
  io.tick_ifid := tick_ifid_reg
  io.tick_idex := tick_idex_reg
  io.tick_exmem := tick_exmem_reg
  io.tick_memwb := tick_memwb_reg
  io.tick_device := tick_device_reg
  val cycleCNT = RegInit(1.U(5.W))
  val pc_flag = RegInit(false.B)
  val pc_flag_latch = RegInit(false.B)
  val ifid_flag = RegInit(false.B)
  val ifid_flag_latch = RegInit(false.B)
  val idex_flag = RegInit(false.B)
  val idex_flag_latch = RegInit(false.B)
  val exmem_flag = RegInit(false.B)
  val exmem_flag_latch = RegInit(false.B)
  val memwb_flag = RegInit(false.B)
  val memwb_flag_latch = RegInit(false.B)

  when(io.stuck =/= true.B){
    pc_flag := (cycleCNT === 0.U)
    pc_flag_latch := pc_flag
    ifid_flag := (cycleCNT === 5.U)
    ifid_flag_latch := ifid_flag
    idex_flag := (cycleCNT === 10.U)
    idex_flag_latch := idex_flag
    exmem_flag := (cycleCNT === (if(Width == 32) 15.U else 19.U))
    exmem_flag_latch := exmem_flag
    memwb_flag := (cycleCNT === (if(Width == 32) 20.U else 28.U))
    memwb_flag_latch := memwb_flag
    cycleCNT := Mux(cycleCNT === (cycleMax - 1).U, 0.U, cycleCNT + 1.U)
  }

  when(reset.asBool){
    tick_pc_reg := false.B
  }.elsewhen(pc_flag_latch === false.B && pc_flag === true.B){
    tick_pc_reg := true.B
  }.otherwise{
    tick_pc_reg := false.B
  }

  when(reset.asBool){
    tick_ifid_reg := false.B
  }.elsewhen(ifid_flag_latch === false.B && ifid_flag === true.B){
    tick_ifid_reg := true.B
  }.otherwise {
    tick_ifid_reg := false.B
  }
  when(reset.asBool){
    tick_idex_reg := false.B
  }.elsewhen(idex_flag_latch === false.B && idex_flag === true.B){
    tick_idex_reg := true.B
  }.otherwise {
    tick_idex_reg := false.B
  }

  val mem_accessCNT = RegInit(0.U(4.W))
  when(reset.asBool){
    tick_exmem_reg := false.B
    mem_accessCNT := 0.U
  }.elsewhen(exmem_flag_latch === false.B && exmem_flag === true.B){
    mem_accessCNT := 0.U
    tick_exmem_reg := true.B
  }.elsewhen(mem_accessCNT === (if(Width == 32) 4.U else 8.U)){
    tick_exmem_reg := false.B
    mem_accessCNT := 0.U
  }.otherwise {
    mem_accessCNT := mem_accessCNT + 1.U
  }

  when(exmem_flag_latch === false.B && exmem_flag === true.B){
    tick_device_reg := true.B
  }.otherwise {
    tick_device_reg := false.B
  }

  when(reset.asBool){
    tick_memwb_reg := false.B
  }.elsewhen(memwb_flag_latch === false.B && memwb_flag === true.B){
    tick_memwb_reg := true.B
  }.otherwise {
    tick_memwb_reg := false.B
  }

}

class PC_Ctrl(Width:Int = 32, initPC: BigInt = BigInt("80000000", 16)) extends Module {
  val io = IO(new Bundle {
    val next_pc = Input(UInt(Width.W))
    val pc_write_en = Input(Bool())
    val pc_out = Output(UInt(Width.W))
    val pc_plus4 = Output(UInt(Width.W))
  })

  val pc_current = RegInit(initPC.U(Width.W))
  io.pc_out := pc_current
  io.pc_plus4 := pc_current + 4.U

  when(io.pc_write_en){
    pc_current := io.next_pc
  }.elsewhen(io.pc_write_en === true.B){
    pc_current := io.next_pc
  }

}

class OpcodeCtrlTop(Width: Int = 32, M_Extension: Boolean = false) extends Module {
  val Sel_num = if (M_Extension) 2 else 1
  val io = IO(new Bundle {
    val opcode   = Input(UInt(7.W))
    val funct7   = Input(UInt(7.W))
    val funct3   = Input(UInt(3.W))
    val branch   = Output(Bool())
    val memRead  = Output(Bool())
    val memtoReg = Output(Bool())
    val aluop    = Output(UInt(4.W))
    val memWrite = Output(Bool())
    val aluSrc   = Output(Bool())
    val regWrite = Output(Bool())
    // bit[0]=standard hit, bit[1]=M-ext hit (only valid when M_Extension=true)
    val extSel   = Output(UInt(Sel_num.W))
  })

  // ── Standard decoder ─────────────────────────────────────────────────
  val std = Module(new OpcodeCtrl_I(Width))
  std.io.opcode := io.opcode
  std.io.funct7 := io.funct7
  std.io.funct3 := io.funct3
  io.branch   := std.io.branch
  io.memRead  := std.io.memRead
  io.memtoReg := std.io.memtoReg
  io.memWrite := std.io.memWrite
  io.aluSrc   := std.io.aluSrc

  val std_sel = WireDefault(false.B)
  std_sel := std.io.sel

  // ── M-Extension decoder ───────────────────────────────────────────────
  val mSel   = WireDefault(false.B)
  val mAluop = WireDefault(0.U(4.W))

  if (M_Extension) {
    val mext = Module(new OpcodeCtrl_M(Width))
    mext.io.opcode := io.opcode
    mext.io.funct7 := io.funct7
    mSel   := mext.io.sel
    mAluop := mext.io.aluop
  }

  // M-extension instructions always write to a register (R-type)
  io.regWrite := std.io.regWrite || mSel

  // ── aluop mux + extSel ───────────────────────────────────────────────
  if (M_Extension) {
    io.extSel := Cat(mSel, std.io.sel)
  } else {
    io.extSel := std.io.sel.asUInt
  }

  when(std_sel){
    io.aluop := std.io.aluop
  }.elsewhen(mSel){
    io.aluop := mAluop
  }.otherwise{
    io.aluop := 0.U
  }
}


// aluop encoding:
//   0000: load/store  0001: branch    0010: R-type 32  0011: I-type ALU 32
//   0100: LUI         0101: AUIPC     0110: JALR        0111: JAL
//   1000: R-type 64   1001: I-type ALU 64
//class OpcodeCtrl_I(Width: Int = 32) extends Module {
//  val io = IO(new Bundle {
//    val opcode   = Input(UInt(7.W))
//    val funct7   = Input(UInt(7.W))
//    val branch   = Output(Bool())
//    val memRead  = Output(Bool())
//    val memtoReg = Output(Bool())
//    val aluop    = Output(UInt(4.W))
//    val memWrite = Output(Bool())
//    val aluSrc   = Output(Bool())
//    val regWrite = Output(Bool())
//    val sel      = Output(Bool())
//  })
//
//  // defaults: all false / 0
//  val branch   = WireDefault(false.B)
//  val memRead  = WireDefault(false.B)
//  val memtoReg = WireDefault(false.B)
//  val memWrite = WireDefault(false.B)
//  val aluSrc   = WireDefault(false.B)
//  val regWrite = WireDefault(false.B)
//  val aluop    = WireDefault(0.U(4.W))
//  val sel      = WireDefault(false.B)
//
//  when(io.opcode === "b0000011".U) {        // Load
//    memRead := true.B; memtoReg := true.B; aluSrc := true.B; regWrite := true.B
//    aluop := "b0000".U; sel := true.B
//  }.elsewhen(io.opcode === "b0100011".U) {  // Store
//    memWrite := true.B; aluSrc := true.B
//    aluop := "b0000".U; sel := true.B
//  }.elsewhen(io.opcode === "b1100011".U) {  // Branch
//    branch := true.B
//    aluop := "b0001".U; sel := true.B
//  }.elsewhen(io.opcode === "b0110011".U) {  // R-type 32
//    regWrite := true.B
//    aluop := "b0010".U; sel := true.B
//  }.elsewhen(io.opcode === "b0010011".U) {  // I-type ALU 32
//    aluSrc := true.B; regWrite := true.B
//    aluop := "b0011".U; sel := true.B
//  }.elsewhen(io.opcode === "b0110111".U) {  // LUI
//    aluSrc := true.B; regWrite := true.B
//    aluop := "b0100".U; sel := true.B
//  }.elsewhen(io.opcode === "b0010111".U) {  // AUIPC
//    aluSrc := true.B; regWrite := true.B
//    aluop := "b0101".U; sel := true.B
//  }.elsewhen(io.opcode === "b1100111".U) {  // JALR
//    branch := true.B; regWrite := true.B
//    aluop := "b0110".U; sel := true.B
//  }.elsewhen(io.opcode === "b1101111".U) {  // JAL
//    branch := true.B; regWrite := true.B
//    aluop := "b0111".U; sel := true.B
//  }.otherwise {
//    if (Width == 64) {
//      when(io.opcode === "b0111011".U) {      // R-type 64
//        regWrite := true.B
//        aluop := "b1000".U; sel := true.B
//      }.elsewhen(io.opcode === "b0011011".U) { // I-type ALU 64
//        aluSrc := true.B; regWrite := true.B
//        aluop := "b1001".U; sel := true.B
//      }
//    }
//  }
//
//  io.branch   := branch
//  io.memRead  := memRead
//  io.memtoReg := memtoReg
//  io.memWrite := memWrite
//  io.aluSrc   := aluSrc
//  io.regWrite := regWrite
//  io.aluop    := aluop
//  io.sel      := sel
//}


class OpcodeCtrl_I(Width: Int = 32) extends Module {
  val io = IO(new Bundle {
    val opcode   = Input(UInt(7.W))
    val funct7   = Input(UInt(7.W))
    val funct3   = Input(UInt(3.W))
    val branch   = Output(Bool())
    val memRead  = Output(Bool())
    val memtoReg = Output(Bool())
    val aluop    = Output(UInt(4.W))
    val memWrite = Output(Bool())
    val aluSrc   = Output(Bool())
    val regWrite = Output(Bool())
    val sel      = Output(Bool())
  })

  // defaults: all false / 0
  val branch   = WireDefault(false.B)
  val memRead  = WireDefault(false.B)
  val memtoReg = WireDefault(false.B)
  val memWrite = WireDefault(false.B)
  val aluSrc   = WireDefault(false.B)
  val regWrite = WireDefault(false.B)
  val aluop    = WireDefault(0.U(4.W))
  val sel      = WireDefault(false.B)

  // For R-type: use Cat(funct7(5), opcode) to distinguish ADD/SUB, SRL/SRA etc.
  // For I-type: only use opcode; funct7 is part of immediate, NOT an opcode qualifier.
  //   Exception: SRLI(funct3=101,funct7[5]=0) vs SRAI(funct3=101,funct7[5]=1) inside I-type.
  //   These are handled by separate aluop values, selected in ALU_Ctrl_I.
  val RTypeSel = WireDefault(UInt(8.W), 0.U)
  RTypeSel := Cat(io.funct7(5), io.opcode) // only used for R-type discrimination

  when(io.opcode === "b0000011".U) {        // Load (I-type, all offsets)
    memRead := true.B; memtoReg := true.B; aluSrc := true.B; regWrite := true.B
    aluop := "b0000".U; sel := true.B
  }.elsewhen(io.opcode === "b0100011".U) {  // Store (S-type, all offsets)
    memWrite := true.B; aluSrc := true.B
    aluop := "b0000".U; sel := true.B
  }.elsewhen(io.opcode === "b0010011".U) {  // I-type ALU 32 (ADDI/SLTI/XORI/ORI/ANDI/SLLI/SRLI/SRAI)
    // SRLI vs SRAI distinguished by aluop in ALU_Ctrl_I via funct7(5) there
    aluSrc := true.B; regWrite := true.B
    when(io.funct3 === "b101".U && io.funct7(5) === 1.U) {
      aluop := "b0010".U  // SRAI
    }.otherwise {
      aluop := "b0001".U  // ADDI/SLTI/SLTIU/XORI/ORI/ANDI/SLLI/SRLI
    }
    sel := true.B
  }.elsewhen(io.opcode === "b1100011".U) {  // Branch
    branch := true.B
    aluop := "b0011".U; sel := true.B
  }.elsewhen(io.opcode === "b0110011".U && io.funct7 === "b0000000".U) {  // R-type 32 ADD/SLL/SLT/SLTU/XOR/SRL/OR/AND (not M-ext)
    regWrite := true.B
    aluop := "b0100".U; sel := true.B
  }.elsewhen(io.opcode === "b0110011".U && io.funct7 === "b0100000".U) {  // R-type 32 SUB, SRA (funct7[5]=1)
    regWrite := true.B
    aluop := "b0101".U; sel := true.B
  }.elsewhen(io.opcode === "b1101111".U) {  // JAL
    branch := true.B; regWrite := true.B
    aluop := "b0110".U; sel := true.B
  }.elsewhen(io.opcode === "b1100111".U) {  // JALR (I-type, offset may be negative)
    branch := true.B; regWrite := true.B
    aluop := "b0111".U; sel := true.B
  }.elsewhen(io.opcode === "b0010111".U) {  // AUIPC
    aluSrc := true.B; regWrite := true.B
    aluop := "b1000".U; sel := true.B
  }.elsewhen(io.opcode === "b0110111".U) {  // LUI
    aluSrc := true.B; regWrite := true.B
    aluop := "b1001".U; sel := true.B
  }.otherwise {
    if (Width == 64) {
      when(io.opcode === "b0111011".U && io.funct7 === "b0000000".U) {  // R-type 64 ADDW/SLLW/SRLW (not M-ext)
        regWrite := true.B
        aluop := "b1100".U; sel := true.B
      }.elsewhen(io.opcode === "b0111011".U && io.funct7 === "b0100000".U) {  // R-type 64 SUBW/SRAW (funct7[5]=1)
        regWrite := true.B
        aluop := "b1101".U; sel := true.B
      }.elsewhen(io.opcode === "b0011011".U) {  // I-type ALU 64 (ADDIW/SLLIW/SRLIW/SRAIW)
        aluSrc := true.B; regWrite := true.B
        when(io.funct3 === "b101".U && io.funct7(5) === 1.U) {
          aluop := "b1011".U  // SRAIW
        }.otherwise {
          aluop := "b1010".U  // ADDIW/SLLIW/SRLIW
        }
        sel := true.B
      }
    }
  }

  io.branch   := branch
  io.memRead  := memRead
  io.memtoReg := memtoReg
  io.memWrite := memWrite
  io.aluSrc   := aluSrc
  io.regWrite := regWrite
  io.aluop    := aluop
  io.sel      := sel
}

//
//class OpcodeCtrl_M(Width: Int = 32) extends Module {
//  val io = IO(new Bundle {
//    val opcode = Input(UInt(7.W))
//    val funct7 = Input(UInt(7.W))
//    val aluop  = Output(UInt(4.W))
//    val sel    = Output(Bool())
//  })
//
//  val aluop = WireDefault(0.U(4.W))
//  val sel   = WireDefault(false.B)
//
//  when(io.opcode === "b0110011".U) {        // R-type 32: MUL/DIV/REM
//    aluop := "b1010".U; sel := true.B
//  }
//  if (Width == 64) {
//    when(io.opcode === "b0111011".U) {      // R-type 64: MULW/DIVW/REMW
//      aluop := "b1011".U; sel := true.B
//    }
//  }
//
//  io.aluop := aluop
//  io.sel   := sel
//}


class OpcodeCtrl_M(Width: Int = 32) extends Module {
  val io = IO(new Bundle {
    val opcode = Input(UInt(7.W))
    val funct7 = Input(UInt(7.W))
    val aluop  = Output(UInt(4.W))
    val sel    = Output(Bool())
  })

  val aluop = WireDefault(0.U(4.W))
  val sel   = WireDefault(false.B)



  when(io.opcode === "b0110011".U && io.funct7 === "b0000001".U) {        // R-type 32: MUL/DIV/REM (Cat(funct7[0]=1, opcode=0110011))
    aluop := "b1010".U; sel := true.B
  }
  if (Width == 64) {
    when(io.opcode === "b0111011".U && io.funct7 === "b0000001".U) {      // R-type 64: MULW/DIVW/REMW (Cat(funct7[0]=1, opcode=0111011))
      aluop := "b1011".U; sel := true.B
    }
  }

  io.aluop := aluop
  io.sel   := sel
}





class IO_Distribute(Width: Int = 32) extends Module{

  // ┌─────────────────────────────────────────────────────────────────────────┐
  // │  设备地址空间及访问规格（源自 nemu/src/device/*.c）                       │
  // ├──────────────┬──────────────┬──────────────┬───────────────────────────┤
  // │  设备        │  起始地址     │  结束地址     │  读写规格                  │
  // ├──────────────┼──────────────┼──────────────┼───────────────────────────┤
  // │ physical mem │ 0x80000000   │ 0x8FFFFFFF   │ 字节/半字/字，读写均可      │
  // │ serial       │ 0xA00003F8   │ 0xA00003FF   │ 4字节空间，仅 offset=0，    │
  // │              │              │              │ 1字节写（输出字符到stderr）  │
  // │              │              │              │ 不支持读                    │
  // │ rtc          │ 0xA0000048   │ 0xA000004F   │ 8字节(2×uint32_t)，        │
  // │              │              │              │ offset=0: 低32位(us)，      │
  // │              │              │              │ offset=4: 高32位(us)，      │
  // │              │              │              │ 读 offset=4 时同时刷新两寄存器│
  // │              │              │              │ 每次 4字节读                │
  // │ vgactrl      │ 0xA0000100   │ 0xA0000107   │ 8字节(2×uint32_t)，        │
  // │              │              │              │ offset=0: 屏幕尺寸(高16位宽，│
  // │              │              │              │   低16位高)，4字节只读；     │
  // │              │              │              │ offset=4: sync触发寄存器，   │
  // │              │              │              │   写非零触发屏幕刷新，4字节写 │
  // │ vmem         │ 0xA1000000   │ 0xA10752FF   │ 400×300×4=480000字节，     │
  // │              │              │              │ 每像素 4字节写(ARGB8888)，  │
  // │              │              │              │ 无回调，直接内存映射          │
  // │ keyboard     │ 0xA0000060   │ 0xA0000063   │ 4字节(1×uint32_t)，        │
  // │              │              │              │ 4字节只读，返回键码+按下标志  │
  // │              │              │              │ bit[15]=KEYDOWN，低位=keycode│
  // │ audio        │ 0xA0000200   │ 0xA0000217   │ 6×uint32_t=24字节，        │
  // │              │              │              │ 每寄存器 4字节读写：         │
  // │              │              │              │ +0 freq, +4 channels,      │
  // │              │              │              │ +8 samples, +C sbuf_size,  │
  // │              │              │              │ +10 init, +14 count        │
  // │ audio-sbuf   │ 0xA1200000   │ 0xA120FFFF   │ 64KB字节缓冲，1字节写       │
  // └──────────────┴──────────────┴──────────────┴───────────────────────────┘
  // 从 companion object 引入纯 Scala 常量，在此转换为带 Width 的 Chisel UInt 字面量
  val device_map_start = DeviceMap.startAddrs.map { case (k, v) => k -> v.U(Width.W) }
  val device_map_end   = DeviceMap.endAddrs.map   { case (k, v) => k -> v.U(Width.W) }
  val device_num       = DeviceMap.device_num  // Scala Int，直接用于 .W

  val io = IO(new Bundle{
  
    val alu_result = Input(UInt(Width.W)) // 来自ALU的地址结果

    val enable = Output(UInt(device_num.W)) // 设备使能信号，地址译码输出
  })

  // IO控制逻辑
  val enable_reg = RegInit(0.U(device_num.W))
  io.enable := enable_reg
  when(io.alu_result >= device_map_start("physiscal memory") && io.alu_result <= device_map_end("physiscal memory")){
    enable_reg := "b00000001".U
  }.elsewhen(io.alu_result >= device_map_start("serial") && io.alu_result <= device_map_end("serial")){
    enable_reg := "b00000010".U
  }.elsewhen(io.alu_result >= device_map_start("rtc") && io.alu_result <= device_map_end("rtc")){
    enable_reg := "b00000100".U
  }.elsewhen(io.alu_result >= device_map_start("vgactrl") && io.alu_result <= device_map_end("vgactrl")){
    enable_reg := "b00001000".U
  }.elsewhen(io.alu_result >= device_map_start("vmem") && io.alu_result <= device_map_end("vmem")){
    enable_reg := "b00010000".U
  }.elsewhen(io.alu_result >= device_map_start("keyboard") && io.alu_result <= device_map_end("keyboard")){
    enable_reg := "b00100000".U
  }.elsewhen(io.alu_result >= device_map_start("audio") && io.alu_result <= device_map_end("audio")){
    enable_reg := "b01000000".U
  }.elsewhen(io.alu_result >= device_map_start("audio-sbuf") && io.alu_result <= device_map_end("audio-sbuf")){
    enable_reg := "b10000000".U
  }.otherwise{
    enable_reg := "b00000001".U
  }
  
}





