package npc

import chisel3._
import npc.protocol.AxiLiteMasterIO

/** 位于 L1 arbiter 与外部主存 bridge 之间、以物理地址索引的共享 L2。 */
class UnifiedL2Cache(config: NpcConfig) extends Module {
  private val axi = config.axi
  private val dataWidth = config.memoryDataWidth
  private val cache = config.cache.l2cache
  require(cache.enabled, "UnifiedL2Cache requires CacheHierarchyConfig.l2cache")

  val io = IO(new Bundle {
    val cpu = Flipped(new AxiLiteMasterIO(axi.addrWidth, dataWidth))
    val memory = new AxiLiteMasterIO(axi.addrWidth, dataWidth)
    val flush = Input(Bool())
    val flushDone = Output(Bool())
    val drained = Output(Bool())
    val statistics = Output(new CacheStatistics)
  })

  if (config.cache.accessMode == CacheAccessMode.PipelinedTwoCycle) {
    val controller = Module(new PipelinedCacheController(cache, axi.addrWidth, dataWidth,
      config.memory.mainMemoryBase, config.memory.mainMemorySize, readOnly = false,
      config.cache.pipelinedQueues, dataWidth))
    controller.io.cpu <> io.cpu
    io.memory <> controller.io.memory
    controller.io.maintenanceRequest := io.flush
    controller.io.maintenanceInvalidate := false.B
    io.flushDone := controller.io.maintenanceDone && controller.io.drained
    io.drained := controller.io.drained
    io.statistics := controller.io.statistics
  } else {
    val controller = Module(new CacheController(cache, axi.addrWidth, dataWidth,
      config.memory.mainMemoryBase, config.memory.mainMemorySize, readOnly = false,
      memoryDataWidth = dataWidth))
    controller.io.cpu <> io.cpu
    io.memory <> controller.io.memory
    controller.io.maintenanceRequest := io.flush
    controller.io.maintenanceInvalidate := false.B
    io.flushDone := controller.io.maintenanceDone && controller.io.drained
    io.drained := controller.io.drained
    io.statistics := controller.io.statistics
  }
}
