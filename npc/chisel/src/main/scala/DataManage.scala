package scpu

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline

class Clock_Generator(num: Int) extends Module {
  val io = IO(new Bundle {
    val clk_out = Output(Vec(num, Clock()))
  })

  // 将输入时钟分发到所有输出引脚
  for (i <- 0 until num) {
    io.clk_out(i) := clock
  }
}

class bram_8_4096_L1Cache extends RawModule {
  val io = IO(new Bundle {
    // 端口 A - 独立时钟
    val clka = Input(Clock())
    val ena  = Input(Bool())
    val wea  = Input(Bool())
    val addra = Input(UInt(12.W))
    val dina  = Input(UInt(8.W))
    val douta = Output(UInt(8.W))

    // 端口 B - 独立时钟
    val clkb = Input(Clock())
    val enb  = Input(Bool())
    val web  = Input(Bool())
    val addrb = Input(UInt(12.W))
    val dinb  = Input(UInt(8.W))
    val doutb = Output(UInt(8.W))
  })

  // 使用 SyncReadMem 实现双端口 BRAM（4096 x 8bit）
  val bram = SyncReadMem(4096, UInt(8.W))

  // 端口 A 的逻辑
  val douta_reg = withClockAndReset(io.clka, false.B) { RegInit(0.U(8.W)) }
  io.douta := douta_reg

  withClock(io.clka) {
    when(io.ena) {
      when(io.wea) {
        bram.write(io.addra, io.dina)
      }
      douta_reg := bram.read(io.addra)
    }
  }

  // 端口 B 的逻辑
  val doutb_reg = withClockAndReset(io.clkb, false.B) { RegInit(0.U(8.W)) }
  io.doutb := doutb_reg

  withClock(io.clkb) {
    when(io.enb) {
      when(io.web) {
        bram.write(io.addrb, io.dinb)
      }
      doutb_reg := bram.read(io.addrb)
    }
  }
}


// BlackBox for instruction cache Verilog module
class bram_8_4096_ins_shell extends BlackBox with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clka = Input(Clock())
    val ena  = Input(Bool())
    val wea  = Input(UInt(1.W))
    val addra = Input(UInt(12.W))
    val dina  = Input(UInt(8.W))
    val douta = Output(UInt(8.W))

    val clkb = Input(Clock())
    val enb  = Input(Bool())
    val web  = Input(UInt(1.W))
    val addrb = Input(UInt(12.W))
    val dinb  = Input(UInt(8.W))
    val doutb = Output(UInt(8.W))
  })
  addResource("/rtl/shell/bram_8_4096_ins_shell.v")
}

// BlackBox for data cache Verilog module
class bram_8_4096_mem_shell extends BlackBox with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clka = Input(Clock())
    val ena  = Input(Bool())
    val wea  = Input(UInt(1.W))
    val addra = Input(UInt(12.W))
    val dina  = Input(UInt(8.W))
    val douta = Output(UInt(8.W))

    val clkb = Input(Clock())
    val enb  = Input(Bool())
    val web  = Input(UInt(1.W))
    val addrb = Input(UInt(12.W))
    val dinb  = Input(UInt(8.W))
    val doutb = Output(UInt(8.W))
  })
  addResource("rtl/shell/bram_8_4096_ins_shell.v")
}

// Instruction cache wrapper using BlackBox
// For synthesis, can switch to BlackBox by changing useBlackBox parameter
// initFile: 可选的初始化文件路径 (相对于 resources 目录)
class insCacheL1(useBlackBox: Boolean = false, useDPI: Boolean = false, initFile: Option[String] = None, Width:Int = 32) extends Module {
  val io = IO(new Bundle {
    val ena  = Input(Bool())
    val wea  = Input(Bool())
    val addra = Input(UInt(Width.W))
    val dina  = Input(UInt(8.W))
    val douta = Output(UInt(8.W))

    val enb  = Input(Bool())
    val web  = Input(Bool())
    val addrb = Input(UInt(Width.W))
    val dinb  = Input(UInt(8.W))
    val doutb = Output(UInt(8.W))
  })

  if (useDPI) {
    // Use DPI-C interface for external memory (Verilator simulation)
    val dpiMem = Module(new DPIMem)
    dpiMem.io.clk := clock
    dpiMem.io.rst := reset.asBool
    
    // Port A
    dpiMem.io.addr_a := io.addra
    dpiMem.io.din_a  := io.dina
    dpiMem.io.we_a   := io.wea
    dpiMem.io.en_a   := io.ena
    io.douta := dpiMem.io.dout_a
    
    // Port B  
    dpiMem.io.addr_b := io.addrb
    dpiMem.io.din_b  := io.dinb
    dpiMem.io.we_b   := io.web
    dpiMem.io.en_b   := io.enb
    io.doutb := dpiMem.io.dout_b
  } else if (useBlackBox) {
    // Use BlackBox for synthesis
    val blackbox = Module(new bram_8_4096_mem_shell)
    blackbox.io.clka := clock
    blackbox.io.ena := io.ena
    blackbox.io.wea := io.wea.asUInt
    blackbox.io.addra := io.addra
    blackbox.io.dina := io.dina
    io.douta := blackbox.io.douta

    blackbox.io.clkb := clock
    blackbox.io.enb := io.enb
    blackbox.io.web := io.web.asUInt
    blackbox.io.addrb := io.addrb
    blackbox.io.dinb := io.dinb
    io.doutb := blackbox.io.doutb
  } else {
    // Use Chisel implementation for simulation/testing
    // 使用 SyncReadMem 实现双端口 BRAM（4096 x 8bit）
    val bram = SyncReadMem(4096, UInt(8.W))

    // 如果提供了初始化文件，则加载数据
    initFile.foreach { file =>
      loadMemoryFromFileInline(bram, file)
    }

    // 端口 A 的逻辑
    // SyncReadMem.read() 已经是同步的，不需要额外的寄存器
    when(io.ena) {
      when(io.wea) {
        bram.write(io.addra, io.dina)
      }
    }
    io.douta := Mux(io.ena, bram.read(io.addra, io.ena), 0.U)

    // 端口 B 的逻辑
    when(io.enb) {
      when(io.web) {
        bram.write(io.addrb, io.dinb)
      }
    }
    io.doutb := Mux(io.enb, bram.read(io.addrb, io.enb), 0.U)
  }
}

// Data cache wrapper using BlackBox
// For synthesis, can switch to BlackBox by changing useBlackBox parameter
// initFile: 可选的初始化文件路径 (相对于 resources 目录)
class dataCacheL1(useBlackBox: Boolean = false, useDPI: Boolean = false, initFile: Option[String] = None) extends Module {
  val io = IO(new Bundle {
    val ena  = Input(Bool())
    val wea  = Input(Bool())
    val addra = Input(UInt(32.W))  // Expanded to 32-bit for DPI-C
    val dina  = Input(UInt(8.W))
    val douta = Output(UInt(8.W))

    val enb  = Input(Bool())
    val web  = Input(Bool())
    val addrb = Input(UInt(32.W))  // Expanded to 32-bit for DPI-C
    val dinb  = Input(UInt(8.W))
    val doutb = Output(UInt(8.W))
  })

