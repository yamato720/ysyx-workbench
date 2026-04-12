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

/** 取指 AXI4-Lite Master 适配器
  *
  * 将 CPU 取指请求转换为 AXI4-Lite 读事务。
  *
  * 功能：
  *   - 接收来自 PC_Ctrl / InsBuffer 的取指请求（PC 地址）
  *   - 发起 AXI4-Lite AR 通道握手
  *   - 等待 R 通道返回整拍数据
  *   - 从整拍数据中提取 32-bit 指令
  *
  * 限制（AXI4-Lite 固有）：
  *   - 无 burst：每条指令需要独立的一次读事务
  *   - 最多 outstanding 1 笔读
  *   - 若需高性能预取/cache line fill，应使用 AXI4 Full
  *
  * 状态机：
  *   sIdle   → 等待取指请求
  *   sArWait → 已发 ARVALID，等待 ARREADY 握手
  *   sRWait  → AR 握手完成，等待 RVALID + RDATA
  *   sDone   → 指令已就绪，等待 CPU 取走
  *
  * @param addrWidth 地址位宽（默认 32）
  * @param dataWidth 总线数据位宽（默认 64）
  */
class IFetchAXIAdapter(addrWidth: Int = 32, dataWidth: Int = 64) extends Module {
  val io = IO(new Bundle {
    // ── CPU 侧接口 ──
    val pc         = Input(UInt(addrWidth.W))  // 当前需要取指的 PC 地址
    val fetchEn    = Input(Bool())             // 取指请求使能（脉冲或电平）
    val inst       = Output(UInt(32.W))        // 取回的 32-bit 指令
    val instValid  = Output(Bool())            // 指令有效标志
    val busy       = Output(Bool())            // 适配器正忙，不能接受新请求

    // ── AXI4-Lite Master 端口 ──
    val axi        = new AxiLiteMasterIO(addrWidth, dataWidth)
  })

  // ── 状态定义 ──
  val sIdle :: sArWait :: sRWait :: sDone :: Nil = Enum(4)
  val state = RegInit(sIdle)

  // ── 内部寄存器 ──
  val instReg   = RegInit(0.U(32.W))        // 锁存取回的指令
  val pcReg     = RegInit(0.U(addrWidth.W)) // 锁存请求的 PC（用于从整拍数据中提取）
  val respError = RegInit(false.B)          // 记录是否收到错误响应

  // ── AXI 通道默认值 ──
  // 取指适配器是只读的，写通道永远关闭
  io.axi.aw.valid      := false.B
  io.axi.aw.bits.addr  := 0.U
  io.axi.aw.bits.prot  := 0.U
  io.axi.w.valid       := false.B
  io.axi.w.bits.data   := 0.U
  io.axi.w.bits.strb   := 0.U
  io.axi.b.ready       := false.B

  // AR 通道默认值
  io.axi.ar.valid      := false.B
  io.axi.ar.bits.addr  := 0.U
  io.axi.ar.bits.prot  := "b100".U  // prot[2]=1 表示指令访问

  // R 通道默认值
  io.axi.r.ready       := false.B

  // CPU 侧输出
  io.inst      := instReg
  io.instValid := (state === sDone)
  io.busy      := (state =/= sIdle)

  // ── 状态机 ──
  switch(state) {
    is(sIdle) {
      // 空闲态：等待 CPU 发起取指请求
      when(io.fetchEn) {
        pcReg := io.pc
        state := sArWait
      }
    }

    is(sArWait) {
      // 发送读地址：置 ARVALID，给出对齐后的地址
      // 64-bit 总线下，地址低 3 位清零（8 字节对齐）
      io.axi.ar.valid     := true.B
      io.axi.ar.bits.addr := pcReg & ~((dataWidth / 8 - 1).U(addrWidth.W))

      // 等 slave 拉高 ARREADY，地址握手完成
      when(io.axi.ar.fire) {
        state := sRWait
      }
    }

    is(sRWait) {
      // 等待读数据：拉高 RREADY，准备接收
      io.axi.r.ready := true.B

      when(io.axi.r.fire) {
        // 读数据握手完成：从整拍 RDATA 中按 PC 低位提取 32-bit 指令
        // 对 64-bit 总线，PC[2] 决定取高 32 位还是低 32 位
        val byteOffset = pcReg(log2Ceil(dataWidth / 8) - 1, 0)
        val shifted    = (io.axi.r.bits.data >> (byteOffset << 3))(31, 0)
        instReg   := shifted
        respError := (io.axi.r.bits.resp =/= AxiLiteResp.OKAY)
        state     := sDone
      }
    }

    is(sDone) {
      // 指令就绪：等待 CPU 取走后回到空闲
      // CPU 应在读到 instValid=1 后，下一拍不再保持 fetchEn
      when(!io.fetchEn) {
        state := sIdle
      }
    }
  }
}


