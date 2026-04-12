package scpu

import chisel3._
import chisel3.util._

// ============================================================================
//  AXI4-Lite 总线定义与适配器
//
//  本文件为 NPC CPU 提供 AXI4-Lite 总线基础设施，包括：
//    1. 响应码常量         (AxiLiteResp)
//    2. 五通道 payload     (AxiLiteAddr / AxiLiteWriteData / AxiLiteWriteResp / AxiLiteReadData)
//    3. Master/Slave IO    (AxiLiteMasterIO / AxiLiteSlaveIO)
//    4. 写掩码生成器       (AxiLiteWstrb)
//    5. 读数据拆包器       (AxiLiteLoadUnpack)
//    6. 取指 AXI 适配器    (IFetchAXIAdapter)   — 只读 master
//    7. 访存 AXI 适配器    (LSUAXIAdapter)      — 读写 master
//    8. AXI RAM Slave      (AxiLiteRamSlave)    — 简单内存 slave
//    9. AXI 地址译码交叉开关 (AxiLiteCrossbar)  — 替代 IO_Distribute
//
//  设计原则（参见 AXI4_LITE_MIGRATION.md）：
//    - CPU 内部数据通路保留本地连接
//    - 只把 取指/Load-Store/MMIO 这些"地址化访问边界"改成 AXI4-Lite
//    - Harvard 结构：i_axi (取指) + d_axi (数据) 两个独立 master
// ============================================================================


// ============================================================================
//  一、AXI4-Lite 响应码
// ============================================================================

/** AXI4-Lite 2-bit 响应码常量
  *
  * 用于 BRESP（写响应）和 RRESP（读响应）通道。
  * 参考 AMBA AXI4-Lite 规范 Table A3-4。
  */
object AxiLiteResp {
  val OKAY   = 0.U(2.W)  // 00: 正常完成
  val EXOKAY = 1.U(2.W)  // 01: 独占访问成功（AXI4-Lite 一般不用）
  val SLVERR = 2.U(2.W)  // 10: 从设备内部错误
  val DECERR = 3.U(2.W)  // 11: 地址 decode 失败，没有匹配的从设备
}


// ============================================================================
//  二、五通道 Payload Bundle
// ============================================================================

/** AW / AR 通道共用的地址载荷
  *
  * @param addrWidth 地址位宽（通常为 32）
  *
  * 包含：
  *   - addr : 字节地址
  *   - prot : 保护类型（3-bit），第一版可固定接 0
  *            bit[0] = privileged (1) / unprivileged (0)
  *            bit[1] = non-secure (1) / secure (0)
  *            bit[2] = instruction (1) / data (0)
  */
class AxiLiteAddr(val addrWidth: Int) extends Bundle {
  val addr = UInt(addrWidth.W)
  val prot = UInt(3.W)
}

/** W 通道：写数据载荷
  *
  * @param dataWidth 数据位宽（通常为 64，匹配 RV64）
  *
  * 包含：
  *   - data : 整拍写数据，已按 byte lane 左移到正确位置
  *   - strb : 写字节掩码，每 bit 对应 data 中的一个字节
  *            strb(i)=1 表示 data[(i+1)*8-1 : i*8] 要写入
  */
class AxiLiteWriteData(val dataWidth: Int) extends Bundle {
  val data = UInt(dataWidth.W)
  val strb = UInt((dataWidth / 8).W)
}

/** B 通道：写响应载荷
  *
  * 只有一个 resp 字段，取值参见 AxiLiteResp。
  * Slave 在同时接收 AW 和 W 后，才能返回 B 响应。
  */
class AxiLiteWriteResp extends Bundle {
  val resp = UInt(2.W)
}

/** R 通道：读数据载荷
  *
  * @param dataWidth 数据位宽
  *
  * 包含：
  *   - data : 整拍读数据，master 需根据地址低位自行截取
  *   - resp : 读响应码，取值参见 AxiLiteResp
  */
class AxiLiteReadData(val dataWidth: Int) extends Bundle {
  val data = UInt(dataWidth.W)
  val resp = UInt(2.W)
}


// ============================================================================
//  三、Master / Slave 端口
// ============================================================================

