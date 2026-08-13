package npc.protocol

import chisel3._
import chisel3.util._

/** 本地流水 AXI-Lite 写请求的原子 AW/W 载荷。 */
class PipelinedAxiLiteWriteRequest(addrWidth: Int, dataWidth: Int) extends Bundle {
  val addr = UInt(addrWidth.W)
  val size = UInt(3.W)
  val prot = UInt(3.W)
  val data = UInt(dataWidth.W)
  val strb = UInt((dataWidth / 8).W)
}

/**
  * 两客户端、本地仿真用的 AXI-Lite 仲裁器。
  *
  * AR 与完整 AW/W 各有一个返回归属 FIFO。仲裁器每拍最多向下游发送一笔读或一笔
  * 写请求，并在连续竞争时翻转轮转指针；R/B 依据 FIFO 回到原客户端，因而不会把
  * I$ 与 D$ 的响应交叉。下游若暂时阻塞，已接收请求仍保存在对应 FIFO 中。
  */
class PipelinedAxiLiteArbiter2(
  addrWidth: Int = 32,
  dataWidth: Int = 64,
  depth: Int = 4
) extends Module {
  require(depth > 0 && (depth & (depth - 1)) == 0, s"FIFO depth must be a power of two, got $depth")

  val io = IO(new Bundle {
    val clients = Vec(2, Flipped(new AxiLiteMasterIO(addrWidth, dataWidth)))
    val master = new AxiLiteMasterIO(addrWidth, dataWidth)
  })

  val readRoutes = Module(new Queue(UInt(1.W), depth, pipe = false, flow = false))
  val writeRoutes = Module(new Queue(UInt(1.W), depth, pipe = false, flow = false))
  val readTurn = RegInit(false.B)
  val writeTurn = RegInit(false.B)
  val writeActive = RegInit(false.B)
  val writeOwner = RegInit(0.U(1.W))
  val writeAwSent = RegInit(false.B)
  val writeWSent = RegInit(false.B)

  for (client <- io.clients) {
    client.aw.ready := false.B
    client.w.ready := false.B
    client.ar.ready := false.B
    client.r.valid := false.B
    client.r.bits.data := 0.U
    client.r.bits.resp := AxiLiteResp.OKAY
    client.b.valid := false.B
    client.b.bits.resp := AxiLiteResp.OKAY
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
  io.master.r.ready := false.B
  io.master.b.ready := false.B

  val chooseRead0 = Mux(readTurn,
    !io.clients(1).ar.valid && io.clients(0).ar.valid,
    io.clients(0).ar.valid)
  val readPresent = io.clients(0).ar.valid || io.clients(1).ar.valid
  val readOwner = Mux(chooseRead0, 0.U(1.W), 1.U(1.W))
  when(readPresent && readRoutes.io.enq.ready) {
    io.master.ar.valid := true.B
    io.master.ar.bits := io.clients(readOwner).ar.bits
    io.clients(readOwner).ar.ready := io.master.ar.ready
  }
  readRoutes.io.enq.valid := io.master.ar.fire
  readRoutes.io.enq.bits := readOwner
  when(io.master.ar.fire) { readTurn := !readOwner.asBool }

  when(readRoutes.io.deq.valid) {
    val owner = readRoutes.io.deq.bits
    io.clients(owner).r.valid := io.master.r.valid
    io.clients(owner).r.bits := io.master.r.bits
    io.master.r.ready := io.clients(owner).r.ready
  }
  readRoutes.io.deq.ready := io.master.r.fire

  val chooseWrite0 = Mux(writeTurn,
    !(io.clients(1).aw.valid || io.clients(1).w.valid) &&
      (io.clients(0).aw.valid || io.clients(0).w.valid),
    io.clients(0).aw.valid || io.clients(0).w.valid)
  val writePresent = io.clients(0).aw.valid || io.clients(0).w.valid ||
    io.clients(1).aw.valid || io.clients(1).w.valid
  val selectedWriteOwner = Mux(chooseWrite0, 0.U(1.W), 1.U(1.W))
  val activeWriteOwner = Mux(writeActive, writeOwner, selectedWriteOwner)
  val maySendWrite = (writeActive || writePresent) && writeRoutes.io.enq.ready
  when(maySendWrite) {
    io.master.aw.valid := io.clients(activeWriteOwner).aw.valid && !writeAwSent
    io.master.aw.bits := io.clients(activeWriteOwner).aw.bits
    io.clients(activeWriteOwner).aw.ready := io.master.aw.ready && !writeAwSent
    io.master.w.valid := io.clients(activeWriteOwner).w.valid && !writeWSent
    io.master.w.bits := io.clients(activeWriteOwner).w.bits
    io.clients(activeWriteOwner).w.ready := io.master.w.ready && !writeWSent
  }
  val awComplete = writeAwSent || io.master.aw.fire
  val wComplete = writeWSent || io.master.w.fire
  val writeComplete = maySendWrite && awComplete && wComplete
  writeRoutes.io.enq.valid := writeComplete
  writeRoutes.io.enq.bits := activeWriteOwner
  when(!writeActive && (io.master.aw.fire || io.master.w.fire)) {
    writeActive := true.B
    writeOwner := selectedWriteOwner
  }
  when(io.master.aw.fire) { writeAwSent := true.B }
  when(io.master.w.fire) { writeWSent := true.B }
  when(writeComplete) {
    writeActive := false.B
    writeAwSent := false.B
    writeWSent := false.B
    writeTurn := !activeWriteOwner.asBool
  }

  when(writeRoutes.io.deq.valid) {
    val owner = writeRoutes.io.deq.bits
    io.clients(owner).b.valid := io.master.b.valid
    io.clients(owner).b.bits := io.master.b.bits
    io.master.b.ready := io.clients(owner).b.ready
  }
  writeRoutes.io.deq.ready := io.master.b.fire
}

/**
  * 多从设备、本地流水 AXI-Lite 交叉开关。
  *
  * 每次 AR 和完整 AW/W 的译码结果都进入返回路由 FIFO。未知地址同样占用一个 FIFO
  * 项，并在原顺序位置返回 DECERR；这使主存流水与立即 MMIO 路径不会重排响应。
  */
class PipelinedAxiLiteCrossbar(
  addrWidth: Int,
  dataWidth: Int,
  ranges: Seq[AxiLiteSlaveRange],
  depth: Int = 4
) extends Module {
  require(ranges.nonEmpty, "PipelinedAxiLiteCrossbar requires at least one slave")
  private val slaveCount = ranges.length
  private val routeWidth = math.max(1, log2Ceil(slaveCount + 1))
  private val slaveIndexWidth = math.max(1, log2Ceil(slaveCount))
  private val missRoute = slaveCount.U(routeWidth.W)

  val io = IO(new Bundle {
    val master = Flipped(new AxiLiteMasterIO(addrWidth, dataWidth))
    val slaves = Vec(slaveCount, new AxiLiteMasterIO(addrWidth, dataWidth))
  })
  val readRoutes = Module(new Queue(UInt(routeWidth.W), depth, pipe = false, flow = false))
  val writeRoutes = Module(new Queue(UInt(routeWidth.W), depth, pipe = false, flow = false))
  val writeActive = RegInit(false.B)
  val writeRoute = RegInit(0.U(routeWidth.W))
  val writeAwSent = RegInit(false.B)
  val writeWSent = RegInit(false.B)

  def matches(address: UInt): Vec[Bool] = VecInit(ranges.map(range =>
    address >= range.baseAddr.U(addrWidth.W) && address < (range.baseAddr + range.size).U(addrWidth.W)))

  /** missRoute 已在调用点分支处理；这里仅保留真实从设备所需的低位索引。 */
  def slaveIndex(route: UInt): UInt = route(slaveIndexWidth - 1, 0)

  for (slave <- io.slaves) {
    slave.aw.valid := false.B
    slave.aw.bits.addr := io.master.aw.bits.addr
    slave.aw.bits.size := io.master.aw.bits.size
    slave.aw.bits.prot := io.master.aw.bits.prot
    slave.w.valid := false.B
    slave.w.bits.data := io.master.w.bits.data
    slave.w.bits.strb := io.master.w.bits.strb
    slave.ar.valid := false.B
    slave.ar.bits.addr := io.master.ar.bits.addr
    slave.ar.bits.size := io.master.ar.bits.size
    slave.ar.bits.prot := io.master.ar.bits.prot
    slave.r.ready := false.B
    slave.b.ready := false.B
  }
  io.master.ar.ready := false.B
  io.master.aw.ready := false.B
  io.master.w.ready := false.B
  io.master.r.valid := false.B
  io.master.r.bits.data := 0.U
  io.master.r.bits.resp := AxiLiteResp.OKAY
  io.master.b.valid := false.B
  io.master.b.bits.resp := AxiLiteResp.OKAY

  val arMatches = matches(io.master.ar.bits.addr)
  val arHit = arMatches.asUInt.orR
  val arRoute = Mux(arHit, OHToUInt(arMatches.asUInt), missRoute)
  when(io.master.ar.valid && readRoutes.io.enq.ready) {
    when(arHit) {
      io.slaves(slaveIndex(arRoute)).ar.valid := true.B
      io.master.ar.ready := io.slaves(slaveIndex(arRoute)).ar.ready
    }.otherwise {
      io.master.ar.ready := true.B
    }
  }
  readRoutes.io.enq.valid := io.master.ar.fire
  readRoutes.io.enq.bits := arRoute

  when(readRoutes.io.deq.valid) {
    when(readRoutes.io.deq.bits === missRoute) {
      io.master.r.valid := true.B
      io.master.r.bits.resp := AxiLiteResp.DECERR
    }.otherwise {
      val route = readRoutes.io.deq.bits
      io.master.r.valid := io.slaves(slaveIndex(route)).r.valid
      io.master.r.bits := io.slaves(slaveIndex(route)).r.bits
      io.slaves(slaveIndex(route)).r.ready := io.master.r.ready
    }
  }
  readRoutes.io.deq.ready := io.master.r.fire

  val awMatches = matches(io.master.aw.bits.addr)
  val awHit = awMatches.asUInt.orR
  val firstWriteRoute = Mux(awHit, OHToUInt(awMatches.asUInt), missRoute)
  val activeWriteRoute = Mux(writeActive, writeRoute, firstWriteRoute)
  val writeAvailable = writeActive || io.master.aw.valid
  val maySendWrite = writeAvailable && writeRoutes.io.enq.ready
  when(maySendWrite) {
    when(activeWriteRoute === missRoute) {
      io.master.aw.ready := !writeAwSent
      io.master.w.ready := !writeWSent && (writeAwSent || io.master.aw.fire)
    }.otherwise {
      io.slaves(slaveIndex(activeWriteRoute)).aw.valid := io.master.aw.valid && !writeAwSent
      io.master.aw.ready := io.slaves(slaveIndex(activeWriteRoute)).aw.ready && !writeAwSent
      io.slaves(slaveIndex(activeWriteRoute)).w.valid := io.master.w.valid && !writeWSent
      io.master.w.ready := io.slaves(slaveIndex(activeWriteRoute)).w.ready && !writeWSent
    }
  }
  val awComplete = writeAwSent || io.master.aw.fire
  val wComplete = writeWSent || io.master.w.fire
  val writeComplete = maySendWrite && awComplete && wComplete
  writeRoutes.io.enq.valid := writeComplete
  writeRoutes.io.enq.bits := activeWriteRoute
  when(!writeActive && io.master.aw.fire) {
    writeActive := true.B
    writeRoute := firstWriteRoute
  }
  when(io.master.aw.fire) { writeAwSent := true.B }
  when(io.master.w.fire) { writeWSent := true.B }
  when(writeComplete) {
    writeActive := false.B
    writeAwSent := false.B
    writeWSent := false.B
  }

  when(writeRoutes.io.deq.valid) {
    when(writeRoutes.io.deq.bits === missRoute) {
      io.master.b.valid := true.B
      io.master.b.bits.resp := AxiLiteResp.DECERR
    }.otherwise {
      val route = writeRoutes.io.deq.bits
      io.master.b.valid := io.slaves(slaveIndex(route)).b.valid
      io.master.b.bits := io.slaves(slaveIndex(route)).b.bits
      io.slaves(slaveIndex(route)).b.ready := io.master.b.ready
    }
  }
  writeRoutes.io.deq.ready := io.master.b.fire
}

/**
  * 向后兼容 DPI ABI 的本地主存前端。
  *
  * RAM 仍由稳定的 32/64-bit DPI lane 实现，前端以读 FIFO 与完整写 FIFO 接收流水
  * 请求；默认一拍响应模型下连续 512-bit line 请求可在 FIFO 中排队。启用抖动 timing
  * 时，底层从设备继续按原有确定性延迟返回，顺序不会变化。
  */
class PipelinedAxiLiteDpiRamSlave(
  addrWidth: Int = 32,
  dataWidth: Int = 64,
  depth: Int = 4,
  timing: npc.DpiMemoryTimingConfig = npc.DpiMemoryTimingConfig.Immediate
) extends Module {
  val io = IO(new Bundle {
    val axi = Flipped(new AxiLiteMasterIO(addrWidth, dataWidth))
  })
  val reads = Module(new Queue(new AxiLiteAddr(addrWidth), depth, pipe = false, flow = false))
  val writes = Module(new Queue(new PipelinedAxiLiteWriteRequest(addrWidth, dataWidth),
    depth, pipe = false, flow = false))
  val ram = Module(new AxiLiteDpiRamSlave(addrWidth, dataWidth, timing))
  val awHeld = RegInit(false.B)
  val awAddr = Reg(UInt(addrWidth.W))
  val awSize = Reg(UInt(3.W))
  val awProt = Reg(UInt(3.W))
  val wHeld = RegInit(false.B)
  val wData = Reg(UInt(dataWidth.W))
  val wStrb = Reg(UInt((dataWidth / 8).W))
  val writeAwSent = RegInit(false.B)
  val writeWSent = RegInit(false.B)

  val writeComplete = (awHeld || io.axi.aw.valid) && (wHeld || io.axi.w.valid)
  val writeActive = awHeld || wHeld || io.axi.aw.valid || io.axi.w.valid
  io.axi.aw.ready := !awHeld && (!writeComplete || writes.io.enq.ready)
  io.axi.w.ready := !wHeld && (!writeComplete || writes.io.enq.ready)
  io.axi.ar.ready := !writeActive && reads.io.enq.ready
  reads.io.enq.valid := io.axi.ar.valid && !writeActive
  reads.io.enq.bits := io.axi.ar.bits
  writes.io.enq.valid := writeComplete
  writes.io.enq.bits.addr := Mux(awHeld, awAddr, io.axi.aw.bits.addr)
  writes.io.enq.bits.size := Mux(awHeld, awSize, io.axi.aw.bits.size)
  writes.io.enq.bits.prot := Mux(awHeld, awProt, io.axi.aw.bits.prot)
  writes.io.enq.bits.data := Mux(wHeld, wData, io.axi.w.bits.data)
  writes.io.enq.bits.strb := Mux(wHeld, wStrb, io.axi.w.bits.strb)
  when(io.axi.aw.fire) {
    awHeld := true.B
    awAddr := io.axi.aw.bits.addr
    awSize := io.axi.aw.bits.size
    awProt := io.axi.aw.bits.prot
  }
  when(io.axi.w.fire) {
    wHeld := true.B
    wData := io.axi.w.bits.data
    wStrb := io.axi.w.bits.strb
  }
  when(writes.io.enq.fire) {
    awHeld := false.B
    wHeld := false.B
  }

  val serveRead = reads.io.deq.valid
  ram.io.axi.ar.valid := serveRead
  ram.io.axi.ar.bits := reads.io.deq.bits
  reads.io.deq.ready := ram.io.axi.ar.fire
  ram.io.axi.aw.valid := !serveRead && writes.io.deq.valid && !writeAwSent
  ram.io.axi.aw.bits.addr := writes.io.deq.bits.addr
  ram.io.axi.aw.bits.size := writes.io.deq.bits.size
  ram.io.axi.aw.bits.prot := writes.io.deq.bits.prot
  ram.io.axi.w.valid := !serveRead && writes.io.deq.valid && !writeWSent
  ram.io.axi.w.bits.data := writes.io.deq.bits.data
  ram.io.axi.w.bits.strb := writes.io.deq.bits.strb
  val writeConsumed = (writeAwSent || ram.io.axi.aw.fire) && (writeWSent || ram.io.axi.w.fire)
  writes.io.deq.ready := writeConsumed
  when(ram.io.axi.aw.fire) { writeAwSent := true.B }
  when(ram.io.axi.w.fire) { writeWSent := true.B }
  when(writes.io.deq.fire) {
    writeAwSent := false.B
    writeWSent := false.B
  }
  io.axi.r.valid := ram.io.axi.r.valid
  io.axi.r.bits := ram.io.axi.r.bits
  ram.io.axi.r.ready := io.axi.r.ready
  io.axi.b.valid := ram.io.axi.b.valid
  io.axi.b.bits := ram.io.axi.b.bits
  ram.io.axi.b.ready := io.axi.b.ready
}