  if (useDPI) {
    // Use DPI-C interface for external memory (Verilator simulation)
    val dpiMem = Module(new DPIMem)
    dpiMem.io.clk := clock
    dpiMem.io.rst := reset.asBool
    
    // Port A
    dpiMem.io.addr_a := io.addra
    dpiMem.io.din_a  := io.dina
    dpiMem.io.we_a   := io.wea
    dpiMem.io.en_a   := io.ena
    io.douta := dpiMem.io.dout_a
    
    // Port B  
    dpiMem.io.addr_b := io.addrb
    dpiMem.io.din_b  := io.dinb
    dpiMem.io.we_b   := io.web
    dpiMem.io.en_b   := io.enb
    io.doutb := dpiMem.io.dout_b
  } else if (useBlackBox) {
    // Use BlackBox for synthesis
    val blackbox = Module(new bram_8_4096_mem_shell)
    blackbox.io.clka := clock
    blackbox.io.ena := io.ena
    blackbox.io.wea := io.wea.asUInt
    blackbox.io.addra := io.addra(11, 0)  // Truncate to 12-bit for BRAM
    blackbox.io.dina := io.dina
    io.douta := blackbox.io.douta

    blackbox.io.clkb := clock
    blackbox.io.enb := io.enb
    blackbox.io.web := io.web.asUInt
    blackbox.io.addrb := io.addrb(11, 0)  // Truncate to 12-bit for BRAM
    blackbox.io.dinb := io.dinb
    io.doutb := blackbox.io.doutb
  } else {
    // Use Chisel implementation for simulation/testing
    // 使用 SyncReadMem 实现双端口 BRAM（4096 x 8bit）
    val bram = SyncReadMem(4096, UInt(8.W))

    // 如果提供了初始化文件，则加载数据
    initFile.foreach { file =>
      loadMemoryFromFileInline(bram, file)
    }

    // 端口 A 的逻辑 (truncate to 12-bit for internal BRAM)
    // SyncReadMem.read() 已经是同步的，不需要额外的寄存器
    when(io.ena) {
      when(io.wea) {
        bram.write(io.addra(11, 0), io.dina)
      }
    }
    io.douta := Mux(io.ena, bram.read(io.addra(11, 0), io.ena), 0.U)

    // 端口 B 的逻辑
    when(io.enb) {
      when(io.web) {
        bram.write(io.addrb(11, 0), io.dinb)
      }
    }
    io.doutb := Mux(io.enb, bram.read(io.addrb(11, 0), io.enb), 0.U)
  }
}

class InsBuffer(Width:Int = 32, BufferSize:Int = 128, Debug:Boolean = false, ScalarSize:Int = 1) extends Module{
  // 定义IO Bundle - 始终包含所有端口，Debug模式下暴露额外的调试信号
  val io = IO(new Bundle{
    val pc_in = Input(UInt(Width.W))
    val ins_low = Input(UInt(8.W))
    val ins_high = Input(UInt(8.W))
    val addr_low = Output(UInt(Width.W))
    val addr_high = Output(UInt(Width.W))
    val ins_out = Output(UInt(32.W))
    val valid = Output(Bool())
    val busy = Output(Bool())
    // 调试端口 - 始终存在，但只在Debug模式下使用
    val index = if (Debug) Some(Output(UInt(log2Ceil(BufferSize).W))) else None
    val access_cnt = if (Debug) Some(Output(UInt((log2Ceil(BufferSize)+1).W))) else None
    val wait_cycle = if (Debug) Some(Output(UInt(2.W))) else None
    val pc_base = if (Debug) Some(Output(UInt(Width.W))) else None
    val regs_out = if (Debug) Some(Output(Vec(BufferSize, UInt(Width.W)))) else None
  
  })


  val index_range = log2Ceil(BufferSize + 1).W

  val addr_low = RegInit("h80000000".U(Width.W))
  val addr_high = RegInit("h80000001".U(Width.W))
  val ins_out = RegInit(0.U(32.W))
  val valid = RegInit(false.B)
  val busy = RegInit(true.B)


  io.valid := valid
  io.busy := busy
  io.addr_low := addr_low
  io.addr_high := addr_high
  io.ins_out := ins_out

  // val ins_high = RegInit(0.U(8.W))
  // val ins_low = RegInit(0.U(8.W))


  val ins_reg = RegInit(VecInit(Seq.fill(BufferSize)(0.U(32.W))))
  val finished = RegInit(false.B)
  val pc_base = RegInit("h80000000".U(Width.W))
  val index = RegInit(0.U(Width.W)) // 需要多1位以检测溢出
  val access_cnt = RegInit(0.U(index_range)) // 当前已读取的指令数


  val wait_cycle = RegInit(0.U(2.W))

  // 连接调试端口（如果启用）
  if (Debug) {
    io.access_cnt.get := access_cnt
    io.index.get := index
    io.wait_cycle.get := wait_cycle
    io.pc_base.get := pc_base
    io.regs_out.get := ins_reg
  }

//  val index_res = RegInit(0.U(Width.W))
//  index_res := (io.pc_in - pc_base) >> 2

  index := (io.pc_in-pc_base) >> 2
  val ins_temp = RegInit("h00000013".U(32.W))

    when(index < access_cnt && !busy){
    ins_out := ins_reg(index)
    ins_temp:= ins_reg(index)
  }.otherwise{
    ins_out := ins_temp
    pc_base := io.pc_in
    busy := true.B
    finished := false.B
  }

  val valid_latch = RegInit(false.B)
  valid_latch := valid

  val ins_temp_low16 = RegInit(0.U(16.W))  // 临时保存低16位
  val last_wait_cycle = RegInit(0.U(2.W))
  last_wait_cycle := wait_cycle

  when(!finished){
    when(wait_cycle === 0.U ){
      // 设置第一次读取地址
      addr_low := Mux(!busy, addr_low + 2.U, io.pc_in)
      addr_high := Mux(!busy, addr_high + 2.U, io.pc_in + 1.U)
      access_cnt := Mux(!busy, access_cnt, 0.U)
      wait_cycle := 1.U
    }.elsewhen(wait_cycle === 1.U){
      // 等待1周期，数据在下个周期到达
      wait_cycle := 2.U
    }.elsewhen(wait_cycle === 2.U){
      // 数据到达，保存低16位，并设置第二次读取地址（+2）
      ins_temp_low16 := Cat(io.ins_high, io.ins_low)
      addr_low := addr_low + 2.U
      addr_high := addr_high + 2.U
      wait_cycle := 3.U
    }.elsewhen(wait_cycle === 3.U){
      // 等待1周期，第二次数据在下个周期到达，然后组装
      // 不立即回到0，等待下个周期组装完成
      wait_cycle := 3.U
    }
  }
  
  // 在wait_cycle保持为3且数据已到达时，组装完整指令
  when(wait_cycle === 3.U && last_wait_cycle === 3.U && !finished){
    ins_reg(access_cnt) := Cat(io.ins_high, io.ins_low, ins_temp_low16)
    when(access_cnt === 0.U){
      busy := false.B
    }
    when(access_cnt === (BufferSize - 1).U){
      finished := true.B
    }
    access_cnt := access_cnt + 1.U
    wait_cycle := 0.U  // 组装完成，开始下一条指令
  }
}

class RegisterFile(numRegs:Int = 32, Width:Int = 32, Debug:Boolean = false) extends Module{
  val io = IO(new Bundle{
    val rs1 = Input(UInt(log2Ceil(numRegs).W))
    val rs2 = Input(UInt(log2Ceil(numRegs).W))
    val write_reg = Input(UInt(log2Ceil(numRegs).W))
    val rd_write_en = Input(Bool())
    val rd_write_din = Input(UInt(Width.W))
    val tick_memwb = Input(Bool())
    val rs1_dout = Output(UInt(Width.W))
    val rs2_dout = Output(UInt(Width.W))
    // 调试端口 - 始终存在，但只在Debug模式下使用
    val regs_out = if (Debug) Some(Output(Vec(numRegs, UInt(Width.W)))) else None
  })

  val regs = RegInit(VecInit(Seq.fill(numRegs)(0.U(Width.W))))

  // 读取寄存器
  io.rs1_dout := Mux(io.rs1 === 0.U, 0.U, regs(io.rs1))
  io.rs2_dout := Mux(io.rs2 === 0.U, 0.U, regs(io.rs2))

  regs(0) := 0.U  // x0寄存器始终为0

  // 连接调试端口（如果启用）
  if (Debug) {
    io.regs_out.get := regs
  }