/** AXI4-Lite Master 侧完整端口
  *
  * @param addrWidth 地址位宽
  * @param dataWidth 数据位宽
  *
  * 五个通道的方向按照 master 视角定义：
  *   - aw : Master -> Slave  (Decoupled = valid/ready + bits)
  *   - w  : Master -> Slave
  *   - b  : Slave  -> Master (Flipped Decoupled)
  *   - ar : Master -> Slave
  *   - r  : Slave  -> Master
  *
  * 使用方式：
  *   - Master 模块中：val io = IO(new AxiLiteMasterIO(...))
  *   - Slave  模块中：val io = IO(Flipped(new AxiLiteMasterIO(...)))
  *
  * Chisel 的 Decoupled 提供 valid/ready/bits 三部分：
  *   - valid : Output（源端发起，表示 payload 有效）
  *   - ready : Input （目标端确认，表示可以接收）
  *   - bits  : Output（payload 数据）
  *
  * Flipped(Decoupled(...)) 反转方向，变成接收端。
  */
class AxiLiteMasterIO(val addrWidth: Int, val dataWidth: Int) extends Bundle {
  // ── 写地址通道 (AW) ──
  // Master 提供写目标地址，Slave 通过 AWREADY 表示接受
  val aw = Decoupled(new AxiLiteAddr(addrWidth))

  // ── 写数据通道 (W) ──
  // Master 提供写数据和字节掩码，与 AW 独立握手
  val w  = Decoupled(new AxiLiteWriteData(dataWidth))

  // ── 写响应通道 (B) ──
  // Slave 在 AW + W 都完成后返回写状态码
  val b  = Flipped(Decoupled(new AxiLiteWriteResp))

  // ── 读地址通道 (AR) ──
  // Master 提供读目标地址
  val ar = Decoupled(new AxiLiteAddr(addrWidth))

  // ── 读数据通道 (R) ──
  // Slave 在 AR 握手后返回读数据和状态码
  val r  = Flipped(Decoupled(new AxiLiteReadData(dataWidth)))
}


// ============================================================================
//  四、写掩码 (WSTRB) 生成器
// ============================================================================

/** AXI4-Lite 写掩码与写数据对齐生成器
  *
  * 根据 RISC-V funct3 (access_type) 和字节地址低位，
  * 生成 AXI4-Lite 所需的 WSTRB 掩码和按 byte-lane 对齐的 WDATA。
  *
  * 64-bit 总线下的对应关系：
  *   SB (funct3=000): 写 1 字节, baseMask = 0000_0001
  *   SH (funct3=001): 写 2 字节, baseMask = 0000_0011
  *   SW (funct3=010): 写 4 字节, baseMask = 0000_1111
  *   SD (funct3=011): 写 8 字节, baseMask = 1111_1111
  *
  * WSTRB 最终值 = baseMask << byteOffset
  * WDATA 最终值 = 原始数据  << (byteOffset * 8)
  *
  * @param dataWidth 总线数据位宽（必须是 8 的倍数）
  */
object AxiLiteWstrb {

  /** 生成写掩码
    *
    * @param accessType funct3[1:0]，指示访问宽度 (00=B, 01=H, 10=W, 11=D)
    * @param byteOffset 地址低位 addr[log2(dataWidth/8)-1:0]
    * @param dataWidth  总线数据位宽
    * @return (wstrb, 数据左移量 bit 数)
    */
  def genStrb(accessType: UInt, byteOffset: UInt, dataWidth: Int = 64): UInt = {
    val strbWidth = dataWidth / 8  // 对 64-bit 总线，strbWidth = 8

    // 基础掩码：根据访问宽度确定需要写入多少个连续字节
    val baseMask = MuxLookup(accessType(1, 0), 1.U(strbWidth.W))(Seq(
      "b00".U -> "b00000001".U(strbWidth.W),  // byte:   1 字节
      "b01".U -> "b00000011".U(strbWidth.W),  // half:   2 字节
      "b10".U -> "b00001111".U(strbWidth.W),  // word:   4 字节
      "b11".U -> "b11111111".U(strbWidth.W)   // double: 8 字节
    ))

    // 按字节偏移左移，使掩码对齐到实际 byte lane
    (baseMask << byteOffset)(strbWidth - 1, 0)
  }

