package npc.protocol

import chisel3._
import chisel3.util._
import npc.{AxiConfig, CacheStatistics, NpcConfig, UnifiedL2Cache}
import npc.ip.axi.Axi4ReadWriteMasterIO

/**
  * NPC 的内存职责边界。
  *
  * 前端取指和后端加载/存储流量在此保持为独立客户端。SoC 模式下二者共用现有的
  * Lite 仲裁器和 Full 桥接；独立 DPI 模式保持原有 RAM/MMIO 拓扑，并使对外可见的
  * Full 主机保持非激活。
  */
class NpcMemoryFabric(config: NpcConfig) extends Module {
  private val axiConfig: AxiConfig = config.axi
  private val memoryConfig = config.memory
  private val memoryDataWidth = config.memoryDataWidth

  val io = IO(new Bundle {
    val instruction = Flipped(new AxiLiteMasterIO(axiConfig.addrWidth, memoryDataWidth))
    val data = Flipped(new AxiLiteMasterIO(axiConfig.addrWidth, memoryDataWidth))
    val master = new Axi4ReadWriteMasterIO(axiConfig.addrWidth, memoryDataWidth, axiConfig.idWidth)
    val putch = if (axiConfig.useExternalMaster) Some(Decoupled(UInt(8.W))) else None
    val l2Flush = Input(Bool())
    val l2FlushDone = Output(Bool())
    val l2Drained = Output(Bool())
    val l2Statistics = Output(new CacheStatistics)
    val drained = Output(Bool())
  })

