package scpu.protocol

import chisel3._
import chisel3.util._
import scpu.ipdpishell.{DPIMem64, MMIO_Core}

// ============================================================================
//  AXI4-Lite / AXI4-Full 共享互连协议
//
//  本文件为 IF 和 MEM 两级共同使用的 AXI 基础设施，包括：
//    1. 响应码常量         (AxiLiteResp)
//    2. 五通道载荷          (AxiLiteAddr / AxiLiteWriteData / AxiLiteWriteResp / AxiLiteReadData)
//    3. 主从设备 IO         (AxiLiteMasterIO / AxiLiteSlaveIO)
//    4. AXI4-Full 端口与桥接器 (Axi4FullMasterIO / AxiLiteToAxi4Full)
//    5. Lite 2-to-1 仲裁器  (AxiLiteArbiter2)
//    6. AXI DPI RAM/MMIO 从设备
//    7. AXI 地址译码交叉开关 (AxiLiteCrossbar)
//
//  IFetchAXIAdapter 放在 frontend/fetch/，LSUAXIAdapter 与 load/store
//  对齐逻辑放在 backend/memory/。这样可以从五级流水线的阶段位置看到
//  请求的来源，同时将两级共享的传输协议集中在这里。
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
//  二、五通道载荷 Bundle
// ============================================================================

/** AW / AR 通道共用的地址载荷
  *
  * @param addrWidth 地址位宽（通常为 32）
  *
  * 包含：
  *   - addr : 字节地址
  *   - size : 本次传输的字节数编码（AXI 语义：0/1/2/3 分别为 1/2/4/8 字节）
  *   - prot : 保护类型（3-bit），第一版可固定接 0
  *            bit[0] = 特权（1）/非特权（0）
  *            bit[1] = 非安全（1）/安全（0）
  *            bit[2] = 指令（1）/数据（0）
  */
class AxiLiteAddr(val addrWidth: Int) extends Bundle {
  val addr = UInt(addrWidth.W)
  val size = UInt(3.W)
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
  * 从设备在同时接收 AW 和 W 后，才能返回 B 响应。
  */
class AxiLiteWriteResp extends Bundle {
  val resp = UInt(2.W)
}

/** R 通道：读数据载荷
  *
  * @param dataWidth 数据位宽
  *
  * 包含：
  *   - data : 整拍读数据，主设备需根据地址低位自行截取
  *   - resp : 读响应码，取值参见 AxiLiteResp
  */
class AxiLiteReadData(val dataWidth: Int) extends Bundle {
  val data = UInt(dataWidth.W)
  val resp = UInt(2.W)
}


// ============================================================================
//  三、主从设备端口
// ============================================================================

/** AXI4-Lite 主设备侧完整端口
  *
  * @param addrWidth 地址位宽
  * @param dataWidth 数据位宽
  *
  * 五个通道的方向按主设备视角定义：
  *   - aw : 主设备 -> 从设备（Decoupled = valid/ready + bits）
  *   - w  : 主设备 -> 从设备
  *   - b  : 从设备 -> 主设备（Flipped Decoupled）
  *   - ar : 主设备 -> 从设备
  *   - r  : 从设备 -> 主设备
  *
  * 使用方式：
  *   - 主设备模块中：val io = IO(new AxiLiteMasterIO(...))
  *   - 从设备模块中：val io = IO(Flipped(new AxiLiteMasterIO(...)))
  *
  * Chisel 的 Decoupled 提供 valid/ready/bits 三部分：
  *   - valid : Output（源端发起，表示载荷有效）
  *   - ready : Input （目标端确认，表示可以接收）
  *   - bits  : Output（载荷数据）
  *
  * Flipped(Decoupled(...)) 反转方向，变成接收端。
  */
class AxiLiteMasterIO(val addrWidth: Int, val dataWidth: Int) extends Bundle {
  // ── 写地址通道 (AW) ──
  // 主设备提供写目标地址，从设备通过 AWREADY 表示接受
  val aw = Decoupled(new AxiLiteAddr(addrWidth))

  // ── 写数据通道 (W) ──
  // 主设备提供写数据和字节掩码，与 AW 独立握手
  val w  = Decoupled(new AxiLiteWriteData(dataWidth))

  // ── 写响应通道 (B) ──
  // 从设备在 AW + W 都完成后返回写状态码
  val b  = Flipped(Decoupled(new AxiLiteWriteResp))