  /** 将写数据左移到正确的 byte lane
    *
    * @param wdata      原始写数据（来自寄存器堆 rs2）
    * @param byteOffset 地址低位偏移
    * @param dataWidth  总线数据位宽
    * @return 对齐后的写数据
    */
  def alignData(wdata: UInt, byteOffset: UInt, dataWidth: Int = 64): UInt = {
    // byteOffset << 3 = byteOffset * 8 = 需要左移的 bit 数
    (wdata << (byteOffset << 3))(dataWidth - 1, 0)
  }
}


// ============================================================================
//  五、读数据拆包器 (Load Unpack)
// ============================================================================

/** AXI4-Lite 读回数据拆包与符号/零扩展
  *
  * AXI4-Lite slave 总是返回整拍 RDATA，master 需要：
  *   1. 根据地址低位右移到目标 byte lane
  *   2. 根据 access_type (funct3) 截取 8/16/32/64 bit
  *   3. 做符号扩展（LB/LH/LW）或零扩展（LBU/LHU/LWU）
  *
  * 这个 helper 替代了当前 DataMemory32/64 里大量的字节拼装状态机。
  */
object AxiLiteLoadUnpack {

  /** 从整拍总线数据中提取 load 结果
    *
    * @param busData    总线返回的整拍 RDATA
    * @param byteOffset 原始地址低位 addr[2:0]（64-bit 总线）
    * @param accessType funct3，指示 load 类型
    * @return 最终写入寄存器的 64-bit 值
    */
  def unpack(busData: UInt, byteOffset: UInt, accessType: UInt): UInt = {
    // 第一步：按地址低位右移，让目标字节落到最低位
    val shifted = (busData >> (byteOffset << 3))(63, 0)

    // 第二步：按 access_type 做截取 + 扩展
    MuxLookup(accessType, shifted)(Seq(
      // ── 有符号 load ──
      "b000".U -> Cat(Fill(56, shifted(7)),  shifted(7, 0)),   // LB:  符号扩展 byte
      "b001".U -> Cat(Fill(48, shifted(15)), shifted(15, 0)),  // LH:  符号扩展 halfword
      "b010".U -> Cat(Fill(32, shifted(31)), shifted(31, 0)),  // LW:  符号扩展 word
      "b011".U -> shifted(63, 0),                              // LD:  完整 doubleword

      // ── 无符号 load ──
      "b100".U -> Cat(0.U(56.W), shifted(7, 0)),              // LBU: 零扩展 byte
      "b101".U -> Cat(0.U(48.W), shifted(15, 0)),             // LHU: 零扩展 halfword
      "b110".U -> Cat(0.U(32.W), shifted(31, 0))              // LWU: 零扩展 word
    ))
  }
}



// ============================================================================
//  六、取指 AXI4-Lite 适配器 (IFetchAXIAdapter)
// ============================================================================

/** 取指 AXI4-Lite Master 适配器（PC 变化检测版）
  *
  * 当 PC 改变时自动发起 AXI 读事务取指。
  * 内部缓存上一条指令的 PC；与当前 PC 不同时触发取指。
  *
  * 状态机：
  *   sIdle   → 比较 PC，若不同进入 sArWait
  *   sArWait → 发 ARVALID，等 ARREADY
  *   sRWait  → 等 RVALID，拿到指令后回 sIdle
  *
  * busy 驱动 Metronome.stuck，冻结流水线至取指完成。
  */
class IFetchAXIAdapter(addrWidth: Int = 32, dataWidth: Int = 64) extends Module {
  val io = IO(new Bundle {
    val pc   = Input(UInt(addrWidth.W))   // 当前 PC（来自 PC_Ctrl）
    val inst = Output(UInt(32.W))         // 取回的 32-bit 指令
    val busy = Output(Bool())             // 正忙 → 流水线暂停
    val axi  = new AxiLiteMasterIO(addrWidth, dataWidth)
  })

  // ── 状态定义 ──
  val sIdle :: sArWait :: sRWait :: Nil = Enum(3)
  val state = RegInit(sIdle)

  // ── 内部寄存器 ──
  val instReg  = RegInit(0x00000013.U(32.W)) // 初始 NOP (addi x0,x0,0)
  val cachedPC = RegInit(~0.U(addrWidth.W))  // 初始值 ≠ 任何有效 PC → 强制首次取指

  // ── PC 变化检测 ──
  val needFetch = (io.pc =/= cachedPC)

