package scpu.protocol

import chisel3._
import chisel3.util._
import scpu.{NpcConfig, AxiConfig}

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

  val io = IO(new Bundle {
    val instruction = Flipped(new AxiLiteMasterIO(axiConfig.addrWidth, axiConfig.dataWidth))
    val data = Flipped(new AxiLiteMasterIO(axiConfig.addrWidth, axiConfig.dataWidth))
    val master = new Axi4FullMasterIO(axiConfig.addrWidth, axiConfig.dataWidth, axiConfig.idWidth)
    val putch = if (axiConfig.useExternalMaster) Some(Decoupled(UInt(8.W))) else None
  })

  if (axiConfig.useExternalMaster) {
    val liteArbiter = Module(new AxiLiteArbiter2(axiConfig.addrWidth, axiConfig.dataWidth))
    val dataCrossbar = Module(new AxiLiteCrossbar(
      axiConfig.addrWidth,
      axiConfig.dataWidth,
      Seq(
        AxiLiteSlaveRange(memoryConfig.mainMemoryBase, memoryConfig.mainMemorySize),
        AxiLiteSlaveRange(memoryConfig.mmioBase, memoryConfig.mmioSize)
      )
    ))
    val mmio = Module(new AxiLiteHostMmioSlave(axiConfig.addrWidth, axiConfig.dataWidth))
    val axiBridge = Module(new AxiLiteToAxi4Full(
      axiConfig.addrWidth,
      axiConfig.dataWidth,
      axiConfig.idWidth,
      axiConfig.transactionId
    ))

    liteArbiter.io.clients(0) <> io.instruction
    dataCrossbar.io.master <> io.data
    liteArbiter.io.clients(1) <> dataCrossbar.io.slaves(0)
    mmio.io.axi <> dataCrossbar.io.slaves(1)
    io.putch.get <> mmio.io.putch
    axiBridge.io.lite <> liteArbiter.io.master
    io.master <> axiBridge.io.axi
  } else {
    val instructionMemory = Module(new AxiLiteDpiRamSlave(axiConfig.addrWidth, axiConfig.dataWidth))
    val dataMemory = Module(new AxiLiteDpiRamSlave(axiConfig.addrWidth, axiConfig.dataWidth))
    val mmioSlave = Module(new AxiLiteDpiMmioSlave(axiConfig.addrWidth, axiConfig.dataWidth))
    val dataCrossbar = Module(new AxiLiteCrossbar(
      axiConfig.addrWidth,
      axiConfig.dataWidth,
      Seq(
        AxiLiteSlaveRange(memoryConfig.mainMemoryBase, memoryConfig.mainMemorySize),
        AxiLiteSlaveRange(memoryConfig.mmioBase, memoryConfig.mmioSize)
      )
    ))

    io.instruction <> instructionMemory.io.axi
    io.data <> dataCrossbar.io.master
    dataCrossbar.io.slaves(0) <> dataMemory.io.axi
    dataCrossbar.io.slaves(1) <> mmioSlave.io.axi

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