  // ── 读地址通道 (AR) ──
  // 主设备提供读目标地址
  val ar = Decoupled(new AxiLiteAddr(addrWidth))

  // ── 读数据通道 (R) ──
  // 从设备在 AR 握手后返回读数据和状态码
  val r  = Flipped(Decoupled(new AxiLiteReadData(dataWidth)))
}
// 它相当于 C 语言的嵌套结构体，可从外部逐层访问。
// 方向已由 Decoupled 和 Flipped 定义，使用时可直接访问 aw.valid、aw.bits.addr 等字段。
// Decoupled 可理解为 IO 输出，Flipped 反转方向并成为输入。因此主设备直接使用
// AxiLiteMasterIO，从设备使用 Flipped(AxiLiteMasterIO) 接收信号。


// ============================================================================
//  四、AXI4-Full 主设备端口、AXI-Lite 桥接与仲裁
// ============================================================================

/** AXI4 burst 类型。NPC 目前仅产生单拍 INCR 事务。 */
object Axi4FullBurst {
  val Fixed = 0.U(2.W)
  val Incr  = 1.U(2.W)
  val Wrap  = 2.U(2.W)
}

/** AXI4-Full 的 AW / AR 载荷。
  *
  * 字段宽度与 ysyxSoC 的 Rocket Chip AXI4Bundle 保持一致：
  * id, addr, len, size, burst, lock, cache, prot, qos。
  */
class Axi4FullAddr(val addrWidth: Int, val idWidth: Int) extends Bundle {
  val id    = UInt(idWidth.W)
  val addr  = UInt(addrWidth.W)
  val len   = UInt(8.W)
  val size  = UInt(3.W)
  val burst = UInt(2.W)
  val lock  = UInt(1.W)
  val cache = UInt(4.W)
  val prot  = UInt(3.W)
  val qos   = UInt(4.W)
}

class Axi4FullWriteData(val dataWidth: Int) extends Bundle {
  val data = UInt(dataWidth.W)
  val strb = UInt((dataWidth / 8).W)
  val last = Bool()
}

class Axi4FullWriteResp(val idWidth: Int) extends Bundle {
  val id   = UInt(idWidth.W)
  val resp = UInt(2.W)
}

class Axi4FullReadData(val dataWidth: Int, val idWidth: Int) extends Bundle {
  val id   = UInt(idWidth.W)
  val data = UInt(dataWidth.W)
  val resp = UInt(2.W)
  val last = Bool()
}

/** AXI4-Full 主设备侧完整五通道端口。
  *
  * 与 AxiLiteMasterIO 一样按主设备视角定义。使用 Irrevocable 是因为 AXI 要求
  * VALID 拉高且 READY 未握手期间载荷保持稳定。
  */
class Axi4FullMasterIO(val addrWidth: Int, val dataWidth: Int, val idWidth: Int) extends Bundle {
  require(dataWidth >= 8 && (dataWidth & (dataWidth - 1)) == 0,
    s"AXI4 dataWidth must be a power of two, got $dataWidth")
  require(idWidth > 0, s"AXI4 idWidth must be positive, got $idWidth")