  // 写入寄存器
  when(io.rd_write_en && (io.write_reg =/= 0.U) && io.tick_memwb){
    regs(io.write_reg) := io.rd_write_din
  }


}


class CSRs(cfg: ISAConfig = ISAConfig()) extends Module {
  val io = IO(new Bundle {
    // ── CSR 读/写接口（csrr* 指令）──────────────────
    val addr    = Input(UInt(12.W))   // CSR 地址（imm[11:0]）
    val wdata   = Input(UInt(cfg.xlen.W)) // 待写入值（已由外部运算好）
    val we      = Input(Bool())        // 写使能（tick_memwb节拍控制）
    val allow   = Input(Bool())        // 访问合法（地址有效且指令合法）

    val rdata   = Output(UInt(cfg.xlen.W)) // 读出旧值（写回 rd 用）

    // ── Trap 接口（ecall / 未来异常）─────────────────
    val trap_en    = Input(Bool())
    val trap_cause = Input(UInt(cfg.xlen.W))  // → mcause
    val trap_epc   = Input(UInt(cfg.xlen.W))  // → mepc
    val mtvec_out  = Output(UInt(cfg.xlen.W)) // 跳转目标

    // ── mret 接口 ─────────────────────────────────────
    val mret_en   = Input(Bool())
    val mepc_out  = Output(UInt(cfg.xlen.W))  // 返回地址
  })

  val mstatus = RegInit(0.U(cfg.xlen.W))  // 0x300
  val mtvec   = RegInit(0.U(cfg.xlen.W))  // 0x305
  val mepc    = RegInit(0.U(cfg.xlen.W))  // 0x341
  val mcause  = RegInit(0.U(cfg.xlen.W))  // 0x342

  // 读（组合逻辑，输出旧值供 rd 写回）
  io.rdata := MuxLookup(io.addr, 0.U)(Seq(
    0x300.U -> mstatus,
    0x305.U -> mtvec,
    0x341.U -> mepc,
    0x342.U -> mcause,
  ))

  io.mtvec_out := mtvec
  io.mepc_out  := mepc

  // Trap 优先级最高
  when(io.trap_en) {
    mcause := io.trap_cause
    mepc   := io.trap_epc
  }.elsewhen(io.we && io.allow) {
    switch(io.addr) {
      is(0x300.U) { mstatus := io.wdata }
      is(0x305.U) { mtvec   := io.wdata }
      is(0x341.U) { mepc    := io.wdata }
      is(0x342.U) { mcause  := io.wdata }
    }
  }
}




//class CSRs(numRegs:Int = 32, Width:Int = 32, Debug:Boolean = false) extends Module{
//  val io = IO(new Bundle() {
//
//
//  })
//}

class DataMemory32 extends Module{
  val io = IO(new Bundle{
    val mem_write_en = Input(Bool())
    val mem_read_en = Input(Bool())
    val addr = Input(UInt(32.W))
    val rs2_dout = Input(UInt(32.W))
    val recv_data_a = Input(UInt(8.W))
    val recv_data_b = Input(UInt(8.W))
    val access_type = Input(UInt(3.W))
    /* access_type decoding (funct3)
    000: byte (LB, SB)
    001: halfword (LH, SH)
    010: word (LW, SW)
    011: doubleword (LD, SD)
    100: byte unsigned (LBU)
    101: halfword unsigned (LHU)
    110: word unsigned (LWU)
    */
    val tick_exmem = Input(Bool())
    val result = Output(UInt(32.W))
//    val enab = Output(Bool())
    val ena = Output(Bool())
    val enb = Output(Bool())
    val wea = Output(Bool())
    val web = Output(Bool())
    val addra = Output(UInt(32.W))
    val addrb = Output(UInt(32.W))
    val dina = Output(UInt(8.W))
    val dinb = Output(UInt(8.W))
  })

  val result = RegInit(0.U(32.W))
//  val enab = RegInit(false.B)
  val ena = RegInit(false.B)
  val enb = RegInit(false.B)
  val wea = RegInit(false.B)
  val web = RegInit(false.B)
  val addra = RegInit(0.U(32.W))
  val addrb = RegInit(0.U(32.W))
  val dina = RegInit(0.U(8.W))
  val dinb = RegInit(0.U(8.W))

  io.result := result
//  io.enab := enab
  io.wea := wea
  io.web := web
  io.addra := addra
  io.addrb := addrb
  io.dina := dina
  io.dinb := dinb
  io.ena := ena
  io.enb := enb

//  enab := (io.mem_read_en | io.mem_write_en) & io.tick_exmem

  val state = RegInit(0.U(3.W))
  val wait_state = RegInit(1.U)

  // state: 000 prepare (15, 0)
  //        001 read\write (31, 16)
  //        010 prepare (31, 16)
  //        011 read\write (31, 16)
  //        100 prepare (47, 32)
  //        101 read\write (47, 32)
  //        110 prepare (63, 48)
  //        111 read\write (63, 48)