  // ── CPU 侧输出 ──
  io.inst := instReg
  io.busy := needFetch || (state =/= sIdle)

  // ── AXI 通道默认值（只读 master，写通道永久关闭）──
  io.axi.aw.valid     := false.B
  io.axi.aw.bits.addr := 0.U
  io.axi.aw.bits.prot := 0.U
  io.axi.w.valid      := false.B
  io.axi.w.bits.data  := 0.U
  io.axi.w.bits.strb  := 0.U
  io.axi.b.ready      := false.B
  io.axi.ar.valid     := false.B
  io.axi.ar.bits.addr := io.pc
  io.axi.ar.bits.prot := "b100".U  // prot[2]=1 → 指令访问
  io.axi.r.ready      := false.B

  // ── 状态机 ──
  switch(state) {
    is(sIdle) {
      when(needFetch) {
        state := sArWait
      }
    }
    is(sArWait) {
      io.axi.ar.valid     := true.B
      io.axi.ar.bits.addr := io.pc
      when(io.axi.ar.fire) {
        state := sRWait
      }
    }
    is(sRWait) {
      io.axi.r.ready := true.B
      when(io.axi.r.fire) {
        // 从 64-bit RDATA 按 PC[2:0] 提取 32-bit 指令
        val byteOffset = io.pc(log2Ceil(dataWidth / 8) - 1, 0)
        instReg  := (io.axi.r.bits.data >> (byteOffset << 3))(31, 0)
        cachedPC := io.pc
        state    := sIdle
      }
    }
  }
}


// ============================================================================
//  七、访存 AXI4-Lite 适配器 (LSUAXIAdapter)
// ============================================================================

/** Load/Store Unit AXI4-Lite Master 适配器
  *
  * 功能：
  *   - Load：AR+R 读事务，AxiLiteLoadUnpack 拆包
  *   - Store：AW+W+B 写事务，AxiLiteWstrb 生成 WSTRB
  *
  * 触发：start 脉冲（tick_device && memRead/Write && !privSel）。
  * 完成后直接回 sIdle，busy 驱动 Metronome.stuck。
  *
  * accessType 编码在 ARPROT/AWPROT[1:0]，供 MMIO slave 提取 len。
  */
class LSUAXIAdapter(addrWidth: Int = 32, dataWidth: Int = 64) extends Module {
  val io = IO(new Bundle {
    // ── CPU 侧 ──
    val start      = Input(Bool())             // 单周期启动脉冲
    val addr       = Input(UInt(addrWidth.W))  // 访存地址（ALU 结果）
    val wdata      = Input(UInt(dataWidth.W))  // 写数据（rs2）
    val accessType = Input(UInt(3.W))          // funct3
    val memRead    = Input(Bool())
    val memWrite   = Input(Bool())
    val rdata      = Output(UInt(dataWidth.W)) // 读回数据（已拆包扩展）
    val busy       = Output(Bool())            // 正忙 → 流水线暂停
    // ── AXI4-Lite Master ──
    val axi        = new AxiLiteMasterIO(addrWidth, dataWidth)
  })

  // ── 状态定义 ──
  val sIdle :: sReadAr :: sReadR :: sWrite :: sWriteB :: Nil = Enum(5)
  val state = RegInit(sIdle)

  // ── 锁存的请求参数 ──
  val addrReg   = RegInit(0.U(addrWidth.W))
  val wdataReg  = RegInit(0.U(dataWidth.W))
  val accessReg = RegInit(0.U(3.W))
  val rdataReg  = RegInit(0.U(dataWidth.W))
  val awDone    = RegInit(false.B)
  val wDone     = RegInit(false.B)

  // ── 地址偏移与写掩码 ──
  val strbWidth   = dataWidth / 8
  val byteOffset  = addrReg(log2Ceil(strbWidth) - 1, 0)
  val wstrb       = AxiLiteWstrb.genStrb(accessReg, byteOffset, dataWidth)
  val alignedData = AxiLiteWstrb.alignData(wdataReg, byteOffset, dataWidth)

  // ── AXI 通道默认值 ──
  io.axi.aw.valid     := false.B
  io.axi.aw.bits.addr := addrReg
  io.axi.aw.bits.prot := Cat(0.U(1.W), accessReg(1, 0))
  io.axi.w.valid      := false.B
  io.axi.w.bits.data  := alignedData
  io.axi.w.bits.strb  := wstrb
  io.axi.b.ready      := false.B
  io.axi.ar.valid     := false.B
  io.axi.ar.bits.addr := addrReg
  io.axi.ar.bits.prot := Cat(0.U(1.W), accessReg(1, 0))
  io.axi.r.ready      := false.B

