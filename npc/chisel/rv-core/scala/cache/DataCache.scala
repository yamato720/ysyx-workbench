package npc

import chisel3._

class DataCache(config: NpcConfig) extends Module {
  private val axi = config.axi
  private val memoryDataWidth = config.memoryDataWidth
  private val cache = config.cache.dcache
  val io = IO(new Bundle {
    val cpu = Flipped(new npc.protocol.AxiLiteMasterIO(axi.addrWidth, axi.dataWidth))
    val memory = new npc.protocol.AxiLiteMasterIO(axi.addrWidth, memoryDataWidth)
    val flush = Input(Bool())
    val flushDone = Output(Bool())
    val drained = Output(Bool())
    val statistics = Output(new CacheStatistics)
  })

  if (config.cache.accessMode == CacheAccessMode.PipelinedTwoCycle) {
    val controller = Module(new PipelinedCacheController(cache, axi.addrWidth, axi.dataWidth,
      config.memory.mainMemoryBase, config.memory.mainMemorySize, readOnly = false,
      config.cache.pipelinedQueues, memoryDataWidth))
    controller.io.cpu <> io.cpu
    io.memory <> controller.io.memory
    controller.io.maintenanceRequest := io.flush
    controller.io.maintenanceInvalidate := false.B
    io.flushDone := controller.io.maintenanceDone && controller.io.drained
    io.drained := controller.io.drained
    io.statistics := controller.io.statistics
  } else {
    val controller = Module(new CacheController(cache, axi.addrWidth, axi.dataWidth,
      config.memory.mainMemoryBase, config.memory.mainMemorySize, readOnly = false,
      memoryDataWidth = memoryDataWidth))
    controller.io.cpu <> io.cpu
    io.memory <> controller.io.memory
    controller.io.maintenanceRequest := io.flush
    controller.io.maintenanceInvalidate := false.B
    io.flushDone := controller.io.maintenanceDone && controller.io.drained
    io.drained := controller.io.drained
    io.statistics := controller.io.statistics
  }
}