  when((io.mem_read_en | io.mem_write_en) && io.tick_exmem){
    when(Cat(state, io.access_type)==="b000000".U){ // signed byte
      addra := io.addr
      addrb := io.addr + 1.U
      dina := io.rs2_dout(7,0)
      dinb := io.rs2_dout(15,0)
      wea := io.mem_write_en
      web := false.B
      enb := false.B
      ena := true.B
      when(wait_state === 1.U){
        wait_state := 0.U
      }.otherwise{
        state := state + 1.U
        ena := false.B
        enb := false.B
        wea := false.B
        web := false.B
      }
    }.elsewhen(Cat(state, io.access_type)==="b001000".U){
      result := Cat(Fill(24,io.recv_data_a(7)), io.recv_data_a)
      state := state + 1.U
      addra := io.addr + 2.U
      addrb := io.addr + 3.U
    }.elsewhen(Cat(state, io.access_type)==="b010000".U){
      dina := io.rs2_dout(23,16)
      dinb := io.rs2_dout(31,24)
      state := state + 1.U
    }.elsewhen(Cat(state, io.access_type)==="b011000".U){
//      result := Cat(Fill(16,result(7)), result(15,0))
      state := 0.U
    }.elsewhen(Cat(state, io.access_type)==="b000100".U){  // unsigned byte
      addra := io.addr
      addrb := io.addr + 1.U
      dina := io.rs2_dout(7,0)
      dinb := io.rs2_dout(15,0)
      wea := io.mem_write_en
      web := false.B
      enb := false.B
      ena := true.B
      when(wait_state === 1.U){
        wait_state := 0.U
      }.otherwise{
        state := state + 1.U
        wea := false.B
        web := false.B
        ena := false.B
        enb := false.B
      }
    }.elsewhen(Cat(state, io.access_type)==="b001100".U){
      result := Cat(Fill(24,0.U), io.recv_data_a)
      state := state + 1.U
      addra := io.addr + 2.U
      addrb := io.addr + 3.U
    }.elsewhen(Cat(state, io.access_type)==="b010100".U){
      dina := io.rs2_dout(23,16)
      dinb := io.rs2_dout(31,24)
      state := state + 1.U
    }.elsewhen(Cat(state, io.access_type)==="b011100".U){
//      result := Cat(0.U(16.W), result(15,0))
      state := 0.U
    }.elsewhen(Cat(state, io.access_type)==="b000001".U){// signed halfword
      addra := io.addr
      addrb := io.addr + 1.U
      dina := io.rs2_dout(7,0)
      dinb := io.rs2_dout(15,8)
      wea := io.mem_write_en
      web := io.mem_write_en
      when(wait_state === 1.U){
        wait_state := 0.U
        ena := true.B
        enb := true.B
      }.otherwise {
        state := state + 1.U
      }
    }.elsewhen(Cat(state, io.access_type)==="b001001".U){
      result := Cat(Fill(16,io.recv_data_b(7)), io.recv_data_b, io.recv_data_a)
      state := state + 1.U
      addra := io.addr + 2.U
      addrb := io.addr + 3.U
      wea := false.B
      web := false.B
      ena := false.B
      enb := false.B
    }.elsewhen(Cat(state, io.access_type)==="b010001".U){
      dina := io.rs2_dout(23,16)
      dinb := io.rs2_dout(31,24)
      state := state + 1.U
    }.elsewhen(Cat(state, io.access_type)==="b011001".U){
//      result := Cat(Fill(16,result(15)), result(15,0))
      state := 0.U
    }.elsewhen(Cat(state, io.access_type)==="b000101".U){ // unsigned halfword
      addra := io.addr
      addrb := io.addr + 1.U
      dina := io.rs2_dout(7,0)
      dinb := io.rs2_dout(15,8)
      wea := io.mem_write_en
      web := io.mem_write_en
      when(wait_state === 1.U){
        wait_state := 0.U
        ena := true.B
        enb := true.B
      }.otherwise {
        state := state + 1.U
      }
    }.elsewhen(Cat(state, io.access_type)==="b001101".U){
      result := Cat(Fill(16, 0.U), io.recv_data_b, io.recv_data_a)
      state := state + 1.U
      addra := io.addr + 2.U
      addrb := io.addr + 3.U
      wea := false.B
      web := false.B
      ena := false.B
      enb := false.B
    }.elsewhen(Cat(state, io.access_type)==="b010101".U){
      dina := io.rs2_dout(23,16)
      dinb := io.rs2_dout(31,24)
      state := state + 1.U
    }.elsewhen(Cat(state, io.access_type)==="b011101".U){
//      result := Cat(0.U(16.W), result(15,0))
      state := 0.U
    }.elsewhen(Cat(state, io.access_type)==="b000010".U){   // signed word
      addra := io.addr
      addrb := io.addr + 1.U
      dina := io.rs2_dout(7,0)
      dinb := io.rs2_dout(15,8)
      wea := io.mem_write_en
      web := io.mem_write_en
      when(wait_state === 1.U){
        wait_state := 0.U
        ena := true.B
        enb := true.B
      }.otherwise {
        state := state + 1.U
      }
    }.elsewhen(Cat(state, io.access_type)==="b001010".U){
      result := Cat(io.recv_data_b, io.recv_data_a)
      state := state + 1.U
      addra := io.addr + 2.U
      addrb := io.addr + 3.U
      dina := io.rs2_dout(23,16)
      dinb := io.rs2_dout(31,24)
    }.elsewhen(Cat(state, io.access_type)==="b010010".U){
      state := state + 1.U
    }.elsewhen(Cat(state, io.access_type)==="b011010".U){
      result := Cat(io.recv_data_b, io.recv_data_a, result(15,0))
      state := 0.U
      wea := false.B
      web := false.B
      ena := false.B
      enb := false.B
    }.elsewhen(Cat(state, io.access_type)==="b000110".U){   // unsigned word
      addra := io.addr
      addrb := io.addr + 1.U
      dina := io.rs2_dout(7,0)
      dinb := io.rs2_dout(15,8)
      wea := io.mem_write_en
      web := io.mem_write_en
      ena := true.B
      enb := true.B
      when(wait_state === 1.U){
        wait_state := 0.U
      }.otherwise {
        state := state + 1.U
      }
    }.elsewhen(Cat(state, io.access_type)==="b001110".U){
      result := Cat(io.recv_data_b, io.recv_data_a)
      state := state + 1.U
      addra := io.addr + 2.U
      addrb := io.addr + 3.U
      dina := io.rs2_dout(23,16)
      dinb := io.rs2_dout(31,24)
    }.elsewhen(Cat(state, io.access_type)==="b010110".U){
      state := state + 1.U
    }.elsewhen(Cat(state, io.access_type)==="b011110".U){
      result := Cat(0.U(0.W), result(31,0))
      wea := false.B
      web := false.B
      ena := false.B
      enb := false.B
      state := 0.U
    }.otherwise{
      state := 0.U
      wait_state := 1.U
      wea := false.B
      web := false.B
      ena := false.B
      enb := false.B
    }
  }.otherwise{
    state := 0.U
    wait_state := 1.U
    wea := false.B
    web := false.B
    ena := false.B
    enb := false.B
  }
}

class DataMemory64 extends Module{
  val io = IO(new Bundle{
    val mem_write_en = Input(Bool())
    val mem_read_en = Input(Bool())
    val addr = Input(UInt(64.W))
    val rs2_dout = Input(UInt(64.W))
    val recv_data_a = Input(UInt(8.W))
    val recv_data_b = Input(UInt(8.W))
    val access_type = Input(UInt(3.W))
    /* access_type decoding (funct3)
    000: byte (LB, SB)
    001: halfword (LH, SH)
    010: word (LW, SW)
    011: doubleword (LD, SD)
    100: byte unsigned (LBU)
    101: halfword unsigned (LHU)
    110: word unsigned (LWU)
    */
    val tick_exmem = Input(Bool())
    val result = Output(UInt(64.W))
//    val enab = Output(Bool())
    val ena = Output(Bool())
    val enb = Output(Bool())
    val wea = Output(Bool())
    val web = Output(Bool())
    val addra = Output(UInt(64.W))
    val addrb = Output(UInt(64.W))
    val dina = Output(UInt(8.W))
    val dinb = Output(UInt(8.W))
  })

  val result = RegInit(0.U(64.W))
//  val enab = RegInit(false.B)
  val ena = RegInit(false.B)
  val enb = RegInit(false.B)
  val wea = RegInit(false.B)
  val web = RegInit(false.B)
  val addra = RegInit(0.U(64.W))
  val addrb = RegInit(0.U(64.W))
  val dina = RegInit(0.U(8.W))
  val dinb = RegInit(0.U(8.W))

  io.result := result
//  io.enab := enab
  io.wea := wea
  io.web := web
  io.addra := addra
  io.addrb := addrb
  io.dina := dina
  io.dinb := dinb

//  enab := (io.mem_read_en | io.mem_write_en) & io.tick_exmem

  io.ena := ena
  io.enb := enb

  val state = RegInit(0.U(3.W))
  val wait_state = RegInit(1.U)