  val aw = Irrevocable(new Axi4FullAddr(addrWidth, idWidth))
  val w  = Irrevocable(new Axi4FullWriteData(dataWidth))
  val b  = Flipped(Irrevocable(new Axi4FullWriteResp(idWidth)))
  val ar = Irrevocable(new Axi4FullAddr(addrWidth, idWidth))
  val r  = Flipped(Irrevocable(new Axi4FullReadData(dataWidth, idWidth)))
}

/** 将一个同数据宽度的 AXI4-Lite 主设备转为 AXI4-Full 主设备。
  *
  * 每个方向最多保留一笔未完成事务；发出的 Full 事务均为：
  *   - 单拍：len = 0
  *   - INCR 突发
  *   - WLAST = 1
  *
  * 这正好覆盖 NPC 当前 IFetchAXIAdapter / LSUAXIAdapter 的访问模型。
  * dataWidth 必须与两侧相同；ysyxSoC CPU 口使用 32 位，因此 SoC 模式下
  * 应将 IF/LSU 适配器同样参数化为 32 位。
  */
class AxiLiteToAxi4Full(
    addrWidth:     Int = 32,
    dataWidth:     Int = 32,
    idWidth:       Int = 4,
    transactionId: Int = 0
) extends Module {
  require(dataWidth >= 8 && (dataWidth & (dataWidth - 1)) == 0,
    s"AXI4 dataWidth must be a power of two, got $dataWidth")
  require(transactionId >= 0 && transactionId < (1 << idWidth),
    s"transactionId $transactionId does not fit in $idWidth bits")

  val io = IO(new Bundle {
    val lite = Flipped(new AxiLiteMasterIO(addrWidth, dataWidth))
    val axi  = new Axi4FullMasterIO(addrWidth, dataWidth, idWidth)
  })

  private val axiId    = transactionId.U(idWidth.W)

  def setAddressDefaults(channel: Axi4FullAddr, addr: UInt, size: UInt, prot: UInt): Unit = {
    channel.id    := axiId
    channel.addr  := addr
    channel.len   := 0.U
    channel.size  := size
    channel.burst := Axi4FullBurst.Incr
    channel.lock  := 0.U
    channel.cache := 0.U
    channel.prot  := prot
    channel.qos   := 0.U
  }

  // ── 读路径：一笔 AR 可一直未完成，直到其 R 响应被接收。 ─────────────────────
  val readOutstanding = RegInit(false.B)

  io.axi.ar.valid := io.lite.ar.valid && !readOutstanding
  setAddressDefaults(io.axi.ar.bits, io.lite.ar.bits.addr, io.lite.ar.bits.size, io.lite.ar.bits.prot)
  io.lite.ar.ready := io.axi.ar.ready && !readOutstanding

  io.lite.r.valid     := io.axi.r.valid && readOutstanding
  io.lite.r.bits.data := io.axi.r.bits.data
  io.lite.r.bits.resp := io.axi.r.bits.resp
  io.axi.r.ready      := io.lite.r.ready && readOutstanding

  when(io.axi.ar.fire) {
    readOutstanding := true.B
  }
  when(io.axi.r.fire && readOutstanding) {
    readOutstanding := false.B
  }

  // ── 写路径：AXI 允许 AW 与 W 独立握手。 ────────────────────────────────────
  val awSent               = RegInit(false.B)
  val wSent                = RegInit(false.B)
  val writeResponsePending = RegInit(false.B)
  val canSendWrite         = !writeResponsePending

  io.axi.aw.valid := io.lite.aw.valid && !awSent && canSendWrite
  setAddressDefaults(io.axi.aw.bits, io.lite.aw.bits.addr, io.lite.aw.bits.size, io.lite.aw.bits.prot)
  io.lite.aw.ready := io.axi.aw.ready && !awSent && canSendWrite

  io.axi.w.valid     := io.lite.w.valid && !wSent && canSendWrite
  io.axi.w.bits.data := io.lite.w.bits.data
  io.axi.w.bits.strb := io.lite.w.bits.strb
  io.axi.w.bits.last := true.B
  io.lite.w.ready    := io.axi.w.ready && !wSent && canSendWrite

  io.lite.b.valid     := io.axi.b.valid && writeResponsePending
  io.lite.b.bits.resp := io.axi.b.bits.resp
  io.axi.b.ready      := io.lite.b.ready && writeResponsePending

  when(canSendWrite) {
    when(io.axi.aw.fire) { awSent := true.B }
    when(io.axi.w.fire)  { wSent := true.B }

    val awComplete = awSent || io.axi.aw.fire
    val wComplete  = wSent || io.axi.w.fire
    when(awComplete && wComplete) {
      writeResponsePending := true.B
    }
  }

  when(io.axi.b.fire && writeResponsePending) {
    awSent               := false.B
    wSent                := false.B
    writeResponsePending := false.B
  }
}

/** 两个 AXI4-Lite 主设备到一个 AXI4-Lite 主设备的仲裁器。
  *
  * 读、写方向独立仲裁；已发出的 AR 或 AW/W 会锁定所属客户端，直到对应 R/B
  * 响应完成。当前采用固定优先级，客户端 0（取指）优先于客户端 1（LSU）。
  * 两个 NPC 客户端均一次至多保留一个请求，因此不需要事务 ID 表。
  */
class AxiLiteArbiter2(addrWidth: Int = 32, dataWidth: Int = 64) extends Module {
  val io = IO(new Bundle {
    val clients = Vec(2, Flipped(new AxiLiteMasterIO(addrWidth, dataWidth)))
    val master  = new AxiLiteMasterIO(addrWidth, dataWidth)
  })