  // ── CPU 侧输出 ──
  io.rdata := rdataReg
  io.busy  := (state =/= sIdle)

  // ── 状态机 ──
  switch(state) {
    is(sIdle) {
      when(io.start) {
        addrReg   := io.addr
        wdataReg  := io.wdata
        accessReg := io.accessType
        when(io.memRead) {
          state := sReadAr
        }.elsewhen(io.memWrite) {
          awDone := false.B
          wDone  := false.B
          state  := sWrite
        }
      }
    }

    is(sReadAr) {
      io.axi.ar.valid := true.B
      when(io.axi.ar.fire) {
        state := sReadR
      }
    }

    is(sReadR) {
      io.axi.r.ready := true.B
      when(io.axi.r.fire) {
        rdataReg := AxiLiteLoadUnpack.unpack(io.axi.r.bits.data, byteOffset, accessReg)
        state    := sIdle
      }
    }

    is(sWrite) {
      when(!awDone) {
        io.axi.aw.valid := true.B
        when(io.axi.aw.fire) { awDone := true.B }
      }
      when(!wDone) {
        io.axi.w.valid := true.B
        when(io.axi.w.fire) { wDone := true.B }
      }
      val awComplete = awDone || io.axi.aw.fire
      val wComplete  = wDone  || io.axi.w.fire
      when(awComplete && wComplete) {
        state := sWriteB
      }
    }

    is(sWriteB) {
      io.axi.b.ready := true.B
      when(io.axi.b.fire) {
        state := sIdle
      }
    }
  }
}


// ============================================================================
//  八、AXI4-Lite DPI RAM Slave (AxiLiteDpiRamSlave)
// ============================================================================

/** AXI4-Lite DPI 物理内存 Slave
  *
  * 封装 DPIMem64 BlackBox，提供 AXI4-Lite 从设备接口。
  * DPI-C 函数 pmem_read_64 / pmem_write_64 访问 NEMU 共享内存。
  *
  * 读事务：sIdle → sRResp（1 拍 DPI 读延迟）
  * 写事务：sIdle → sWResp（AW+W 都收到后写入）
  *
  * 地址对齐由 DPI-C 侧完成（pmem_read_64 内部 addr & ~7）。
  */
class AxiLiteDpiRamSlave(addrWidth: Int = 32, dataWidth: Int = 64) extends Module {
  val io = IO(new Bundle {
    val axi = Flipped(new AxiLiteMasterIO(addrWidth, dataWidth))
  })

  val dpiMem = Module(new DPIMem64)
  dpiMem.io.clk := clock
  dpiMem.io.rst := reset.asBool

  // ── 状态定义 ──
  val sIdle :: sRResp :: sWResp :: Nil = Enum(3)
  val state = RegInit(sIdle)

  // ── 写通道锁存 ──
  val awAddr = RegInit(0.U(addrWidth.W))
  val awDone = RegInit(false.B)
  val wData  = RegInit(0.U(dataWidth.W))
  val wStrb  = RegInit(0.U(8.W))
  val wDone  = RegInit(false.B)

  // ── DPIMem64 默认值 ──
  dpiMem.io.ren   := false.B
  dpiMem.io.wen   := false.B
  dpiMem.io.addr  := 0.U
  dpiMem.io.din   := 0.U
  dpiMem.io.wstrb := 0.U

  // ── AXI 默认输出 ──
  io.axi.ar.ready     := false.B
  io.axi.aw.ready     := false.B
  io.axi.w.ready      := false.B
  io.axi.r.valid      := false.B
  io.axi.r.bits.data  := dpiMem.io.dout
  io.axi.r.bits.resp  := AxiLiteResp.OKAY
  io.axi.b.valid      := false.B
  io.axi.b.bits.resp  := AxiLiteResp.OKAY