  when((io.mem_read_en | io.mem_write_en) && io.tick_exmem){
    when(Cat(state, io.access_type)==="b000000".U){ // signed byte
      addra := io.addr
      addrb := io.addr + 1.U
      dina := io.rs2_dout(7,0)
      dinb := io.rs2_dout(15,8)
      wea := io.mem_write_en
      web := false.B
      ena := false.B
      enb := false.B
      when(wait_state === 1.U){
        wait_state := 0.U
      }.otherwise{
        state := state + 1.U
        ena := true.B
      }
    }.elsewhen(Cat(state, io.access_type)==="b001000".U){
      result := Cat(Fill(56,io.recv_data_a(7)), io.recv_data_a)
      state := state + 1.U
      addra := io.addr + 2.U
      addrb := io.addr + 3.U
      wea := false.B
      web := false.B
      ena := false.B
      enb := false.B
    }.elsewhen(Cat(state, io.access_type)==="b010000".U){
      dina := io.rs2_dout(23,16)
      dinb := io.rs2_dout(31,24)
      state := state + 1.U
    }.elsewhen(Cat(state, io.access_type)==="b011000".U){
      state := state + 1.U
      addra := io.addr + 4.U
      addrb := io.addr + 5.U
    }.elsewhen(Cat(state, io.access_type)==="b100000".U){
      state := state + 1.U
      dina := io.rs2_dout(39,32)
      dinb := io.rs2_dout(47,40)
    }.elsewhen(Cat(state, io.access_type)==="b101000".U){
      addra := io.addr + 6.U
      addrb := io.addr + 7.U
      state := state + 1.U
    }.elsewhen(Cat(state, io.access_type)==="b110000".U){
      dina := io.rs2_dout(55,48)
      dinb := io.rs2_dout(63,56)
      state := state + 1.U
    }.elsewhen(Cat(state, io.access_type)==="b111000".U){
      state := 0.U
    }.elsewhen(Cat(state, io.access_type)==="b000100".U){  // unsigned byte
      addra := io.addr
      addrb := io.addr
      dina := io.rs2_dout(7,0)
      dinb := 0.U
      wea := io.mem_write_en
      web := false.B
      ena := true.B
      enb := false.B
      when(wait_state === 1.U){
        wait_state := 0.U
      }.otherwise{
        state := state + 1.U
        ena := false.B
        wea := false.B
      }
    }.elsewhen(Cat(state, io.access_type)==="b001100".U){
      result := Cat(Fill(56,0.U), io.recv_data_a)
      state := state + 1.U
      addra := io.addr + 2.U
      addrb := io.addr + 3.U
      dina := io.rs2_dout(23,16)
      dinb := io.rs2_dout(31,24)
    }.elsewhen(Cat(state, io.access_type)==="b010100".U){
      state := state + 1.U
    }.elsewhen(Cat(state, io.access_type)==="b011100".U){
      addra := io.addr + 4.U
      addrb := io.addr + 5.U
      dina := io.rs2_dout(39,32)
      dinb := io.rs2_dout(47,40)
      state := state + 1.U
    }.elsewhen(Cat(state, io.access_type)==="b100100".U){
      state := state + 1.U
    }.elsewhen(Cat(state, io.access_type)==="b101100".U){
      state := state + 1.U
      addra := io.addr + 6.U
      addrb := io.addr + 7.U
      dina := io.rs2_dout(55,48)
      dinb := io.rs2_dout(63,56)
    }.elsewhen(Cat(state, io.access_type)==="b110100".U){
      state := state + 1.U
    }.elsewhen(Cat(state, io.access_type)==="b111100".U){
      state := 0.U
    }.elsewhen(Cat(state, io.access_type)==="b000001".U){// signed halfword
      addra := io.addr
      addrb := io.addr + 1.U
      dina := io.rs2_dout(7,0)
      dinb := io.rs2_dout(15,8)
      wea := io.mem_write_en
      web := io.mem_write_en
      ena := true.B
      enb := true.B
      when(wait_state === 1.U){
        wait_state := 0.U
      }.otherwise {
        state := state + 1.U
        wea := false.B
        web := false.B
        ena := false.B
        enb := false.B
      }
    }.elsewhen(Cat(state, io.access_type)==="b001001".U){
      result := Cat(Fill(48,io.recv_data_b(7)), io.recv_data_b, io.recv_data_a)
      state := state + 1.U
      addra := io.addr + 2.U
      addrb := io.addr + 3.U
      dina := io.rs2_dout(23,16)
      dinb := io.rs2_dout(31,24)
    }.elsewhen(Cat(state, io.access_type)==="b010001".U){
      state := state + 1.U
    }.elsewhen(Cat(state, io.access_type)==="b011001".U){
      addra := io.addr + 4.U
      addrb := io.addr + 5.U
      dina := io.rs2_dout(39,32)
      dinb := io.rs2_dout(47,40)
      state := state + 1.U
    }.elsewhen(Cat(state, io.access_type)==="b100001".U){
      state := state + 1.U
    }.elsewhen(Cat(state, io.access_type)==="b101001".U){
      addra := io.addr + 6.U
      addrb := io.addr + 7.U
      dina := io.rs2_dout(55,48)
      dinb := io.rs2_dout(63,56)
      state := state + 1.U
    }.elsewhen(Cat(state, io.access_type)==="b110001".U){
      state := state + 1.U
    }.elsewhen(Cat(state, io.access_type)==="b111001".U){
      state := 0.U
    }.elsewhen(Cat(state, io.access_type)==="b000101".U){ // unsigned halfword
      addra := io.addr
      addrb := io.addr + 1.U
      dina := io.rs2_dout(7,0)
      dinb := io.rs2_dout(15,8)
      wea := io.mem_write_en
      web := io.mem_write_en
      ena := true.B
      enb := true.B
      when(wait_state === 1.U){
        wait_state := 0.U
      }.otherwise {
        state := state + 1.U
        wea := false.B
        web := false.B
        ena := false.B
        enb := false.B
      }
    }.elsewhen(Cat(state, io.access_type)==="b001101".U){
      result := Cat(Fill(48, 0.U), io.recv_data_b, io.recv_data_a)
      state := state + 1.U
      addra := io.addr + 2.U
      addrb := io.addr + 3.U
      dina := io.rs2_dout(23,16)
      dinb := io.rs2_dout(31,24)
    }.elsewhen(Cat(state, io.access_type)==="b010101".U){
      state := state + 1.U
    }.elsewhen(Cat(state, io.access_type)==="b011101".U){
      addra := io.addr + 4.U
      addrb := io.addr + 5.U
      dina := io.rs2_dout(39,32)
      dinb := io.rs2_dout(47,40)
      state := state + 1.U
    }.elsewhen(Cat(state, io.access_type)==="b100101".U){
      state := state + 1.U
    }.elsewhen(Cat(state, io.access_type)==="b101101".U){
      addra := io.addr + 6.U
      addrb := io.addr + 7.U
      dina := io.rs2_dout(55,48)
      dinb := io.rs2_dout(63,56)
      state := state + 1.U
    }.elsewhen(Cat(state, io.access_type)==="b110101".U){
      state := state + 1.U
    }.elsewhen(Cat(state, io.access_type)==="b111101".U){
      state := 0.U
    }.elsewhen(Cat(state, io.access_type)==="b000010".U){   // signed word
      addra := io.addr
      addrb := io.addr + 1.U
      dina := io.rs2_dout(7,0)
      dinb := io.rs2_dout(15,8)
      wea := io.mem_write_en
      web := io.mem_write_en
      ena := true.B
      enb := true.B
      when(wait_state === 1.U){
        wait_state := 0.U

      }.otherwise {
        state := state + 1.U
        ena := false.B
        enb := false.B
      }
    }.elsewhen(Cat(state, io.access_type)==="b001010".U){
      result := Cat(Fill(32,io.recv_data_b(7)), io.recv_data_b, io.recv_data_a)
      state := state + 1.U
      addra := io.addr + 2.U
      addrb := io.addr + 3.U
      dina := io.rs2_dout(23,16)
      dinb := io.rs2_dout(31,24)
      ena := true.B
      enb := true.B
    }.elsewhen(Cat(state, io.access_type)==="b010010".U){
      state := state + 1.U
      wea := false.B
      web := false.B
      enb := false.B
      ena := false.B
    }.elsewhen(Cat(state, io.access_type)==="b011010".U){
      result := Cat(Fill(32, io.recv_data_b(7)) ,io.recv_data_b, io.recv_data_a, result(15,0))
      state := state + 1.U
      addra := io.addr + 4.U
      addrb := io.addr + 5.U
      dina := io.rs2_dout(39,32)
      dinb := io.rs2_dout(47,40)
    }.elsewhen(Cat(state, io.access_type)==="b100010".U){
      state := state + 1.U
    }.elsewhen(Cat(state, io.access_type)==="b101010".U){
      addra := io.addr + 6.U
      addrb := io.addr + 7.U
      dina := io.rs2_dout(55,48)
      dinb := io.rs2_dout(63,56)
      state := state + 1.U
    }.elsewhen(Cat(state, io.access_type)==="b110010".U){
      state := state + 1.U
    }.elsewhen(Cat(state, io.access_type)==="b111010".U){
      state := 0.U
    }.elsewhen(Cat(state, io.access_type)==="b000110".U){   // unsigned word
      addra := io.addr
      addrb := io.addr + 1.U
      dina := io.rs2_dout(7,0)
      dinb := io.rs2_dout(15,8)
      wea := io.mem_write_en
      web := io.mem_write_en
      ena := true.B
      enb := true.B
      when(wait_state === 1.U){
        wait_state := 0.U
      }.otherwise {
        state := state + 1.U
        ena := false.B
        enb := false.B
      }
    }.elsewhen(Cat(state, io.access_type)==="b001110".U){
      result := Cat(io.recv_data_b, io.recv_data_a)
      state := state + 1.U
      addra := io.addr + 2.U
      addrb := io.addr + 3.U
      dina := io.rs2_dout(23,16)
      dinb := io.rs2_dout(31,24)
      ena := true.B
      enb := true.B
    }.elsewhen(Cat(state, io.access_type)==="b010110".U){
      state := state + 1.U
      ena := false.B
      enb := false.B
      wea := false.B
      web := false.B
    }.elsewhen(Cat(state, io.access_type)==="b011110".U){
      result := Cat(0.U(32.W), result(31,0))
      state := state + 1.U
      addra := io.addr + 4.U
      addrb := io.addr + 5.U
      dina := io.rs2_dout(39,32)
      dinb := io.rs2_dout(47,40)
    }.elsewhen(Cat(state, io.access_type)==="b100110".U){
      state := state + 1.U
    }.elsewhen(Cat(state, io.access_type)==="b101110".U){
      state := state + 1.U
      addra := io.addr + 6.U
      addrb := io.addr + 7.U
      dina := io.rs2_dout(55,48)
      dinb := io.rs2_dout(63,56)
    }.elsewhen(Cat(state, io.access_type)==="b110110".U){
      state := state + 1.U
    }.elsewhen(Cat(state, io.access_type)==="b111110".U){
      state := 0.U
    }.elsewhen(Cat(state, io.access_type)==="b000011".U){ // double
      addra := io.addr
      addrb := io.addr + 1.U
      dina := io.rs2_dout(7,0)
      dinb := io.rs2_dout(15,8)
      wea := io.mem_write_en
      web := io.mem_write_en
      ena := true.B
      enb := true.B
      when(wait_state === 1.U){
        wait_state := 0.U
      }.otherwise {
        state := state + 1.U
        ena := false.B
        enb := false.B
      }
    }.elsewhen(Cat(state, io.access_type)==="b001011".U){
      result := Cat(io.recv_data_b, io.recv_data_a)
      state := state + 1.U
      addra := io.addr + 2.U
      addrb := io.addr + 3.U
      dina := io.rs2_dout(23,16)
      dinb := io.rs2_dout(31,24)
      ena := true.B
      enb := true.B
    }.elsewhen(Cat(state, io.access_type)==="b010011".U){
      state := state + 1.U
      ena := false.B
      enb := false.B
    }.elsewhen(Cat(state, io.access_type)==="b011011".U){
      result := Cat(io.recv_data_b, io.recv_data_a, result(15,0))
      state := state + 1.U
      addra := io.addr + 4.U
      addrb := io.addr + 5.U
      dina := io.rs2_dout(39,32)
      dinb := io.rs2_dout(47,40)
      ena := true.B
      enb := true.B
    }.elsewhen(Cat(state, io.access_type)==="b100011".U){
      state := state + 1.U
      ena := false.B
      enb := false.B
    }.elsewhen(Cat(state, io.access_type)==="b101011".U){
      result := Cat(io.recv_data_b, io.recv_data_a, result(31,0))
      state := state + 1.U
      addra := io.addr + 6.U
      addrb := io.addr + 7.U
      dina := io.rs2_dout(55,48)
      dinb := io.rs2_dout(63,56)
      ena := true.B
      enb := true.B
    }.elsewhen(Cat(state, io.access_type)==="b110011".U){
      state := state + 1.U
      wea := false.B
      web := false.B
      ena := false.B
      enb := false.B
    }.elsewhen(Cat(state, io.access_type)==="b111011".U){
      result := Cat(io.recv_data_b, io.recv_data_a, result(47,0))
      state := 0.U
    }
  }.otherwise{
    state := 0.U
    wait_state := 1.U
    wea := false.B
    web := false.B
    ena := false.B
    enb := false.B
  }
}



