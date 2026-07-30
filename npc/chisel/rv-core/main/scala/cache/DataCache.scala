package npc

import chisel3._

class DataCache(config: NpcConfig) extends Module {
  private val axi = config.axi
  private val cache = config.cache.dcache
  val io = IO(new Bundle {
    val cpu = Flipped(new npc.protocol.AxiLiteMasterIO(axi.addrWidth, axi.dataWidth))
    val memory = new npc.protocol.AxiLiteMasterIO(axi.addrWidth, axi.dataWidth)
    val flush = Input(Bool())
    val flushDone = Output(Bool())
    val drained = Output(Bool())
    val statistics = Output(new CacheStatistics)
  })

  val controller = Module(new CacheController(cache, axi.addrWidth, axi.dataWidth,
    config.memory.mainMemoryBase, config.memory.mainMemorySize, readOnly = false))
  controller.io.cpu <> io.cpu
  io.memory <> controller.io.memory
  controller.io.maintenanceRequest := io.flush
  controller.io.maintenanceInvalidate := false.B
  // A failed dirty writeback leaves drained low. Do not let FENCE.I or the
  // FPGA completion path observe a false maintenance completion.
  io.flushDone := controller.io.maintenanceDone && controller.io.drained
  io.drained := controller.io.drained
  io.statistics := controller.io.statistics
}