  // ── 状态机 ──
  switch(state) {
    is(sIdle) {
      // 读优先
      io.axi.ar.ready := true.B
      when(io.axi.ar.fire) {
        dpiMem.io.ren  := true.B
        dpiMem.io.addr := io.axi.ar.bits.addr
        state := sRResp
      }.otherwise {
        io.axi.aw.ready := !awDone
        io.axi.w.ready  := !wDone
        when(io.axi.aw.fire) { awAddr := io.axi.aw.bits.addr; awDone := true.B }
        when(io.axi.w.fire)  { wData  := io.axi.w.bits.data; wStrb := io.axi.w.bits.strb; wDone := true.B }
        val awComplete = awDone || io.axi.aw.fire
        val wComplete  = wDone  || io.axi.w.fire
        when(awComplete && wComplete) {
          dpiMem.io.wen   := true.B
          dpiMem.io.addr  := Mux(io.axi.aw.fire, io.axi.aw.bits.addr, awAddr)
          dpiMem.io.din   := Mux(io.axi.w.fire,  io.axi.w.bits.data,  wData)
          dpiMem.io.wstrb := Mux(io.axi.w.fire,  io.axi.w.bits.strb,  wStrb)
          awDone := false.B
          wDone  := false.B
          state  := sWResp
        }
      }
    }

    is(sRResp) {
      io.axi.r.valid := true.B
      when(io.axi.r.fire) { state := sIdle }
    }

    is(sWResp) {
      io.axi.b.valid := true.B
      when(io.axi.b.fire) { state := sIdle }
    }
  }
}


// ============================================================================
//  九、AXI4-Lite DPI MMIO Slave (AxiLiteDpiMmioSlave)
// ============================================================================

/** AXI4-Lite DPI MMIO Slave
  *
  * 封装 MMIO_Core BlackBox，提供 AXI4-Lite 从设备接口。
  * DPI-C 函数 mmio_read_impl / mmio_write_impl。
  *
  * len 来源：
  *   - 读事务：ARPROT[1:0]（LSU 编码的 accessType）→ 换算
  *   - 写事务：PopCount(WSTRB) → 换算
  *
  * 数据对齐：
  *   - 读：MMIO_Core 返回低字节数据，slave 左移到正确 byte lane
  *   - 写：AXI WDATA 在正确 byte lane，slave 右移到低字节给 MMIO_Core
  */
class AxiLiteDpiMmioSlave(addrWidth: Int = 32, dataWidth: Int = 64) extends Module {
  val io = IO(new Bundle {
    val axi = Flipped(new AxiLiteMasterIO(addrWidth, dataWidth))
  })

  val mmioCore = Module(new MMIO_Core())
  mmioCore.io.clk := clock
  mmioCore.io.rst := reset.asBool

  // ── 状态定义 ──
  val sIdle :: sRResp :: sWResp :: Nil = Enum(3)
  val state = RegInit(sIdle)

  // ── 锁存 ──
  val addrReg = RegInit(0.U(addrWidth.W))
  val awAddr  = RegInit(0.U(addrWidth.W))
  val awDone  = RegInit(false.B)
  val wData   = RegInit(0.U(dataWidth.W))
  val wStrb   = RegInit(0.U(8.W))
  val wDone   = RegInit(false.B)

  // ARPROT[1:0] → len 换算
  def protToLen(prot: UInt): UInt = MuxLookup(prot(1, 0), 1.U(5.W))(Seq(
    "b00".U -> 1.U,  "b01".U -> 2.U,  "b10".U -> 4.U,  "b11".U -> 8.U
  ))

  // PopCount(WSTRB) → len 换算
  def strbToLen(strb: UInt): UInt = MuxLookup(PopCount(strb), 1.U(5.W))(Seq(
    1.U -> 1.U,  2.U -> 2.U,  4.U -> 4.U,  8.U -> 8.U
  ))

  // ── MMIO_Core 默认值 ──
  mmioCore.io.re   := false.B
  mmioCore.io.we   := false.B
  mmioCore.io.addr := 0.U
  mmioCore.io.din  := 0.U
  mmioCore.io.len  := 0.U

  // ── AXI 默认输出 ──
  io.axi.ar.ready     := false.B
  io.axi.aw.ready     := false.B
  io.axi.w.ready      := false.B
  io.axi.r.valid      := false.B
  io.axi.r.bits.data  := 0.U
  io.axi.r.bits.resp  := AxiLiteResp.OKAY
  io.axi.b.valid      := false.B
  io.axi.b.bits.resp  := AxiLiteResp.OKAY