class PhysicalMemory(Width:Int = 32) extends Module{
  val io = IO(new Bundle{
    val selected = Input(Bool()) 
    val mem_write_en = Input(Bool())
    val mem_read_en = Input(Bool())
    val addr = Input(UInt(Width.W))
    val rs2_dout = Input(UInt(Width.W))
    val recv_data_a = Input(UInt(8.W))
    val recv_data_b = Input(UInt(8.W))
    val access_type = Input(UInt(3.W))
    /* access_type decoding (funct3)
    000: byte (LB, SB)
    001: halfword (LH, SH)
    010: word (LW, SW)
    011: doubleword (LD, SD)
    100: byte unsigned (LBU)
    101: halfword unsigned (LHU)
    110: word unsigned (LWU)
    */
    val tick_exmem = Input(Bool())
    val result = Output(UInt(Width.W))
    val ena = Output(Bool())
    val enb = Output(Bool())
    val wea = Output(Bool())
    val web = Output(Bool())
    val addra = Output(UInt(Width.W))
    val addrb = Output(UInt(Width.W))
    val dina = Output(UInt(8.W))
    val dinb = Output(UInt(8.W))
  })

  if(Width == 32){
    val dataMemory32 = Module(new DataMemory32)
    dataMemory32.io.mem_write_en := io.mem_write_en && io.selected
    dataMemory32.io.mem_read_en := io.mem_read_en && io.selected
    dataMemory32.io.addr := io.addr
    dataMemory32.io.rs2_dout := io.rs2_dout
    dataMemory32.io.recv_data_a := io.recv_data_a
    dataMemory32.io.recv_data_b := io.recv_data_b
    dataMemory32.io.access_type := io.access_type
    dataMemory32.io.tick_exmem := io.tick_exmem

    io.result := dataMemory32.io.result
    io.ena := dataMemory32.io.ena
    io.enb := dataMemory32.io.enb
    io.wea := dataMemory32.io.wea
    io.web := dataMemory32.io.web
    io.addra := dataMemory32.io.addra
    io.addrb := dataMemory32.io.addrb
    io.dina := dataMemory32.io.dina
    io.dinb := dataMemory32.io.dinb
  }else{
    val dataMemory64 = Module(new DataMemory64)
    dataMemory64.io.mem_write_en := io.mem_write_en && io.selected
    dataMemory64.io.mem_read_en := io.mem_read_en && io.selected
    dataMemory64.io.addr := io.addr
    dataMemory64.io.rs2_dout := io.rs2_dout
    dataMemory64.io.recv_data_a := io.recv_data_a
    dataMemory64.io.recv_data_b := io.recv_data_b
    dataMemory64.io.access_type := io.access_type
    dataMemory64.io.tick_exmem := io.tick_exmem

    io.result := dataMemory64.io.result
    io.ena := dataMemory64.io.ena
    io.enb := dataMemory64.io.enb
    io.wea := dataMemory64.io.wea
    io.web := dataMemory64.io.web
    io.addra := dataMemory64.io.addra
    io.addrb := dataMemory64.io.addrb
    io.dina := dataMemory64.io.dina
    io.dinb := dataMemory64.io.dinb
  }
}

class SerialAccess(Width:Int = 32) extends Module{
  val io = IO(new Bundle{
    val selected = Input(Bool())
    val mem_write_en = Input(Bool())
    val mem_read_en = Input(Bool())
    val addr = Input(UInt(Width.W))
    val rs2_dout = Input(UInt(Width.W))
    val recv_data = Output(UInt(Width.W))
    val access_type = Input(UInt(3.W))
    /* access_type decoding (funct3)
    000: byte (LB, SB)
    001: halfword (LH, SH)
    010: word (LW, SW)
    011: doubleword (LD, SD)
    100: byte unsigned (LBU)
    101: halfword unsigned (LHU)
    110: word unsigned (LWU)
    */
    val tick_device = Input(Bool())
  })

