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
    // HBM 首次访问约需八十拍。将总容量等分为四个独立 bank 后，不同 line 的 miss
    // 可以各自在 bank 内保持一个 MSHR；上下游交叉开关仍按原请求顺序返回 R/B。
    val useHbmBanks = config.memory.dpiTiming.enabled && cache.geometry.sets >= 4
    if (useHbmBanks) {
      val bankCount = 4
      require(cache.geometry.capacityBytes % bankCount == 0,
        s"banked data cache needs capacity divisible by $bankCount")
      val bankCache = cache.copy(geometry = cache.geometry.copy(
        capacityBytes = cache.geometry.capacityBytes / bankCount
      ))
      // 两级选择使用原 set 的两位最高 index。标量流水按序等待 load 结果，连续 line
      // 的 MSHR 并发无法抵消预取被分散到不同 bank 的损失；保留同一 bank 的连续 line
      // 才能让下一行预取在真实需求前完成。
      val firstHighSelectBit = bankCache.geometry.offsetBits + bankCache.geometry.indexBits
      val secondHighSelectBit = firstHighSelectBit + 1
      val firstLowSelectBit = bankCache.geometry.offsetBits
      val secondLowSelectBit = firstLowSelectBit + 1
      val rootInterleaver = Module(new npc.protocol.PipelinedAxiLiteXorInterleaver2(
        axi.addrWidth, axi.dataWidth, secondLowSelectBit, secondHighSelectBit,
        directSelectBit = Some(secondHighSelectBit),
        depth = config.cache.pipelinedQueues.memoryDepth))
      val bankInterleavers = Seq.fill(2)(Module(new npc.protocol.PipelinedAxiLiteXorInterleaver2(
        axi.addrWidth, axi.dataWidth, firstLowSelectBit, firstHighSelectBit,
        directSelectBit = Some(firstHighSelectBit),
        depth = config.cache.pipelinedQueues.memoryDepth)))
      val rootMemoryArbiter = Module(new npc.protocol.PipelinedAxiLiteArbiter2(
        axi.addrWidth, memoryDataWidth, config.cache.pipelinedQueues.memoryDepth))
      val bankMemoryArbiters = Seq.fill(2)(Module(new npc.protocol.PipelinedAxiLiteArbiter2(
        axi.addrWidth, memoryDataWidth, config.cache.pipelinedQueues.memoryDepth)))
      val banks = Seq.tabulate(bankCount) { bankIndex => Module(new PipelinedCacheController(
        bankCache, axi.addrWidth, axi.dataWidth,
        config.memory.mainMemoryBase, config.memory.mainMemorySize, readOnly = false,
        config.cache.pipelinedQueues, memoryDataWidth,
        enableNextLinePrefetch = true,
        eagerNextLinePrefetch = config.memory.dpiTiming.enabled && !config.cache.l2cache.enabled,
        enableWriteMissEarlyAcknowledgement = true)) }

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
      val controller = Module(new PipelinedCacheController(cache, axi.addrWidth, axi.dataWidth,
        config.memory.mainMemoryBase, config.memory.mainMemorySize, readOnly = false,
        config.cache.pipelinedQueues, memoryDataWidth,
        // L2 的四个 bank 可并行接收 D$ 确认过的线性后继请求。首个 miss 不激进预取，
        // 既保留流式访问的提前量，也避免随机地址把共享 L2 的单行 buffer 占满。
        enableNextLinePrefetch = config.memory.dpiTiming.enabled,
        eagerNextLinePrefetch = false,
        enableWriteMissEarlyAcknowledgement = config.memory.dpiTiming.enabled))
      controller.io.cpu <> io.cpu
      io.memory <> controller.io.memory
      controller.io.maintenanceRequest := io.flush
      controller.io.maintenanceInvalidate := false.B
      io.flushDone := controller.io.maintenanceDone && controller.io.drained
      io.drained := controller.io.drained
      io.statistics := controller.io.statistics
    }
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