  // ── 状态机 ──
  switch(state) {
    is(sIdle) {
      io.axi.ar.ready := true.B
      when(io.axi.ar.fire) {
        addrReg := io.axi.ar.bits.addr
        // 发起 MMIO 读
        mmioCore.io.re   := true.B
        mmioCore.io.addr := io.axi.ar.bits.addr
        mmioCore.io.len  := protToLen(io.axi.ar.bits.prot)
        state := sRResp
      }.otherwise {
        io.axi.aw.ready := !awDone
        io.axi.w.ready  := !wDone
        when(io.axi.aw.fire) { awAddr := io.axi.aw.bits.addr; awDone := true.B }
        when(io.axi.w.fire)  { wData  := io.axi.w.bits.data; wStrb := io.axi.w.bits.strb; wDone := true.B }

        val awComplete = awDone || io.axi.aw.fire
        val wComplete  = wDone  || io.axi.w.fire
        when(awComplete && wComplete) {
          val finalAddr = Mux(io.axi.aw.fire, io.axi.aw.bits.addr, awAddr)
          val finalData = Mux(io.axi.w.fire,  io.axi.w.bits.data,  wData)
          val finalStrb = Mux(io.axi.w.fire,  io.axi.w.bits.strb,  wStrb)
          // WDATA 在 byte lane 位置 → 右移到低字节给 MMIO_Core
          val byteOff     = finalAddr(2, 0)
          val shiftedData = (finalData >> (byteOff << 3))(dataWidth - 1, 0)
          mmioCore.io.we   := true.B
          mmioCore.io.addr := finalAddr
          mmioCore.io.din  := shiftedData
          mmioCore.io.len  := strbToLen(finalStrb)
          awDone := false.B
          wDone  := false.B
          state  := sWResp
        }
      }
    }

    is(sRResp) {
      // MMIO_Core dout 在低字节 → 左移到正确 byte lane
      val byteOff = addrReg(2, 0)
      io.axi.r.valid     := true.B
      io.axi.r.bits.data := (mmioCore.io.dout << (byteOff << 3))(dataWidth - 1, 0)
      when(io.axi.r.fire) { state := sIdle }
    }

    is(sWResp) {
      io.axi.b.valid := true.B
      when(io.axi.b.fire) { state := sIdle }
    }
  }
}


// ============================================================================
//  十、AXI4-Lite 地址译码交叉开关 (AxiLiteCrossbar)
// ============================================================================

/** AXI4-Lite 从设备地址区域描述
  *
  * 用于 AxiLiteCrossbar 的地址译码配置。
  *
  * @param baseAddr 该 slave 的起始地址（含）
  * @param size     地址空间大小（字节数）
  *
  * 匹配规则：baseAddr <= addr < baseAddr + size
  */
case class AxiLiteSlaveRange(baseAddr: Long, size: Long)

/** AXI4-Lite 1-to-N 地址译码交叉开关
  *
  * 功能：
  *   - 接收 1 个 master 的 AXI4-Lite 请求
  *   - 根据地址范围路由到 N 个 slave 之一
  *   - 将被选中 slave 的响应返回给 master
  *   - 地址未命中任何 slave 时返回 DECERR
  *
  * @param addrWidth 地址位宽
  * @param dataWidth 数据位宽
  * @param ranges    每个 slave 的地址范围（有序）
  */