  val mmio = Module(new MMIO_Core())

  val len = Wire(UInt(5.W))
  len := MuxLookup(io.access_type(1,0), 1.U)(Seq(
    "b00".U -> 1.U,
    "b01".U -> 2.U,
    "b10".U -> 4.U,
    "b11".U -> 8.U
  ))

  mmio.io.we := io.mem_write_en && io.selected && io.tick_device
  mmio.io.re := io.mem_read_en && io.selected && io.tick_device
  mmio.io.addr := io.addr
  mmio.io.din := io.rs2_dout
  mmio.io.len := len
  io.recv_data := mmio.io.dout

  mmio.io.clk := clock
  mmio.io.rst := reset.asBool
 

}


class RTCAccess(Width:Int = 32) extends Module{
  val io = IO(new Bundle{
    val selected = Input(Bool())
    val mem_write_en = Input(Bool())
    val mem_read_en = Input(Bool())
    val addr = Input(UInt(Width.W))
    val rs2_dout = Input(UInt(Width.W))
    val recv_data = Output(UInt(Width.W))
    val access_type = Input(UInt(3.W))
    /* access_type decoding (funct3)
    000: byte (LB, SB)
    001: halfword (LH, SH)
    010: word (LW, SW)
    011: doubleword (LD, SD)
    100: byte unsigned (LBU)
    101: halfword unsigned (LHU)
    110: word unsigned (LWU)
    */
    val tick_device = Input(Bool())
  })

  val mmio = Module(new MMIO_Core())

  val len = Wire(UInt(5.W))
  len := MuxLookup(io.access_type(1,0), 1.U)(Seq(
    "b00".U -> 1.U,
    "b01".U -> 2.U,
    "b10".U -> 4.U,
    "b11".U -> 8.U
  ))

  mmio.io.we := io.mem_write_en && io.selected && io.tick_device
  mmio.io.re := io.mem_read_en && io.selected && io.tick_device
  mmio.io.addr := io.addr
  mmio.io.din := io.rs2_dout
  mmio.io.len := len
  io.recv_data := mmio.io.dout

  mmio.io.clk := clock
  mmio.io.rst := reset.asBool
 

}

class VGA_Access(Width:Int = 32) extends Module{
  val io = IO(new Bundle{
    val selected = Input(Bool())
    val mem_write_en = Input(Bool())
    val mem_read_en = Input(Bool())
    val addr = Input(UInt(Width.W))
    val rs2_dout = Input(UInt(Width.W))
    val recv_data = Output(UInt(Width.W))
    val access_type = Input(UInt(3.W))
    /* access_type decoding (funct3)
    000: byte (LB, SB)
    001: halfword (LH, SH)
    010: word (LW, SW)
    011: doubleword (LD, SD)
    100: byte unsigned (LBU)
    101: halfword unsigned (LHU)
    110: word unsigned (LWU)
    */
    val tick_device = Input(Bool())
  })

  val mmio = Module(new MMIO_Core())

  val len = Wire(UInt(5.W))
  len := MuxLookup(io.access_type(1,0), 1.U)(Seq(
    "b00".U -> 1.U,
    "b01".U -> 2.U,
    "b10".U -> 4.U,
    "b11".U -> 8.U
  ))

  mmio.io.we := io.mem_write_en && io.selected && io.tick_device
  mmio.io.re := io.mem_read_en && io.selected && io.tick_device
  mmio.io.addr := io.addr
  mmio.io.din := io.rs2_dout
  mmio.io.len := len
  io.recv_data := mmio.io.dout

  mmio.io.clk := clock
  mmio.io.rst := reset.asBool
 

}

class VmemAccess(Width:Int = 32) extends Module{
  val io = IO(new Bundle{
    val selected = Input(Bool())
    val mem_write_en = Input(Bool())
    val mem_read_en = Input(Bool())
    val addr = Input(UInt(Width.W))
    val rs2_dout = Input(UInt(Width.W))
    val recv_data = Output(UInt(Width.W))
    val access_type = Input(UInt(3.W))
    /* access_type decoding (funct3)
    000: byte (LB, SB)
    001: halfword (LH, SH)
    010: word (LW, SW)
    011: doubleword (LD, SD)
    100: byte unsigned (LBU)
    101: halfword unsigned (LHU)
    110: word unsigned (LWU)
    */
    val tick_device = Input(Bool())
  })

  val mmio = Module(new MMIO_Core())

  val len = Wire(UInt(5.W))
  len := MuxLookup(io.access_type(1,0), 1.U)(Seq(
    "b00".U -> 1.U,
    "b01".U -> 2.U,
    "b10".U -> 4.U,
    "b11".U -> 8.U
  ))

  mmio.io.we := io.mem_write_en && io.selected && io.tick_device
  mmio.io.re := io.mem_read_en && io.selected && io.tick_device
  mmio.io.addr := io.addr
  mmio.io.din := io.rs2_dout
  mmio.io.len := len
  io.recv_data := mmio.io.dout

  mmio.io.clk := clock
  mmio.io.rst := reset.asBool
 

}

class KeyboardAccess(Width:Int = 32) extends Module{
  val io = IO(new Bundle{
    val selected = Input(Bool())
    val mem_write_en = Input(Bool())
    val mem_read_en = Input(Bool())
    val addr = Input(UInt(Width.W))
    val rs2_dout = Input(UInt(Width.W))
    val recv_data = Output(UInt(Width.W))
    val access_type = Input(UInt(3.W))
    /* access_type decoding (funct3)
    000: byte (LB, SB)
    001: halfword (LH, SH)
    010: word (LW, SW)
    011: doubleword (LD, SD)
    100: byte unsigned (LBU)
    101: halfword unsigned (LHU)
    110: word unsigned (LWU)
    */
    val tick_device = Input(Bool())
  })

  val mmio = Module(new MMIO_Core())

  val len = Wire(UInt(5.W))
  len := MuxLookup(io.access_type(1,0), 1.U)(Seq(
    "b00".U -> 1.U,
    "b01".U -> 2.U,
    "b10".U -> 4.U,
    "b11".U -> 8.U
  ))

  mmio.io.we := io.mem_write_en && io.selected && io.tick_device
  mmio.io.re := io.mem_read_en && io.selected && io.tick_device
  mmio.io.addr := io.addr
  mmio.io.din := io.rs2_dout
  mmio.io.len := len
  io.recv_data := mmio.io.dout

  mmio.io.clk := clock
  mmio.io.rst := reset.asBool
 

}


class AudioAccess(Width:Int = 32) extends Module{
  val io = IO(new Bundle{
    val selected = Input(Bool())
    val mem_write_en = Input(Bool())
    val mem_read_en = Input(Bool())
    val addr = Input(UInt(Width.W))
    val rs2_dout = Input(UInt(Width.W))
    val recv_data = Output(UInt(Width.W))
    val access_type = Input(UInt(3.W))
    /* access_type decoding (funct3)
    000: byte (LB, SB)
    001: halfword (LH, SH)
    010: word (LW, SW)
    011: doubleword (LD, SD)
    100: byte unsigned (LBU)
    101: halfword unsigned (LHU)
    110: word unsigned (LWU)
    */
    val tick_device = Input(Bool())
  })

  val mmio = Module(new MMIO_Core())

  val len = Wire(UInt(5.W))
  len := MuxLookup(io.access_type(1,0), 1.U)(Seq(
    "b00".U -> 1.U,
    "b01".U -> 2.U,
    "b10".U -> 4.U,
    "b11".U -> 8.U
  ))