  // 所有未被选中的客户端保持反压；响应仅送回发起请求的客户端。
  for (client <- io.clients) {
    client.aw.ready := false.B
    client.w.ready  := false.B
    client.ar.ready := false.B
    client.b.valid  := false.B
    client.b.bits.resp := AxiLiteResp.OKAY
    client.r.valid  := false.B
    client.r.bits.data := 0.U
    client.r.bits.resp := AxiLiteResp.OKAY
  }

  io.master.aw.valid := false.B
  io.master.aw.bits.addr := 0.U
  io.master.aw.bits.size := 0.U
  io.master.aw.bits.prot := 0.U
  io.master.w.valid := false.B
  io.master.w.bits.data := 0.U
  io.master.w.bits.strb := 0.U
  io.master.ar.valid := false.B
  io.master.ar.bits.addr := 0.U
  io.master.ar.bits.size := 0.U
  io.master.ar.bits.prot := 0.U
  io.master.b.ready := false.B
  io.master.r.ready := false.B

  // ── 读方向：AR 成功握手后，R 只会回到发起 AR 的客户端。 ───────────────────
  val readBusy   = RegInit(false.B)
  val readOwner  = RegInit(0.U(1.W))
  val readSelect = Mux(io.clients(0).ar.valid, 0.U(1.W), 1.U(1.W))

  when(!readBusy) {
    val selected = io.clients(readSelect)
    io.master.ar.valid := selected.ar.valid
    io.master.ar.bits := selected.ar.bits
    selected.ar.ready := io.master.ar.ready

    when(io.master.ar.fire) {
      readBusy  := true.B
      readOwner := readSelect
    }
  }.otherwise {
    val selected = io.clients(readOwner)
    selected.r.valid := io.master.r.valid
    selected.r.bits := io.master.r.bits
    io.master.r.ready := selected.r.ready

    when(io.master.r.fire) {
      readBusy := false.B
    }
  }

  // ── 写方向：AW/W 可以先后握手，但必须属于同一个客户端。 ───────────────────
  val writeBusy        = RegInit(false.B)
  val writeOwner       = RegInit(0.U(1.W))
  val writeAddressDone = RegInit(false.B)
  val writeDataDone    = RegInit(false.B)
  val writeSelect = Mux(
    io.clients(0).aw.valid || io.clients(0).w.valid,
    0.U(1.W),
    1.U(1.W)
  )