// ============================================================================
//  七、访存 AXI4-Lite 适配器 (LSUAXIAdapter)
// ============================================================================

/** Load/Store Unit AXI4-Lite Master 适配器
  *
  * 将 CPU 的 load/store 请求转换为 AXI4-Lite 读写事务。
  *
  * 功能：
  *   - Load：发起 AR+R 读事务，对 RDATA 做拆包 + 符号/零扩展
  *   - Store：发起 AW+W+B 写事务，自动生成 WSTRB 和对齐 WDATA
  *
  * 读事务状态机：
  *   sIdle → sReadAr → sReadR → sDone
  *
  * 写事务状态机：
  *   sIdle → sWrite → sWriteB → sDone
  *
  *   写事务中 AW 和 W 同时发出（同拍 valid），
  *   分别记录各自的握手完成状态 (aw_done / w_done)，
  *   两者都完成后才转去等 B 响应。
  *
  * @param addrWidth 地址位宽（默认 32）
  * @param dataWidth 总线数据位宽（默认 64）
  */
class LSUAXIAdapter(addrWidth: Int = 32, dataWidth: Int = 64) extends Module {
  val io = IO(new Bundle {
    // ── CPU 侧接口 ──
    val addr       = Input(UInt(addrWidth.W))  // 访存地址（来自 ALU 计算结果）
    val wdata      = Input(UInt(dataWidth.W))  // 写数据（来自 rs2 寄存器）
    val accessType = Input(UInt(3.W))          // funct3：指示 load/store 类型
    val memRead    = Input(Bool())             // load 请求
    val memWrite   = Input(Bool())             // store 请求
    val reqValid   = Input(Bool())             // 请求有效使能

    val rdata      = Output(UInt(dataWidth.W)) // 读回数据（已拆包 + 扩展）
    val respValid  = Output(Bool())            // 响应有效标志
    val busy       = Output(Bool())            // 适配器正忙

    // ── AXI4-Lite Master 端口 ──
    val axi        = new AxiLiteMasterIO(addrWidth, dataWidth)
  })

  // ── 状态定义 ──
  // 读写共用 sIdle 和 sDone，中间态各自独立
  val sIdle    = 0.U(3.W)          // 0: 空闲
  val sReadAr  = 1.U(3.W)        // 1: 读 — 等 AR 握手
  val sReadR   = 2.U(3.W)        // 2: 读 — 等 R 数据
  val sWrite   = 3.U(3.W)        // 3: 写 — 发 AW+W，等握手
  val sWriteB  = 4.U(3.W)        // 4: 写 — 等 B 响应
  val sDone    = 5.U(3.W)        // 5: 事务完成

  val state = RegInit(0.U(3.W))  // sIdle = 0

  // ── 内部寄存器 ──
  val addrReg    = RegInit(0.U(addrWidth.W))  // 锁存的访存地址
  val wdataReg   = RegInit(0.U(dataWidth.W))  // 锁存的写数据
  val accessReg  = RegInit(0.U(3.W))          // 锁存的 funct3
  val rdataReg   = RegInit(0.U(dataWidth.W))  // 读回的拆包后数据
  val awDone     = RegInit(false.B)           // AW 通道已握手标志
  val wDone      = RegInit(false.B)           // W  通道已握手标志

  // ── 地址对齐与偏移 ──
  val strbWidth  = dataWidth / 8
  val alignMask  = (~((strbWidth - 1).U(addrWidth.W))).asUInt
  val alignedAddr = addrReg & alignMask               // 按总线宽度对齐的地址
  val byteOffset  = addrReg(log2Ceil(strbWidth) - 1, 0) // 地址低位偏移

