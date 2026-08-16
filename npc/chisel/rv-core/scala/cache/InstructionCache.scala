package npc

import chisel3._

class InstructionCache(config: NpcConfig) extends Module {
  private val axi = config.axi
  private val memoryDataWidth = config.memoryDataWidth
  private val cache = config.cache.icache
  val io = IO(new Bundle {
    val cpu = Flipped(new npc.protocol.AxiLiteMasterIO(axi.addrWidth, axi.dataWidth))
    val memory = new npc.protocol.AxiLiteMasterIO(axi.addrWidth, memoryDataWidth)
    val invalidate = Input(Bool())
    val invalidateDone = Output(Bool())
    val statistics = Output(new CacheStatistics)
  })

  if (config.cache.accessMode == CacheAccessMode.PipelinedTwoCycle) {
    val controller = Module(new PipelinedCacheController(cache, axi.addrWidth, axi.dataWidth,
      config.memory.mainMemoryBase, config.memory.mainMemorySize, readOnly = true,
      config.cache.pipelinedQueues, memoryDataWidth,
      enableNextLinePrefetch = config.memory.dpiTiming.enabled,
      eagerNextLinePrefetch = config.memory.dpiTiming.enabled))
    controller.io.cpu <> io.cpu
    io.memory <> controller.io.memory
    controller.io.maintenanceRequest := io.invalidate
    controller.io.maintenanceInvalidate := true.B
    io.invalidateDone := controller.io.maintenanceDone
    io.statistics := controller.io.statistics
  } else {
    val controller = Module(new CacheController(cache, axi.addrWidth, axi.dataWidth,
      config.memory.mainMemoryBase, config.memory.mainMemorySize, readOnly = true,
      memoryDataWidth = memoryDataWidth))
    controller.io.cpu <> io.cpu
    io.memory <> controller.io.memory
    controller.io.maintenanceRequest := io.invalidate
    controller.io.maintenanceInvalidate := true.B
    io.invalidateDone := controller.io.maintenanceDone
    io.statistics := controller.io.statistics
  }
}