  when(!writeBusy) {
    val selected = io.clients(writeSelect)
    io.master.aw.valid := selected.aw.valid
    io.master.aw.bits := selected.aw.bits
    selected.aw.ready := io.master.aw.ready
    io.master.w.valid := selected.w.valid
    io.master.w.bits := selected.w.bits
    selected.w.ready := io.master.w.ready

    when(io.master.aw.fire || io.master.w.fire) {
      writeBusy        := true.B
      writeOwner       := writeSelect
      writeAddressDone := io.master.aw.fire
      writeDataDone    := io.master.w.fire
    }
  }.otherwise {
    val selected = io.clients(writeOwner)
    val writeResponsePending = writeAddressDone && writeDataDone

    when(!writeResponsePending) {
      io.master.aw.valid := selected.aw.valid && !writeAddressDone
      io.master.aw.bits := selected.aw.bits
      selected.aw.ready := io.master.aw.ready && !writeAddressDone
      io.master.w.valid := selected.w.valid && !writeDataDone
      io.master.w.bits := selected.w.bits
      selected.w.ready := io.master.w.ready && !writeDataDone

      when(io.master.aw.fire) { writeAddressDone := true.B }
      when(io.master.w.fire)  { writeDataDone := true.B }
    }.otherwise {
      selected.b.valid := io.master.b.valid
      selected.b.bits := io.master.b.bits
      io.master.b.ready := selected.b.ready

      when(io.master.b.fire) {
        writeBusy        := false.B
        writeAddressDone := false.B
        writeDataDone    := false.B
      }
    }
  }
}




// ============================================================================
//  十、AXI4-Lite DPI RAM 从设备（AxiLiteDpiRamSlave）
// ============================================================================

/** AXI4-Lite DPI 物理内存从设备
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
  val readAddr = RegInit(0.U(addrWidth.W))

  // DPIMem64 始终返回对齐的 64 位双字。RV32 请求通过地址位 2 选择其中一个
  // 32 位字节通道。
  val readData = if (dataWidth == 32) {
    Mux(readAddr(2), dpiMem.io.dout(63, 32), dpiMem.io.dout(31, 0))
  } else {
    dpiMem.io.dout
  }

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
  io.axi.r.bits.data  := readData
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
        readAddr := io.axi.ar.bits.addr
        state := sRResp
      }.otherwise {
        io.axi.aw.ready := !awDone
        io.axi.w.ready  := !wDone
        when(io.axi.aw.fire) { awAddr := io.axi.aw.bits.addr; awDone := true.B }
        when(io.axi.w.fire)  { wData  := io.axi.w.bits.data; wStrb := io.axi.w.bits.strb; wDone := true.B }
        val awComplete = awDone || io.axi.aw.fire
        val wComplete  = wDone  || io.axi.w.fire
        when(awComplete && wComplete) {
          val writeAddr = Mux(io.axi.aw.fire, io.axi.aw.bits.addr, awAddr)
          val writeData = Mux(io.axi.w.fire, io.axi.w.bits.data, wData)
          val writeStrb = Mux(io.axi.w.fire, io.axi.w.bits.strb, wStrb)

          dpiMem.io.wen  := true.B
          dpiMem.io.addr := writeAddr
          if (dataWidth == 32) {
            dpiMem.io.din := Mux(writeAddr(2), Cat(writeData, 0.U(32.W)), Cat(0.U(32.W), writeData))
            dpiMem.io.wstrb := Mux(writeAddr(2), Cat(writeStrb(3, 0), 0.U(4.W)), Cat(0.U(4.W), writeStrb(3, 0)))
          } else {
            dpiMem.io.din := writeData
            dpiMem.io.wstrb := writeStrb
          }
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
//  十一、AXI4-Lite DPI MMIO 从设备（AxiLiteDpiMmioSlave）
// ============================================================================

/** AXI4-Lite DPI MMIO 从设备
  *
  * 封装 MMIO_Core BlackBox，提供 AXI4-Lite 从设备接口。
  * DPI-C 函数 mmio_read_impl / mmio_write_impl。
  *
  * len 来源：
  *   - 读事务：ARPROT[1:0]（LSU 编码的 accessType）→ 换算
  *   - 写事务：PopCount(WSTRB) → 换算
  *
  * 数据对齐：
  *   - 读：MMIO_Core 返回低字节数据，从设备左移到正确字节通道
  *   - 写：AXI WDATA 位于正确字节通道，从设备右移到低字节后交给 MMIO_Core
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

  // AXI size 编码 → 实际字节数
  def sizeToLen(size: UInt): UInt = MuxLookup(size, 1.U(5.W))(Seq(
    "b000".U -> 1.U, "b001".U -> 2.U, "b010".U -> 4.U, "b011".U -> 8.U
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
        mmioCore.io.len  := sizeToLen(io.axi.ar.bits.size)
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
          // WDATA 位于字节通道位置，右移到低字节后交给 MMIO_Core
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
      // MMIO_Core dout 位于低字节，左移到正确字节通道
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
//  十二、AXI4-Lite 地址译码交叉开关 (AxiLiteCrossbar)
// ============================================================================

/** AXI4-Lite 从设备地址区域描述。
  *
  * 用于 AxiLiteCrossbar 的地址译码配置。
  *
  * @param baseAddr 该从设备的起始地址（含）
  * @param size     地址空间大小（字节数）
  *
  * 匹配规则：baseAddr <= addr < baseAddr + size
  */
case class AxiLiteSlaveRange(baseAddr: Long, size: Long)

/** AXI4-Lite 1-to-N 地址译码交叉开关
  *
  * 功能：
  *   - 接收 1 个主设备的 AXI4-Lite 请求
  *   - 根据地址范围路由到 N 个从设备之一
  *   - 将被选中从设备的响应返回给主设备
  *   - 地址未命中任何从设备时返回 DECERR
  *
  * @param addrWidth 地址位宽
  * @param dataWidth 数据位宽
  * @param ranges    每个从设备的地址范围（有序）
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
    io.slaves(i).ar.bits.size := io.master.ar.bits.size
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
    io.slaves(i).aw.bits.size := io.master.aw.bits.size
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