  if (axiConfig.useExternalMaster) {
    val liteArbiter = Module(new AxiLiteArbiter2(axiConfig.addrWidth, memoryDataWidth))
    val dataCrossbar = Module(new AxiLiteCrossbar(
      axiConfig.addrWidth,
      memoryDataWidth,
      Seq(
        AxiLiteSlaveRange(memoryConfig.mainMemoryBase, memoryConfig.mainMemorySize),
        AxiLiteSlaveRange(memoryConfig.mmioBase, memoryConfig.mmioSize)
      )
    ))
    val mmio = Module(new AxiLiteHostMmioSlave(axiConfig.addrWidth, memoryDataWidth))
    val axiBridge = Module(new AxiLiteToAxi4Full(
      axiConfig.addrWidth,
      memoryDataWidth,
      axiConfig.idWidth,
      axiConfig.transactionId
    ))

    liteArbiter.io.clients(0) <> io.instruction
    dataCrossbar.io.master <> io.data
    liteArbiter.io.clients(1) <> dataCrossbar.io.slaves(0)
    mmio.io.axi <> dataCrossbar.io.slaves(1)
    io.putch.get <> mmio.io.putch
    if (config.cache.l2cache.enabled) {
      val l2 = Module(new UnifiedL2Cache(config))
      l2.io.cpu <> liteArbiter.io.master
      axiBridge.io.lite <> l2.io.memory
      l2.io.flush := io.l2Flush
      io.l2FlushDone := l2.io.flushDone
      io.l2Drained := l2.io.drained
      io.l2Statistics := l2.io.statistics
    } else {
      axiBridge.io.lite <> liteArbiter.io.master
      io.l2FlushDone := true.B
      io.l2Drained := true.B
      io.l2Statistics := 0.U.asTypeOf(new CacheStatistics)
    }
    io.drained := liteArbiter.io.drained && axiBridge.io.drained
    io.master <> axiBridge.io.axi
  } else {
    // 本地主存 timing 只属于 DPI RAM；MMIO 保持即时路径，不能被误当成 HBM 延迟。
    // 有 L2 的普通本地模式保留历史阻塞拓扑；两拍模式使用独立的路由 FIFO Fabric。
    val mmioSlave = Module(new AxiLiteDpiMmioSlave(axiConfig.addrWidth, memoryDataWidth))
    if (config.cache.l2cache.enabled) {
      if (config.cache.accessMode == npc.CacheAccessMode.PipelinedTwoCycle) {
        val depth = config.cache.pipelinedQueues.memoryDepth
        val liteArbiter = Module(new PipelinedAxiLiteArbiter2(axiConfig.addrWidth, memoryDataWidth, depth))
        val dataCrossbar = Module(new PipelinedAxiLiteCrossbar(
          axiConfig.addrWidth,
          memoryDataWidth,
          Seq(
            AxiLiteSlaveRange(memoryConfig.mainMemoryBase, memoryConfig.mainMemorySize),
            AxiLiteSlaveRange(memoryConfig.mmioBase, memoryConfig.mmioSize)
          ),
          depth
        ))
        val l2 = Module(new UnifiedL2Cache(config))
        val mainMemory = Module(new PipelinedAxiLiteDpiRamSlave(
          axiConfig.addrWidth, memoryDataWidth, depth, memoryConfig.dpiTiming))

        liteArbiter.io.clients(0) <> io.instruction
        dataCrossbar.io.master <> io.data
        liteArbiter.io.clients(1) <> dataCrossbar.io.slaves(0)
        mmioSlave.io.axi <> dataCrossbar.io.slaves(1)
        l2.io.cpu <> liteArbiter.io.master
        mainMemory.io.axi <> l2.io.memory
        l2.io.flush := io.l2Flush
        io.l2FlushDone := l2.io.flushDone
        io.l2Drained := l2.io.drained
        io.l2Statistics := l2.io.statistics
      } else {
        val liteArbiter = Module(new AxiLiteArbiter2(axiConfig.addrWidth, memoryDataWidth))
        val dataCrossbar = Module(new AxiLiteCrossbar(
          axiConfig.addrWidth,
          memoryDataWidth,
          Seq(
            AxiLiteSlaveRange(memoryConfig.mainMemoryBase, memoryConfig.mainMemorySize),
            AxiLiteSlaveRange(memoryConfig.mmioBase, memoryConfig.mmioSize)
          )
        ))
        val l2 = Module(new UnifiedL2Cache(config))
        val mainMemory = Module(new AxiLiteDpiRamSlave(
          axiConfig.addrWidth, memoryDataWidth, memoryConfig.dpiTiming))

        liteArbiter.io.clients(0) <> io.instruction
        dataCrossbar.io.master <> io.data
        liteArbiter.io.clients(1) <> dataCrossbar.io.slaves(0)
        mmioSlave.io.axi <> dataCrossbar.io.slaves(1)
        l2.io.cpu <> liteArbiter.io.master
        mainMemory.io.axi <> l2.io.memory
        l2.io.flush := io.l2Flush
        io.l2FlushDone := l2.io.flushDone
        io.l2Drained := l2.io.drained
        io.l2Statistics := l2.io.statistics
      }
    } else {
      if (config.cache.accessMode == npc.CacheAccessMode.PipelinedTwoCycle) {
        val depth = config.cache.pipelinedQueues.memoryDepth
        val instructionMemory = Module(new PipelinedAxiLiteDpiRamSlave(
          axiConfig.addrWidth, memoryDataWidth, depth, memoryConfig.dpiTiming))
        val dataMemory = Module(new PipelinedAxiLiteDpiRamSlave(
          axiConfig.addrWidth, memoryDataWidth, depth, memoryConfig.dpiTiming))
        val dataCrossbar = Module(new PipelinedAxiLiteCrossbar(
          axiConfig.addrWidth,
          memoryDataWidth,
          Seq(
            AxiLiteSlaveRange(memoryConfig.mainMemoryBase, memoryConfig.mainMemorySize),
            AxiLiteSlaveRange(memoryConfig.mmioBase, memoryConfig.mmioSize)
          ),
          depth
        ))

        io.instruction <> instructionMemory.io.axi
        io.data <> dataCrossbar.io.master
        dataCrossbar.io.slaves(0) <> dataMemory.io.axi
        dataCrossbar.io.slaves(1) <> mmioSlave.io.axi
      } else {
        val instructionMemory = Module(new AxiLiteDpiRamSlave(
          axiConfig.addrWidth, memoryDataWidth, memoryConfig.dpiTiming))
        val dataMemory = Module(new AxiLiteDpiRamSlave(
          axiConfig.addrWidth, memoryDataWidth, memoryConfig.dpiTiming))
        val dataCrossbar = Module(new AxiLiteCrossbar(
          axiConfig.addrWidth,
          memoryDataWidth,
          Seq(
            AxiLiteSlaveRange(memoryConfig.mainMemoryBase, memoryConfig.mainMemorySize),
            AxiLiteSlaveRange(memoryConfig.mmioBase, memoryConfig.mmioSize)
          )
        ))

        io.instruction <> instructionMemory.io.axi
        io.data <> dataCrossbar.io.master
        dataCrossbar.io.slaves(0) <> dataMemory.io.axi
        dataCrossbar.io.slaves(1) <> mmioSlave.io.axi
      }
      io.l2FlushDone := true.B
      io.l2Drained := true.B
      io.l2Statistics := 0.U.asTypeOf(new CacheStatistics)
    }
    // 本地 DPI 路径不连接 FPGA 外部 AXI；其维护状态由各自的 cache 信号负责。
    io.drained := true.B

    io.master.aw.valid := false.B
    io.master.aw.bits.id := 0.U
    io.master.aw.bits.addr := 0.U
    io.master.aw.bits.len := 0.U
    io.master.aw.bits.size := 0.U
    io.master.aw.bits.burst := 0.U
    io.master.aw.bits.lock := 0.U
    io.master.aw.bits.cache := 0.U
    io.master.aw.bits.prot := 0.U
    io.master.aw.bits.qos := 0.U
    io.master.w.valid := false.B
    io.master.w.bits.data := 0.U
    io.master.w.bits.strb := 0.U
    io.master.w.bits.last := false.B
    io.master.ar.valid := false.B
    io.master.ar.bits.id := 0.U
    io.master.ar.bits.addr := 0.U
    io.master.ar.bits.len := 0.U
    io.master.ar.bits.size := 0.U
    io.master.ar.bits.burst := 0.U
    io.master.ar.bits.lock := 0.U
    io.master.ar.bits.cache := 0.U
    io.master.ar.bits.prot := 0.U
    io.master.ar.bits.qos := 0.U
    io.master.b.ready := false.B
    io.master.r.ready := false.B
  }
}