  // ── 生成写掩码和对齐写数据 ──
  val wstrb = AxiLiteWstrb.genStrb(accessReg, byteOffset, dataWidth)
  val wdata = AxiLiteWstrb.alignData(wdataReg, byteOffset, dataWidth)

  // ── AXI 通道默认值 ──
  io.axi.aw.valid     := false.B
  io.axi.aw.bits.addr := alignedAddr
  io.axi.aw.bits.prot := 0.U      // 数据访问，非特权，安全
  io.axi.w.valid      := false.B
  io.axi.w.bits.data  := wdata
  io.axi.w.bits.strb  := wstrb
  io.axi.b.ready      := false.B
  io.axi.ar.valid     := false.B
  io.axi.ar.bits.addr := alignedAddr
  io.axi.ar.bits.prot := 0.U
  io.axi.r.ready      := false.B

  // CPU 侧输出
  io.rdata     := rdataReg
  io.respValid := (state === sDone)
  io.busy      := (state =/= 0.U)  // sIdle = 0

  // ── 状态机 ──
  switch(state) {
    // ────────────────────────────────────────────────
    // 空闲态：根据请求类型决定进入读或写路径
    // ────────────────────────────────────────────────
    is(sIdle) {
      when(io.reqValid) {
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

    // ────────────────────────────────────────────────
    // 读路径 — 第一阶段：发送读地址
    // ────────────────────────────────────────────────
    is(sReadAr) {
      io.axi.ar.valid     := true.B
      io.axi.ar.bits.addr := alignedAddr

      when(io.axi.ar.fire) {
        state := sReadR
      }
    }

    // ────────────────────────────────────────────────
    // 读路径 — 第二阶段：等待读数据
    // ────────────────────────────────────────────────
    is(sReadR) {
      io.axi.r.ready := true.B

      when(io.axi.r.fire) {
        // 用 AxiLiteLoadUnpack 做拆包 + 扩展
        rdataReg := AxiLiteLoadUnpack.unpack(io.axi.r.bits.data, byteOffset, accessReg)
        state    := sDone
      }
    }

    // ────────────────────────────────────────────────
    // 写路径 — 发送 AW + W（可并行握手）
    // ────────────────────────────────────────────────
    is(sWrite) {
      // AW 通道：如果还没握手完成，就保持 valid
      when(!awDone) {
        io.axi.aw.valid := true.B
        when(io.axi.aw.fire) {
          awDone := true.B
        }
      }

      // W 通道：如果还没握手完成，就保持 valid
      when(!wDone) {
        io.axi.w.valid := true.B
        when(io.axi.w.fire) {
          wDone := true.B
        }
      }

      // 当 AW 和 W 都完成握手（包括本拍刚完成的），转去等 B 响应
      val awComplete = awDone || io.axi.aw.fire
      val wComplete  = wDone  || io.axi.w.fire
      when(awComplete && wComplete) {
        state := sWriteB
      }
    }

    // ────────────────────────────────────────────────
    // 写路径 — 等待写响应
    // ────────────────────────────────────────────────
    is(sWriteB) {
      io.axi.b.ready := true.B

      when(io.axi.b.fire) {
        // 可在此检查 io.axi.b.bits.resp 判断写是否成功
        state := sDone
      }
    }

    // ────────────────────────────────────────────────
    // 完成态：等待 CPU 撤销请求后回到空闲
    // ────────────────────────────────────────────────
    is(sDone) {
      when(!io.reqValid) {
        state := 0.U  // sIdle
      }
    }
  }
}


// ============================================================================
//  八、AXI4-Lite RAM Slave
// ============================================================================

/** AXI4-Lite RAM Slave
  *
  * 简单的单周期 AXI4-Lite 内存 slave，内部使用 Chisel SyncReadMem。
  * 用于替代当前的 PhysicalMemory / dataCacheL1 / insCacheL1 字节口架构。
  *
  * 特性：
  *   - 支持 AXI4-Lite 读写事务
  *   - 写操作按 WSTRB 做字节级写入
  *   - 读操作返回整拍数据（master 侧自行拆包）
  *   - 单周期响应（组合逻辑 ready 或 1-cycle 延迟）
  *
  * 状态机（简化实现）：
  *   读：sIdle → sRRead → sRResp
  *   写：sIdle → (接收 AW+W) → sWResp
  *
  * @param addrWidth   地址位宽
  * @param dataWidth   数据位宽
  * @param memSizeBytes 内存大小（字节数），决定 SyncReadMem 的深度
  */
class AxiLiteRamSlave(
  addrWidth: Int = 32,
  dataWidth: Int = 64,
  memSizeBytes: Int = 128 * 1024 * 1024  // 默认 128 MB（匹配当前 pmem）
) extends Module {
  val io = IO(new Bundle {
    val axi = Flipped(new AxiLiteMasterIO(addrWidth, dataWidth))
  })

  val bytesPerBeat = dataWidth / 8                     // 每拍字节数（64-bit → 8）
  val depth        = memSizeBytes / bytesPerBeat        // SyncReadMem 深度（以 beat 为单位）

  // ── 内部存储 ──
  // SyncReadMem：同步读（读地址注册后下一拍出数据），组合写
  // 每个条目是一个 Vec[UInt(8.W)]，支持按字节写入（对应 WSTRB）
  val mem = SyncReadMem(depth, Vec(bytesPerBeat, UInt(8.W)))

  // ── 状态定义 ──
  val sIdle :: sRResp :: sWResp :: Nil = Enum(3)
  val state = RegInit(sIdle)

  // ── 内部寄存器 ──
  val rdata   = RegInit(VecInit(Seq.fill(bytesPerBeat)(0.U(8.W)))) // 读回的数据
  val awAddr  = RegInit(0.U(addrWidth.W))   // 锁存的写地址
  val awValid = RegInit(false.B)            // AW 已接收但尚未处理
  val wData   = RegInit(VecInit(Seq.fill(bytesPerBeat)(0.U(8.W)))) // 锁存的写数据
  val wStrb   = RegInit(0.U(bytesPerBeat.W)) // 锁存的写掩码
  val wValid  = RegInit(false.B)             // W  已接收但尚未处理

  // ── 默认输出 ──
  io.axi.aw.ready     := false.B
  io.axi.w.ready      := false.B
  io.axi.b.valid      := false.B
  io.axi.b.bits.resp  := AxiLiteResp.OKAY
  io.axi.ar.ready     := false.B
  io.axi.r.valid      := false.B
  io.axi.r.bits.data  := Cat(rdata.reverse)  // Vec[UInt(8.W)] → 拼成整拍 UInt
  io.axi.r.bits.resp  := AxiLiteResp.OKAY

  // ── 地址转换辅助函数 ──
  // 将字节地址转换为 SyncReadMem 的索引（除以 bytesPerBeat）
  def addrToIdx(addr: UInt): UInt = (addr >> log2Ceil(bytesPerBeat).U).asUInt

  // ── 状态机 ──
  switch(state) {
    is(sIdle) {
      // ── 接受读地址 ──
      // 读优先：如果 AR 和 AW 同时到来，先处理读
      io.axi.ar.ready := true.B

      when(io.axi.ar.fire) {
        // SyncReadMem 的读在此时注册地址，下一拍输出数据
        rdata := mem.read(addrToIdx(io.axi.ar.bits.addr))
        state := sRResp
      }.otherwise {
        // ── 接受写地址和写数据 ──
        // AW 和 W 可以分别到达，也可以同拍到达
        io.axi.aw.ready := !awValid  // 还没收到 AW 时才 ready
        io.axi.w.ready  := !wValid   // 还没收到 W  时才 ready

        when(io.axi.aw.fire) {
          awAddr  := io.axi.aw.bits.addr
          awValid := true.B
        }
        when(io.axi.w.fire) {
          for (i <- 0 until bytesPerBeat) {
            wData(i) := io.axi.w.bits.data(i * 8 + 7, i * 8)
          }
          wStrb  := io.axi.w.bits.strb
          wValid := true.B
        }

        // 当 AW 和 W 都已收到（包括本拍刚收到），执行写入
        val awReady = awValid || io.axi.aw.fire
        val wReady  = wValid  || io.axi.w.fire
        when(awReady && wReady) {
          // 使用最新的地址和数据（可能来自本拍 fire，也可能来自寄存器）
          val finalAddr = Mux(io.axi.aw.fire, io.axi.aw.bits.addr, awAddr)
          val finalStrb = Mux(io.axi.w.fire, io.axi.w.bits.strb, wStrb)
          val finalData = Wire(Vec(bytesPerBeat, UInt(8.W)))
          for (i <- 0 until bytesPerBeat) {
            finalData(i) := Mux(io.axi.w.fire,
              io.axi.w.bits.data(i * 8 + 7, i * 8),
              wData(i))
          }

          // 按 WSTRB 做字节级写入
          val writeMask = VecInit((0 until bytesPerBeat).map(i => finalStrb(i)))
          mem.write(addrToIdx(finalAddr), finalData, writeMask)

          // 清空锁存状态，准备返回 B 响应
          awValid := false.B
          wValid  := false.B
          state   := sWResp
        }
      }
    }

    is(sRResp) {
      // 读响应：SyncReadMem 数据已就绪，发送 R 通道
      io.axi.r.valid     := true.B
      io.axi.r.bits.data := Cat(rdata.reverse)
      io.axi.r.bits.resp := AxiLiteResp.OKAY

      when(io.axi.r.fire) {
        state := sIdle
      }
    }

    is(sWResp) {
      // 写响应：发送 B 通道
      io.axi.b.valid     := true.B
      io.axi.b.bits.resp := AxiLiteResp.OKAY

      when(io.axi.b.fire) {
        state := sIdle
      }
    }
  }
}


// ============================================================================
//  九、AXI4-Lite 地址译码交叉开关 (AxiLiteCrossbar)
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
  * 替代当前的 IO_Distribute 模块。
  *
  * 功能：
  *   - 接收 1 个 master 的 AXI4-Lite 请求
  *   - 根据地址范围路由到 N 个 slave 之一
  *   - 将被选中 slave 的响应返回给 master
  *   - 地址未命中任何 slave 时返回 DECERR
  *
  * 当前 NPC 的地址空间可直接复用：
  *   | 区域            | 地址范围                    |
  *   | physical memory | 0x8000_0000 - 0x8FFF_FFFF   |
  *   | serial          | 0xA000_03F8 - 0xA000_03FF   |
  *   | rtc             | 0xA000_0048 - 0xA000_004F   |
  *   | vgactrl         | 0xA000_0100 - 0xA000_0107   |
  *   | vmem            | 0xA100_0000 - 0xA107_52FF   |
  *   | keyboard        | 0xA000_0060 - 0xA000_0063   |
  *   | audio           | 0xA000_0200 - 0xA000_0217   |
  *   | audio-sbuf      | 0xA120_0000 - 0xA120_FFFF   |
  *
  * @param addrWidth   地址位宽
  * @param dataWidth   数据位宽
  * @param slaveRanges 各 slave 的地址范围列表，顺序对应 slaves 端口顺序
  */
class AxiLiteCrossbar(
  addrWidth: Int,
  dataWidth: Int,
  slaveRanges: Seq[AxiLiteSlaveRange]
) extends Module {
  val numSlaves = slaveRanges.length

  val io = IO(new Bundle {
    // ── Master 侧（接收来自 CPU 的请求）──
    // 使用 Flipped：因为 crossbar 对 master 来说是 slave 角色
    val master = Flipped(new AxiLiteMasterIO(addrWidth, dataWidth))

    // ── Slave 侧（转发到各个从设备）──
    // 对 slave 来说，crossbar 是 master 角色
    val slaves = Vec(numSlaves, new AxiLiteMasterIO(addrWidth, dataWidth))
  })

  // ── 地址匹配函数 ──
  // 检查给定地址是否落在某个 slave 的地址范围内
  def addrMatch(addr: UInt, range: AxiLiteSlaveRange): Bool = {
    (addr >= range.baseAddr.U) && (addr < (range.baseAddr + range.size).U)
  }

  // ── 读地址译码 ──
  // 根据 AR 通道的地址确定目标 slave
  val arSel = VecInit(slaveRanges.map(r => addrMatch(io.master.ar.bits.addr, r)))
  val arSelIdx = OHToUInt(arSel)
  val arHit = arSel.asUInt.orR  // 是否命中任何 slave

  // ── 写地址译码 ──
  // 根据 AW 通道的地址确定目标 slave
  val awSel = VecInit(slaveRanges.map(r => addrMatch(io.master.aw.bits.addr, r)))
  val awSelIdx = OHToUInt(awSel)
  val awHit = awSel.asUInt.orR

  // ── 锁存选中的 slave 索引 ──
  // 在事务期间保持 slave 选择不变（防止地址变化导致路由切换）
  val rSelReg  = RegInit(0.U(log2Ceil(numSlaves + 1).W))
  val rHitReg  = RegInit(false.B)
  val bSelReg  = RegInit(0.U(log2Ceil(numSlaves + 1).W))
  val bHitReg  = RegInit(false.B)

  // 读事务：AR 握手时锁存
  when(io.master.ar.fire) {
    rSelReg := arSelIdx
    rHitReg := arHit
  }

  // 写事务：AW 握手时锁存
  when(io.master.aw.fire) {
    bSelReg := awSelIdx
    bHitReg := awHit
  }

  // ── 默认：所有 slave 端口无效 ──
  for (i <- 0 until numSlaves) {
    io.slaves(i).ar.valid     := false.B
    io.slaves(i).ar.bits      := io.master.ar.bits
    io.slaves(i).aw.valid     := false.B
    io.slaves(i).aw.bits      := io.master.aw.bits
    io.slaves(i).w.valid      := false.B
    io.slaves(i).w.bits       := io.master.w.bits
    io.slaves(i).b.ready      := false.B
    io.slaves(i).r.ready      := false.B
  }

  // ── AR 通道路由 ──
  // 将 master 的 AR 请求转发到匹配的 slave
  io.master.ar.ready := false.B
  for (i <- 0 until numSlaves) {
    when(arSel(i)) {
      io.slaves(i).ar.valid := io.master.ar.valid
      io.master.ar.ready    := io.slaves(i).ar.ready
    }
  }
  // 地址未命中：直接接受请求（后续返回 DECERR）
  when(!arHit && io.master.ar.valid) {
    io.master.ar.ready := true.B
  }

  // ── R 通道路由 ──
  // 将选中 slave 的 R 响应返回给 master
  io.master.r.valid     := false.B
  io.master.r.bits.data := 0.U
  io.master.r.bits.resp := AxiLiteResp.DECERR  // 默认 DECERR
  for (i <- 0 until numSlaves) {
    when(rSelReg === i.U && rHitReg) {
      io.master.r.valid     := io.slaves(i).r.valid
      io.master.r.bits      := io.slaves(i).r.bits
      io.slaves(i).r.ready  := io.master.r.ready
    }
  }
  // 未命中时：生成假的 R 响应（data=0, resp=DECERR）
  when(!rHitReg) {
    io.master.r.valid     := true.B
    io.master.r.bits.data := 0.U
    io.master.r.bits.resp := AxiLiteResp.DECERR
  }

  // ── AW 通道路由 ──
  io.master.aw.ready := false.B
  for (i <- 0 until numSlaves) {
    when(awSel(i)) {
      io.slaves(i).aw.valid := io.master.aw.valid
      io.master.aw.ready    := io.slaves(i).aw.ready
    }
  }
  when(!awHit && io.master.aw.valid) {
    io.master.aw.ready := true.B
  }

  // ── W 通道路由 ──
  // W 通道使用 AW 译码结果（写数据跟随写地址路由）
  io.master.w.ready := false.B
  for (i <- 0 until numSlaves) {
    when(awSel(i)) {
      io.slaves(i).w.valid := io.master.w.valid
      io.master.w.ready    := io.slaves(i).w.ready
    }
  }
  when(!awHit && io.master.w.valid) {
    io.master.w.ready := true.B
  }

  // ── B 通道路由 ──
  io.master.b.valid     := false.B
  io.master.b.bits.resp := AxiLiteResp.DECERR
  for (i <- 0 until numSlaves) {
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