class AxiLiteCrossbar(
    addrWidth: Int,
    dataWidth: Int,
    ranges:    Seq[AxiLiteSlaveRange]
) extends Module {

  val numSlaves = ranges.length

  val io = IO(new Bundle {
    val master = Flipped(new AxiLiteMasterIO(addrWidth, dataWidth))
    val slaves = Vec(numSlaves, new AxiLiteMasterIO(addrWidth, dataWidth))
  })

  // ── 地址译码函数 ──
  def addrMatch(addr: UInt, range: AxiLiteSlaveRange): Bool = {
    (addr >= range.baseAddr.U) && (addr < (range.baseAddr + range.size).U)
  }

  // ──────────────────────────────────────────────────
  //  AR 通道路由
  // ──────────────────────────────────────────────────
  val arSel = VecInit(ranges.map(r => addrMatch(io.master.ar.bits.addr, r)))
  val arHit = arSel.asUInt.orR

  // 锁存 AR 路由结果，用于 R 通道回送
  val rSelReg = RegInit(0.U(log2Ceil(numSlaves + 1).W))
  val rHitReg = RegInit(false.B)

  io.master.ar.ready := false.B
  for (i <- 0 until numSlaves) {
    io.slaves(i).ar.valid     := false.B
    io.slaves(i).ar.bits.addr := io.master.ar.bits.addr
    io.slaves(i).ar.bits.prot := io.master.ar.bits.prot
    when(arSel(i)) {
      io.slaves(i).ar.valid := io.master.ar.valid
      io.master.ar.ready    := io.slaves(i).ar.ready
    }
  }
  // 地址未命中：直接吞掉请求
  when(!arHit && io.master.ar.valid) {
    io.master.ar.ready := true.B
  }
  // 锁存路由供 R 通道使用
  when(io.master.ar.fire) {
    rHitReg := arHit
    rSelReg := OHToUInt(arSel.asUInt)
  }

  // ──────────────────────────────────────────────────
  //  R 通道路由（返回方向）
  // ──────────────────────────────────────────────────
  io.master.r.valid     := false.B
  io.master.r.bits.data := 0.U
  io.master.r.bits.resp := AxiLiteResp.OKAY
  for (i <- 0 until numSlaves) {
    io.slaves(i).r.ready := false.B
    when(rSelReg === i.U && rHitReg) {
      io.master.r.valid     := io.slaves(i).r.valid
      io.master.r.bits      := io.slaves(i).r.bits
      io.slaves(i).r.ready  := io.master.r.ready
    }
  }
  when(!rHitReg) {
    io.master.r.valid     := true.B
    io.master.r.bits.resp := AxiLiteResp.DECERR
  }

  // ──────────────────────────────────────────────────
  //  AW 通道路由
  // ──────────────────────────────────────────────────
  val awSel = VecInit(ranges.map(r => addrMatch(io.master.aw.bits.addr, r)))
  val awHit = awSel.asUInt.orR

  // 锁存 AW 路由结果，用于 B 通道回送
  val bSelReg = RegInit(0.U(log2Ceil(numSlaves + 1).W))
  val bHitReg = RegInit(false.B)

  io.master.aw.ready := false.B
  for (i <- 0 until numSlaves) {
    io.slaves(i).aw.valid     := false.B
    io.slaves(i).aw.bits.addr := io.master.aw.bits.addr
    io.slaves(i).aw.bits.prot := io.master.aw.bits.prot
    when(awSel(i)) {
      io.slaves(i).aw.valid := io.master.aw.valid
      io.master.aw.ready    := io.slaves(i).aw.ready
    }
  }
  when(!awHit && io.master.aw.valid) {
    io.master.aw.ready := true.B
  }
  when(io.master.aw.fire) {
    bHitReg := awHit
    bSelReg := OHToUInt(awSel.asUInt)
  }

  // ──────────────────────────────────────────────────
  //  W 通道路由（使用 AW 译码结果 — LSU 同时发送 AW+W）
  // ──────────────────────────────────────────────────
  io.master.w.ready := false.B
  for (i <- 0 until numSlaves) {
    io.slaves(i).w.valid     := false.B
    io.slaves(i).w.bits.data := io.master.w.bits.data
    io.slaves(i).w.bits.strb := io.master.w.bits.strb
    when(awSel(i)) {
      io.slaves(i).w.valid := io.master.w.valid
      io.master.w.ready    := io.slaves(i).w.ready
    }
  }
  when(!awHit && io.master.w.valid) {
    io.master.w.ready := true.B
  }

  // ──────────────────────────────────────────────────
  //  B 通道路由（返回方向）
  // ──────────────────────────────────────────────────
  io.master.b.valid     := false.B
  io.master.b.bits.resp := AxiLiteResp.OKAY
  for (i <- 0 until numSlaves) {
    io.slaves(i).b.ready := false.B
    when(bSelReg === i.U && bHitReg) {
      io.master.b.valid     := io.slaves(i).b.valid
      io.master.b.bits      := io.slaves(i).b.bits
      io.slaves(i).b.ready  := io.master.b.ready
    }
  }
  when(!bHitReg) {
    io.master.b.valid     := true.B
    io.master.b.bits.resp := AxiLiteResp.DECERR
  }
}
