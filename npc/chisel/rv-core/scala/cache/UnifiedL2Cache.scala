package npc

import chisel3._
import npc.protocol.{AxiLiteMasterIO, PipelinedAxiLiteArbiter2, PipelinedAxiLiteXorInterleaver2}

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
    require(cache.geometry.capacityBytes >= 4 * cache.geometry.lineBytes,
      "a four-bank pipelined L2 needs at least four cache lines")
    require(cache.geometry.indexBits >= 2,
      "a four-bank pipelined L2 needs at least four sets for address interleaving")

    // 四个 bank 各持有原 L2 一半再一半的 set。每个 bank 选择都保留一个原 set 的
    // 高位，因此给定 bank、低位 set 及 tag 后，原 set 仍唯一确定；这只是索引置换
    // 而非容量扩张。两层路由再混入两个低位与同一 tag 位，使短小程序的代码、紧邻的
    // 全局数据和栈 line 不会持续落到同一 bank。每个 bank 有独立阻塞 MSHR，I$/D$
    // 及顺序预取最多可让四笔 HBM read 同时在途。
    val bankCache = cache.copy(geometry = cache.geometry.copy(
      capacityBytes = cache.geometry.capacityBytes / 4
    ))
    val firstHighSelectBit = bankCache.geometry.offsetBits + bankCache.geometry.indexBits
    val secondHighSelectBit = firstHighSelectBit + 1
    val firstLowSelectBit = bankCache.geometry.offsetBits
    val secondLowSelectBit = firstLowSelectBit + 1
    val rootInterleaver = Module(new PipelinedAxiLiteXorInterleaver2(
      axi.addrWidth, dataWidth, secondHighSelectBit, secondLowSelectBit,
      extraSelectBits = Seq(secondLowSelectBit + 1, secondHighSelectBit + 1),
      depth = config.cache.pipelinedQueues.memoryDepth))
    val bankInterleavers = Seq.fill(2)(Module(new PipelinedAxiLiteXorInterleaver2(
      axi.addrWidth, dataWidth, firstHighSelectBit, firstLowSelectBit,
      extraSelectBits = Seq(firstLowSelectBit + 2, secondHighSelectBit + 1),
      depth = config.cache.pipelinedQueues.memoryDepth)))
    val rootMemoryArbiter = Module(new PipelinedAxiLiteArbiter2(
      axi.addrWidth, dataWidth, config.cache.pipelinedQueues.memoryDepth))
    val bankMemoryArbiters = Seq.fill(2)(Module(new PipelinedAxiLiteArbiter2(
      axi.addrWidth, dataWidth, config.cache.pipelinedQueues.memoryDepth)))
    val banks = Seq.fill(4)(Module(new PipelinedCacheController(bankCache, axi.addrWidth, dataWidth,
      config.memory.mainMemoryBase, config.memory.mainMemorySize, readOnly = false,
      config.cache.pipelinedQueues, dataWidth,
      // L2 以独立 bank 承接 I$/D$ 的共享后继流量。L1 D$ 在该组合不做立即预取，
      // 因此不会与此处的后继 line buffer 并发持有同一可写数据 line。
      enableNextLinePrefetch = config.memory.dpiTiming.enabled,
      eagerNextLinePrefetch = config.memory.dpiTiming.enabled)))

    rootInterleaver.io.upstream <> io.cpu
    for (group <- 0 until 2) {
      rootInterleaver.io.banks(group) <> bankInterleavers(group).io.upstream
      rootMemoryArbiter.io.clients(group) <> bankMemoryArbiters(group).io.master
      for (lane <- 0 until 2) {
        val bank = banks(group * 2 + lane)
        bankInterleavers(group).io.banks(lane) <> bank.io.cpu
        bankMemoryArbiters(group).io.clients(lane) <> bank.io.memory
        bank.io.maintenanceRequest := io.flush
        bank.io.maintenanceInvalidate := false.B
      }
    }
    io.memory <> rootMemoryArbiter.io.master
    io.flushDone := banks.map(bank => bank.io.maintenanceDone && bank.io.drained).reduce(_ && _)
    io.drained := banks.map(_.io.drained).reduce(_ && _)
    io.statistics.hits := banks.map(_.io.statistics.hits).reduce(_ +& _)
    io.statistics.misses := banks.map(_.io.statistics.misses).reduce(_ +& _)
    io.statistics.refills := banks.map(_.io.statistics.refills).reduce(_ +& _)
    io.statistics.writebacks := banks.map(_.io.statistics.writebacks).reduce(_ +& _)
    io.statistics.evictions := banks.map(_.io.statistics.evictions).reduce(_ +& _)
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