  mmio.io.we := io.mem_write_en && io.selected && io.tick_device
  mmio.io.re := io.mem_read_en && io.selected && io.tick_device
  mmio.io.addr := io.addr
  mmio.io.din := io.rs2_dout
  mmio.io.len := len
  io.recv_data := mmio.io.dout

  mmio.io.clk := clock
  mmio.io.rst := reset.asBool
 

}


class AudioSbufAccess(Width:Int = 32) extends Module{
  val io = IO(new Bundle{
    val selected = Input(Bool())
    val mem_write_en = Input(Bool())
    val mem_read_en = Input(Bool())
    val addr = Input(UInt(Width.W))
    val rs2_dout = Input(UInt(Width.W))
    val recv_data = Output(UInt(Width.W))
    val access_type = Input(UInt(3.W))
    /* access_type decoding (funct3)
    000: byte (LB, SB)
    001: halfword (LH, SH)
    010: word (LW, SW)
    011: doubleword (LD, SD)
    100: byte unsigned (LBU)
    101: halfword unsigned (LHU)
    110: word unsigned (LWU)
    */
    val tick_device = Input(Bool())
  })

  val mmio = Module(new MMIO_Core())

  val len = Wire(UInt(5.W))
  len := MuxLookup(io.access_type(1,0), 1.U)(Seq(
    "b00".U -> 1.U,
    "b01".U -> 2.U,
    "b10".U -> 4.U,
    "b11".U -> 8.U
  ))

  mmio.io.we := io.mem_write_en && io.selected && io.tick_device
  mmio.io.re := io.mem_read_en && io.selected && io.tick_device
  mmio.io.addr := io.addr
  mmio.io.din := io.rs2_dout
  mmio.io.len := len
  io.recv_data := mmio.io.dout

  mmio.io.clk := clock
  mmio.io.rst := reset.asBool
 

}



class DataMemory(Width:Int = 32) extends Module{
  val io = IO(new Bundle{
    val mem_write_en = Input(Bool())
    val mem_read_en = Input(Bool())
    val addr = Input(UInt(Width.W))
    val rs2_dout = Input(UInt(Width.W))
    val recv_data_a = Input(UInt(8.W))
    val recv_data_b = Input(UInt(8.W))
    val access_type = Input(UInt(3.W))
    val selected = Input(UInt(DeviceMap.device_num.W))
    /* access_type decoding (funct3)
    000: byte (LB, SB)
    001: halfword (LH, SH)
    010: word (LW, SW)
    011: doubleword (LD, SD)
    100: byte unsigned (LBU)
    101: halfword unsigned (LHU)
    110: word unsigned (LWU)
    */
    val tick_exmem = Input(Bool())
    val tick_device = Input(Bool())
    val result = Output(UInt(Width.W))
    val ena = Output(Bool())
    val enb = Output(Bool())
    val wea = Output(Bool())
    val web = Output(Bool())
    val addra = Output(UInt(Width.W))
    val addrb = Output(UInt(Width.W))
    val dina = Output(UInt(8.W))
    val dinb = Output(UInt(8.W))
  })

  val PhysicalMemoryModule = Module(new PhysicalMemory(Width))
  val SerialModule = Module(new SerialAccess(Width))
  val RTCModule = Module(new RTCAccess(Width))
  val VGA_Module = Module(new VGA_Access(Width))
  val VmemModule = Module(new VmemAccess(Width))
  val KeyboardModule = Module(new KeyboardAccess(Width))
  val AudioModule = Module(new AudioAccess(Width))
  val AudioSbufModule = Module(new AudioSbufAccess(Width))

  val results = Seq(
    PhysicalMemoryModule.io.result,
    SerialModule.io.recv_data,
    RTCModule.io.recv_data,
    VGA_Module.io.recv_data,
    VmemModule.io.recv_data,
    KeyboardModule.io.recv_data,
    AudioModule.io.recv_data,
    AudioSbufModule.io.recv_data
  )

  io.result := MuxLookup(io.selected, results(0))(
    results.zipWithIndex.map { case (r, i) => (1 << i).U -> r }
  )

  PhysicalMemoryModule.io.selected := io.selected(0)
  PhysicalMemoryModule.io.mem_write_en := io.mem_write_en
  PhysicalMemoryModule.io.mem_read_en := io.mem_read_en
  PhysicalMemoryModule.io.addr := io.addr
  PhysicalMemoryModule.io.rs2_dout := io.rs2_dout
  PhysicalMemoryModule.io.recv_data_a := io.recv_data_a
  PhysicalMemoryModule.io.recv_data_b := io.recv_data_b
  PhysicalMemoryModule.io.access_type := io.access_type
  PhysicalMemoryModule.io.tick_exmem := io.tick_exmem

  io.ena := PhysicalMemoryModule.io.ena
  io.enb := PhysicalMemoryModule.io.enb
  io.wea := PhysicalMemoryModule.io.wea
  io.web := PhysicalMemoryModule.io.web
  io.addra := PhysicalMemoryModule.io.addra
  io.addrb := PhysicalMemoryModule.io.addrb
  io.dina := PhysicalMemoryModule.io.dina
  io.dinb := PhysicalMemoryModule.io.dinb




  SerialModule.io.selected := io.selected(1)
  SerialModule.io.mem_write_en := io.mem_write_en
  SerialModule.io.mem_read_en := io.mem_read_en
  SerialModule.io.addr := io.addr
  SerialModule.io.rs2_dout := io.rs2_dout
  SerialModule.io.access_type := io.access_type
  SerialModule.io.tick_device := io.tick_device
   // 这里假设串口设备


  RTCModule.io.selected := io.selected(2)
  RTCModule.io.mem_write_en := io.mem_write_en
  RTCModule.io.mem_read_en := io.mem_read_en
  RTCModule.io.addr := io.addr
  RTCModule.io.rs2_dout := io.rs2_dout
  RTCModule.io.access_type := io.access_type
  RTCModule.io.tick_device := io.tick_device
    // 这里假设 RTC 设备

  VGA_Module.io.selected := io.selected(3)
  VGA_Module.io.mem_write_en := io.mem_write_en
  VGA_Module.io.mem_read_en := io.mem_read_en
  VGA_Module.io.addr := io.addr
  VGA_Module.io.rs2_dout := io.rs2_dout
  VGA_Module.io.access_type := io.access_type
  VGA_Module.io.tick_device := io.tick_device


  VmemModule.io.selected := io.selected(4)
  VmemModule.io.mem_write_en := io.mem_write_en
  VmemModule.io.mem_read_en := io.mem_read_en
  VmemModule.io.addr := io.addr
  VmemModule.io.rs2_dout := io.rs2_dout
  VmemModule.io.access_type := io.access_type
  VmemModule.io.tick_device := io.tick_device

  KeyboardModule.io.selected := io.selected(5)
  KeyboardModule.io.mem_write_en := io.mem_write_en
  KeyboardModule.io.mem_read_en := io.mem_read_en
  KeyboardModule.io.addr := io.addr
  KeyboardModule.io.rs2_dout := io.rs2_dout
  KeyboardModule.io.access_type := io.access_type
  KeyboardModule.io.tick_device := io.tick_device

  AudioModule.io.selected := io.selected(6)
  AudioModule.io.mem_write_en := io.mem_write_en
  AudioModule.io.mem_read_en := io.mem_read_en
  AudioModule.io.addr := io.addr
  AudioModule.io.rs2_dout := io.rs2_dout
  AudioModule.io.access_type := io.access_type
  AudioModule.io.tick_device := io.tick_device

  AudioSbufModule.io.selected := io.selected(7)
  AudioSbufModule.io.mem_write_en := io.mem_write_en
  AudioSbufModule.io.mem_read_en := io.mem_read_en
  AudioSbufModule.io.addr := io.addr
  AudioSbufModule.io.rs2_dout := io.rs2_dout
  AudioSbufModule.io.access_type := io.access_type
  AudioSbufModule.io.tick_device := io.tick_device




}




