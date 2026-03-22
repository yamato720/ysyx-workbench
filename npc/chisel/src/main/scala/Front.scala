package scpu
import chisel3._
import  chisel3.util._

class Front(Width:Int = 32, Debug:Boolean = false) extends Module {
  val io = IO(new Bundle{
    val tick_pc = Input(Bool())
    val ins_in = Input(UInt(32.W))
    val busy = Output(Bool())
  })
  val InsBuffer_inst = Module(new InsBuffer(Width = Width, BufferSize = 10, Debug = Debug))
  val InsCacheL1_inst = Module(new insCacheL1(initFile = Some("init_data/program.hex")))
  val PC_Ctrl_inst = Module(new PC_Ctrl(Width = Width))
  val BranchPredictor_inst = Module(new BranchPredictor(NLP = 1, RAS = 1, TAGE = 1, IP = 1, Debug = Debug))

  BranchPredictor_inst.io.pc_in := io.ins_in

  PC_Ctrl_inst.io.next_pc := BranchPredictor_inst.io.predict_target
  PC_Ctrl_inst.io.pc_write_en := io.tick_pc

  InsCacheL1_inst.io.addra := InsBuffer_inst.io.addr_low
  InsCacheL1_inst.io.ena := true.B
  InsCacheL1_inst.io.wea := false.B
  InsCacheL1_inst.io.dina := 0.U
  InsCacheL1_inst.io.addrb := InsBuffer_inst.io.addr_high
  InsCacheL1_inst.io.enb := true.B
  InsCacheL1_inst.io.web := false.B
  InsCacheL1_inst.io.dinb := 0.U

  InsBuffer_inst.io.pc_in := PC_Ctrl_inst.io.pc_out
  InsBuffer_inst.io.ins_low := InsCacheL1_inst.io.douta
  InsBuffer_inst.io.ins_high := InsCacheL1_inst.io.doutb
}
