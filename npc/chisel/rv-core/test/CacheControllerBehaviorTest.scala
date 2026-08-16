package npc

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class CacheControllerBehaviorTest extends AnyFlatSpec {
  private case class CycleResult(read: Option[BigInt], writeResponse: Boolean)

  "PipelinedCacheController" should "return a warmed synchronous hit without an extra response register" in {
    val cache = CacheConfig(
      enabled = true,
      geometry = CacheGeometry(64, 16, CacheMapping.DirectMapped),
      replacement = CacheReplacement.TreePLRU,
      storage = CacheStorage.Registers
    )
    val base = BigInt("80000000", 16)
    val line = BigInt("1122334455667788", 16)

    simulate(new PipelinedCacheController(cache, 32, 64, 0x80000000L, 0x10000000L,
      readOnly = false, PipelinedCacheQueueConfig.TwoCycleLocal)) { dut =>
      var pendingRead: Option[BigInt] = None
      var cycleCount = 0


      def driveMemory(): Unit = {
        dut.io.memory.ar.ready.poke(pendingRead.isEmpty)
        dut.io.memory.r.valid.poke(pendingRead.nonEmpty)
        dut.io.memory.r.bits.data.poke(pendingRead.fold(BigInt(0))(_ => line))
        dut.io.memory.r.bits.resp.poke(0)
        dut.io.memory.aw.ready.poke(true)
        dut.io.memory.w.ready.poke(true)
        dut.io.memory.b.valid.poke(false)
        dut.io.memory.b.bits.resp.poke(0)
      }

      def cycle(): Option[BigInt] = {
        driveMemory()
        val arFire = dut.io.memory.ar.valid.peek().litToBoolean && pendingRead.isEmpty
        val arAddress = dut.io.memory.ar.bits.addr.peek().litValue
        val rFire = pendingRead.nonEmpty && dut.io.memory.r.ready.peek().litToBoolean
        val cpuRead = Option.when(dut.io.cpu.r.valid.peek().litToBoolean &&
          dut.io.cpu.r.ready.peek().litToBoolean)(dut.io.cpu.r.bits.data.peek().litValue)
        dut.clock.step()
        if (rFire) pendingRead = None
        if (arFire) pendingRead = Some(arAddress)
        cycleCount += 1
        cpuRead
      }

      dut.io.cpu.aw.valid.poke(false)
      dut.io.cpu.w.valid.poke(false)
      dut.io.cpu.ar.valid.poke(false)
      dut.io.cpu.b.ready.poke(true)
      dut.io.cpu.r.ready.poke(true)
      dut.io.maintenanceRequest.poke(false)
      dut.io.maintenanceInvalidate.poke(false)
      dut.reset.poke(true)
      dut.clock.step(2)
      dut.reset.poke(false)

      def issueRead(address: BigInt = base): Option[BigInt] = {
        dut.io.cpu.ar.bits.addr.poke(address)
        dut.io.cpu.ar.bits.size.poke(3)
        dut.io.cpu.ar.bits.prot.poke(4)
        dut.io.cpu.ar.valid.poke(true)
        driveMemory()
        dut.io.cpu.ar.ready.expect(true.B)
        val result = cycle()
        dut.io.cpu.ar.valid.poke(false)
        result
      }

      issueRead()
      assert((0 until 40).iterator.map(_ => cycle()).collectFirst { case Some(value) => value }
        .contains(line), "first access must refill the cache")

      issueRead()
      dut.io.cpu.r.valid.expect(true.B)
      dut.io.cpu.r.bits.data.expect(line.U)
      cycle()

      val warmHandshakeCycles = mutable.ArrayBuffer.empty[Int]
      val warmResponseCycles = mutable.ArrayBuffer.empty[Int]
      val warmResponses = mutable.ArrayBuffer.empty[BigInt]
      Seq(base, base + 8, base, base + 8).foreach { address =>
        // 以握手边沿后的周期作为 N，避免把测试驱动器的采样边沿重复计入。
        warmHandshakeCycles += (cycleCount + 1)
        issueRead(address).foreach { value =>
          warmResponseCycles += (cycleCount - 1)
          warmResponses += value
        }
      }
      var guard = 0
      while (warmResponses.size < 4 && guard < 12) {
        val observedCycle = cycleCount
        cycle().foreach { value =>
          warmResponseCycles += observedCycle
          warmResponses += value
        }
        guard += 1
      }
      assert(warmResponses.toSeq == Seq(line, line, line, line))
      assert(warmResponseCycles.head == warmHandshakeCycles.head)
      assert(warmResponseCycles.sliding(2).forall(pair => pair(1) == pair.head + 1))
    }
  }

  it should "start a queued hit while returning the preceding miss response" in {
    val cache = CacheConfig(
      enabled = true,
      geometry = CacheGeometry(64, 8, CacheMapping.DirectMapped),
      replacement = CacheReplacement.TreePLRU,
      storage = CacheStorage.Auto
    )
    val base = BigInt("80000000", 16)
    val line = BigInt("1122334455667788", 16)

    simulate(new PipelinedCacheController(cache, 32, 64, 0x80000000L, 0x10000000L,
      readOnly = true, PipelinedCacheQueueConfig.TwoCycleLocal)) { dut =>
      var pendingRead: Option[BigInt] = None
      var cycleCount = 0

      def driveMemory(): Unit = {
        dut.io.memory.ar.ready.poke(pendingRead.isEmpty)
        dut.io.memory.r.valid.poke(pendingRead.nonEmpty)
        dut.io.memory.r.bits.data.poke(pendingRead.fold(BigInt(0))(_ => line))
        dut.io.memory.r.bits.resp.poke(0)
        dut.io.memory.aw.ready.poke(true)
        dut.io.memory.w.ready.poke(true)
        dut.io.memory.b.valid.poke(false)
        dut.io.memory.b.bits.resp.poke(0)
      }

      def cycle(): Option[BigInt] = {
        driveMemory()
        val arFire = dut.io.memory.ar.valid.peek().litToBoolean && pendingRead.isEmpty
        val arAddress = dut.io.memory.ar.bits.addr.peek().litValue
        val rFire = pendingRead.nonEmpty && dut.io.memory.r.ready.peek().litToBoolean
        val cpuRead = Option.when(dut.io.cpu.r.valid.peek().litToBoolean &&
          dut.io.cpu.r.ready.peek().litToBoolean)(dut.io.cpu.r.bits.data.peek().litValue)
        dut.clock.step()
        if (rFire) pendingRead = None
        if (arFire) pendingRead = Some(arAddress)
        cycleCount += 1
        cpuRead
      }

      def submitRead(): Option[BigInt] = {
        dut.io.cpu.ar.bits.addr.poke(base)
        dut.io.cpu.ar.bits.size.poke(3)
        dut.io.cpu.ar.bits.prot.poke(4)
        dut.io.cpu.ar.valid.poke(true)
        driveMemory()
        dut.io.cpu.ar.ready.expect(true.B)
        val result = cycle()
        dut.io.cpu.ar.valid.poke(false)
        result
      }

      dut.io.cpu.aw.valid.poke(false)
      dut.io.cpu.w.valid.poke(false)
      dut.io.cpu.ar.valid.poke(false)
      dut.io.cpu.b.ready.poke(true)
      dut.io.cpu.r.ready.poke(true)
      dut.io.maintenanceRequest.poke(false)
      dut.io.maintenanceInvalidate.poke(false)
      dut.reset.poke(true)
      dut.clock.step(2)
      dut.reset.poke(false)

      val responseCycles = mutable.ArrayBuffer.empty[Int]
      submitRead().foreach(_ => responseCycles += cycleCount - 1)
      submitRead().foreach(_ => responseCycles += cycleCount - 1)
      var guard = 0
      while (responseCycles.size < 2 && guard < 40) {
        val observedCycle = cycleCount
        cycle().foreach(_ => responseCycles += observedCycle)
        guard += 1
      }

      assert(responseCycles.size == 2)
      assert(responseCycles(1) == responseCycles.head + 1,
        s"queued hit must follow the miss response without a recovery bubble: $responseCycles")
    }
  }

  it should "drain requests accepted before cache maintenance" in {
    val cache = CacheConfig(
      enabled = true,
      geometry = CacheGeometry(64, 16, CacheMapping.DirectMapped),
      replacement = CacheReplacement.TreePLRU,
      storage = CacheStorage.Registers
    )
    val base = BigInt("80000000", 16)
    val firstLine = BigInt("1111111111111111", 16)
    val secondLine = BigInt("2222222222222222", 16)
    val backing = Map(base -> firstLine, base + 16 -> secondLine)

    simulate(new PipelinedCacheController(cache, 32, 64, 0x80000000L, 0x10000000L,
      readOnly = true, PipelinedCacheQueueConfig.TwoCycleLocal)) { dut =>
      var pendingRead: Option[BigInt] = None

      def driveMemory(): Unit = {
        dut.io.memory.ar.ready.poke(pendingRead.isEmpty)
        dut.io.memory.r.valid.poke(pendingRead.nonEmpty)
        dut.io.memory.r.bits.data.poke(pendingRead.flatMap(backing.get).getOrElse(BigInt(0)))
        dut.io.memory.r.bits.resp.poke(0)
        dut.io.memory.aw.ready.poke(true)
        dut.io.memory.w.ready.poke(true)
        dut.io.memory.b.valid.poke(false)
        dut.io.memory.b.bits.resp.poke(0)
      }

      def cycle(): Option[BigInt] = {
        driveMemory()
        val arFire = dut.io.memory.ar.valid.peek().litToBoolean && pendingRead.isEmpty
        val arAddress = dut.io.memory.ar.bits.addr.peek().litValue
        val rFire = pendingRead.nonEmpty && dut.io.memory.r.ready.peek().litToBoolean
        val cpuRead = Option.when(dut.io.cpu.r.valid.peek().litToBoolean &&
          dut.io.cpu.r.ready.peek().litToBoolean)(dut.io.cpu.r.bits.data.peek().litValue)
        dut.clock.step()
        if (rFire) pendingRead = None
        if (arFire) pendingRead = Some(arAddress)
        cpuRead
      }

      def acceptRead(address: BigInt): Unit = {
        dut.io.cpu.ar.bits.addr.poke(address)
        dut.io.cpu.ar.bits.size.poke(3)
        dut.io.cpu.ar.bits.prot.poke(4)
        dut.io.cpu.ar.valid.poke(true)
        driveMemory()
        dut.io.cpu.ar.ready.expect(true.B)
        cycle()
        dut.io.cpu.ar.valid.poke(false)
      }

      dut.io.cpu.aw.valid.poke(false)
      dut.io.cpu.w.valid.poke(false)
      dut.io.cpu.ar.valid.poke(false)
      dut.io.cpu.b.ready.poke(true)
      dut.io.cpu.r.ready.poke(true)
      dut.io.maintenanceRequest.poke(false)
      dut.io.maintenanceInvalidate.poke(false)
      dut.reset.poke(true)
      dut.clock.step(2)
      dut.reset.poke(false)

      // 第二笔在第一笔 miss 阻塞时进入 FIFO；维护必须继续服务这两个旧请求。
      acceptRead(base)
      acceptRead(base + 16)
      dut.io.maintenanceRequest.poke(true)
      dut.io.maintenanceInvalidate.poke(true)
      val responses = mutable.ArrayBuffer.empty[BigInt]
      var guard = 0
      while ((!dut.io.maintenanceDone.peek().litToBoolean || responses.size < 2) && guard < 120) {
        cycle().foreach(responses += _)
        guard += 1
      }
      assert(guard < 120, "maintenance must drain already accepted requests")
      assert(responses.toSeq == Seq(firstLine, secondLine))
      dut.io.maintenanceRequest.poke(false)
      cycle()
    }
  }

  it should "keep queued reads ordered when an earlier line miss invalidates S0 prefetch data" in {
    val cache = CacheConfig(
      enabled = true,
      geometry = CacheGeometry(64, 16, CacheMapping.DirectMapped),
      replacement = CacheReplacement.TreePLRU,
      storage = CacheStorage.Registers
    )
    val base = BigInt("80000000", 16)
    val firstLine = BigInt("1111111111111111", 16)
    val secondLine = BigInt("2222222222222222", 16)
    val backing = Map(base -> firstLine, base + 16 -> secondLine)

    simulate(new PipelinedCacheController(cache, 32, 64, 0x80000000L, 0x10000000L,
      readOnly = false, PipelinedCacheQueueConfig.TwoCycleLocal)) { dut =>
      var pendingRead: Option[BigInt] = None

      def driveMemory(): Unit = {
        dut.io.memory.ar.ready.poke(pendingRead.isEmpty)
        dut.io.memory.r.valid.poke(pendingRead.nonEmpty)
        dut.io.memory.r.bits.data.poke(pendingRead.flatMap(backing.get).getOrElse(BigInt(0)))
        dut.io.memory.r.bits.resp.poke(0)
        dut.io.memory.aw.ready.poke(true)
        dut.io.memory.w.ready.poke(true)
        dut.io.memory.b.valid.poke(false)
        dut.io.memory.b.bits.resp.poke(0)
      }

      def cycle(): Option[BigInt] = {
        driveMemory()
        val arFire = dut.io.memory.ar.valid.peek().litToBoolean && pendingRead.isEmpty
        val arAddress = dut.io.memory.ar.bits.addr.peek().litValue
        val rFire = pendingRead.nonEmpty && dut.io.memory.r.ready.peek().litToBoolean
        val cpuRead = Option.when(dut.io.cpu.r.valid.peek().litToBoolean &&
          dut.io.cpu.r.ready.peek().litToBoolean)(dut.io.cpu.r.bits.data.peek().litValue)
        dut.clock.step()
        if (rFire) pendingRead = None
        if (arFire) pendingRead = Some(arAddress)
        cpuRead
      }

      def issue(address: BigInt): Option[BigInt] = {
        dut.io.cpu.ar.bits.addr.poke(address)
        dut.io.cpu.ar.bits.size.poke(3)
        dut.io.cpu.ar.bits.prot.poke(4)
        dut.io.cpu.ar.valid.poke(true)
        driveMemory()
        dut.io.cpu.ar.ready.expect(true.B)
        val result = cycle()
        dut.io.cpu.ar.valid.poke(false)
        result
      }

      dut.io.cpu.aw.valid.poke(false)
      dut.io.cpu.w.valid.poke(false)
      dut.io.cpu.ar.valid.poke(false)
      dut.io.cpu.b.ready.poke(true)
      dut.io.cpu.r.ready.poke(true)
      dut.io.maintenanceRequest.poke(false)
      dut.io.maintenanceInvalidate.poke(false)
      dut.reset.poke(true)
      dut.clock.step(2)
      dut.reset.poke(false)

      // 首个 cold miss 的下一拍立刻接收另一 set 的读，请求不能因 S0 预读失效而丢失或串线。
      val coldResponses = mutable.ArrayBuffer.empty[BigInt]
      issue(base).foreach(coldResponses += _)
      issue(base + 16).foreach(coldResponses += _)
      (0 until 80).foreach(_ => cycle().foreach(coldResponses += _))
      assert(coldResponses.toSeq == Seq(firstLine, secondLine))

      // 两条 line 已热，连续 AR 必须保持每拍一笔并按请求顺序返回各自的数据。
      val warmResponses = mutable.ArrayBuffer.empty[BigInt]
      issue(base).foreach(warmResponses += _)
      issue(base + 16).foreach(warmResponses += _)
      (0 until 8).foreach(_ => cycle().foreach(warmResponses += _))
      assert(warmResponses.toSeq == Seq(firstLine, secondLine))
    }
  }

  it should "refill adjacent 64-byte lines through the synchronous Auto array" in {
    val cache = CacheConfig(
      enabled = true,
      geometry = CacheGeometry(4 * 1024, 64, CacheMapping.SetAssociative(2)),
      replacement = CacheReplacement.TreePLRU,
      storage = CacheStorage.Auto
    )
    val base = BigInt("80000000", 16)
    val firstLine = (0 until 8).foldLeft(BigInt(0)) { (line, lane) =>
      line | ((BigInt("1111111111111111", 16) + lane) << (lane * 64))
    }
    val secondLine = (0 until 8).foldLeft(BigInt(0)) { (line, lane) =>
      line | ((BigInt("2222222222222222", 16) + lane) << (lane * 64))
    }
    val backing = Map(base -> firstLine, base + 64 -> secondLine)

    simulate(new PipelinedCacheController(cache, 32, 64, 0x80000000L, 0x10000000L,
      readOnly = true, PipelinedCacheQueueConfig.TwoCycleLocal, memoryDataWidth = 512)) { dut =>
      var pendingRead: Option[BigInt] = None

      def driveMemory(): Unit = {
        dut.io.memory.ar.ready.poke(pendingRead.isEmpty)
        dut.io.memory.r.valid.poke(pendingRead.nonEmpty)
        dut.io.memory.r.bits.data.poke(pendingRead.flatMap(backing.get).getOrElse(BigInt(0)))
        dut.io.memory.r.bits.resp.poke(0)
        dut.io.memory.aw.ready.poke(true)
        dut.io.memory.w.ready.poke(true)
        dut.io.memory.b.valid.poke(false)
        dut.io.memory.b.bits.resp.poke(0)
      }

      def cycle(): Option[BigInt] = {
        driveMemory()
        val arFire = dut.io.memory.ar.valid.peek().litToBoolean && pendingRead.isEmpty
        val arAddress = dut.io.memory.ar.bits.addr.peek().litValue
        val rFire = pendingRead.nonEmpty && dut.io.memory.r.ready.peek().litToBoolean
        val cpuRead = Option.when(dut.io.cpu.r.valid.peek().litToBoolean &&
          dut.io.cpu.r.ready.peek().litToBoolean)(dut.io.cpu.r.bits.data.peek().litValue)
        dut.clock.step()
        if (rFire) pendingRead = None
        if (arFire) pendingRead = Some(arAddress)
        cpuRead
      }

      def issue(address: BigInt): Unit = {
        dut.io.cpu.ar.bits.addr.poke(address)
        dut.io.cpu.ar.bits.size.poke(3)
        dut.io.cpu.ar.bits.prot.poke(4)
        dut.io.cpu.ar.valid.poke(true)
        driveMemory()
        dut.io.cpu.ar.ready.expect(true.B)
        cycle()
        dut.io.cpu.ar.valid.poke(false)
      }

      dut.io.cpu.aw.valid.poke(false)
      dut.io.cpu.w.valid.poke(false)
      dut.io.cpu.ar.valid.poke(false)
      dut.io.cpu.b.ready.poke(true)
      dut.io.cpu.r.ready.poke(true)
      dut.io.maintenanceRequest.poke(false)
      dut.io.maintenanceInvalidate.poke(false)
      dut.reset.poke(true)
      dut.clock.step(2)
      dut.reset.poke(false)

      val responses = mutable.ArrayBuffer.empty[BigInt]
      issue(base + 56)
      issue(base + 64)
      (0 until 120).foreach(_ => cycle().foreach(responses += _))
      assert(responses.toSeq == Seq(BigInt("1111111111111118", 16),
        BigInt("2222222222222222", 16)))
    }
  }

  it should "not let a younger warm hit pass a preceding miss during refill" in {
    val cache = CacheConfig(
      enabled = true,
      geometry = CacheGeometry(64, 16, CacheMapping.DirectMapped),
      replacement = CacheReplacement.TreePLRU,
      storage = CacheStorage.Registers
    )
    val missAddress = BigInt("80000000", 16)
    val warmAddress = missAddress + 16
    val missData = BigInt("1111111111111111", 16)
    val warmData = BigInt("2222222222222222", 16)
    val backing = Map(missAddress -> missData, warmAddress -> warmData)

    simulate(new PipelinedCacheController(cache, 32, 64, 0x80000000L, 0x10000000L,
      readOnly = true, PipelinedCacheQueueConfig.TwoCycleLocal)) { dut =>
      var pendingRead: Option[BigInt] = None

      def driveMemory(): Unit = {
        dut.io.memory.ar.ready.poke(pendingRead.isEmpty)
        dut.io.memory.r.valid.poke(pendingRead.nonEmpty)
        dut.io.memory.r.bits.data.poke(pendingRead.flatMap(backing.get).getOrElse(BigInt(0)))
        dut.io.memory.r.bits.resp.poke(0)
        dut.io.memory.aw.ready.poke(true)
        dut.io.memory.w.ready.poke(true)
        dut.io.memory.b.valid.poke(false)
        dut.io.memory.b.bits.resp.poke(0)
      }

      def cycle(): Option[BigInt] = {
        driveMemory()
        val arFire = dut.io.memory.ar.valid.peek().litToBoolean && pendingRead.isEmpty
        val arAddress = dut.io.memory.ar.bits.addr.peek().litValue
        val rFire = pendingRead.nonEmpty && dut.io.memory.r.ready.peek().litToBoolean
        val response = Option.when(dut.io.cpu.r.valid.peek().litToBoolean &&
          dut.io.cpu.r.ready.peek().litToBoolean)(dut.io.cpu.r.bits.data.peek().litValue)
        dut.clock.step()
        if (rFire) pendingRead = None
        if (arFire) pendingRead = Some(arAddress)
        response
      }

      def submit(address: BigInt): Unit = {
        dut.io.cpu.ar.bits.addr.poke(address)
        dut.io.cpu.ar.bits.size.poke(3)
        dut.io.cpu.ar.bits.prot.poke(4)
        dut.io.cpu.ar.valid.poke(true)
        driveMemory()
        dut.io.cpu.ar.ready.expect(true.B)
        cycle()
        dut.io.cpu.ar.valid.poke(false)
      }

      dut.io.cpu.aw.valid.poke(false)
      dut.io.cpu.w.valid.poke(false)
      dut.io.cpu.ar.valid.poke(false)
      dut.io.cpu.b.ready.poke(true)
      dut.io.cpu.r.ready.poke(true)
      dut.io.maintenanceRequest.poke(false)
      dut.io.maintenanceInvalidate.poke(false)
      dut.reset.poke(true)
      dut.clock.step(2)
      dut.reset.poke(false)

      // 先把后一笔所在 line 预热，再让它紧跟一笔 cold miss 进入控制器。
      submit(warmAddress)
      assert((0 until 80).iterator.map(_ => cycle()).collectFirst { case Some(value) => value }
        .contains(warmData))

      val responses = mutable.ArrayBuffer.empty[BigInt]
      submit(missAddress)
      submit(warmAddress)
      (0 until 80).foreach(_ => cycle().foreach(responses += _))
      assert(responses.toSeq == Seq(missData, warmData))
    }
  }

  it should "install a write-back store miss in the synchronous Auto array" in {
    val cache = CacheConfig(
      enabled = true,
      geometry = CacheGeometry(4 * 1024, 64, CacheMapping.SetAssociative(2)),
      replacement = CacheReplacement.TreePLRU,
      policy = CachePolicy(
        CacheReadMissPolicy.ReadAllocate,
        CacheWritePolicy.WriteBack,
        CacheWriteMissPolicy.WriteAllocate
      ),
      storage = CacheStorage.Auto
    )
    val base = BigInt("80000000", 16)
    val initialLine = (0 until 8).foldLeft(BigInt(0)) { (line, lane) =>
      line | (BigInt("f0f0f0f0f0f0f0f0", 16) << (lane * 64))
    }
    val firstStore = BigInt("0123456789abcdef", 16)
    val secondStore = BigInt("fedcba9876543210", 16)

    simulate(new PipelinedCacheController(cache, 32, 64, 0x80000000L, 0x10000000L,
      readOnly = false, PipelinedCacheQueueConfig.TwoCycleLocal, memoryDataWidth = 512)) { dut =>
      val memory = mutable.Map(base -> initialLine)
      var pendingRead: Option[BigInt] = None
      var acceptedStores = 0
      var observedResponses = 0

      def driveMemory(): Unit = {
        dut.io.memory.ar.ready.poke(pendingRead.isEmpty)
        dut.io.memory.r.valid.poke(pendingRead.nonEmpty)
        dut.io.memory.r.bits.data.poke(pendingRead.flatMap(memory.get).getOrElse(BigInt(0)))
        dut.io.memory.r.bits.resp.poke(0)
        dut.io.memory.aw.ready.poke(true)
        dut.io.memory.w.ready.poke(true)
        dut.io.memory.b.valid.poke(false)
        dut.io.memory.b.bits.resp.poke(0)
      }

      def cycle(): (Option[BigInt], Boolean) = {
        driveMemory()
        val arFire = dut.io.memory.ar.valid.peek().litToBoolean &&
          dut.io.memory.ar.ready.peek().litToBoolean
        val arAddress = dut.io.memory.ar.bits.addr.peek().litValue
        val rFire = dut.io.memory.r.valid.peek().litToBoolean &&
          dut.io.memory.r.ready.peek().litToBoolean
        val cpuRead = Option.when(dut.io.cpu.r.valid.peek().litToBoolean &&
          dut.io.cpu.r.ready.peek().litToBoolean)(dut.io.cpu.r.bits.data.peek().litValue)
        val cpuWrite = dut.io.cpu.b.valid.peek().litToBoolean &&
          dut.io.cpu.b.ready.peek().litToBoolean
        if (cpuWrite) observedResponses += 1
        dut.clock.step()
        if (rFire) pendingRead = None
        if (arFire) pendingRead = Some(arAddress)
        (cpuRead, cpuWrite)
      }

      def initialize(): Unit = {
        dut.io.cpu.aw.valid.poke(false)
        dut.io.cpu.w.valid.poke(false)
        dut.io.cpu.ar.valid.poke(false)
        dut.io.cpu.b.ready.poke(true)
        dut.io.cpu.r.ready.poke(true)
        dut.io.maintenanceRequest.poke(false)
        dut.io.maintenanceInvalidate.poke(false)
        dut.reset.poke(true)
        dut.clock.step(2)
        dut.reset.poke(false)
      }

      def submitStore(address: BigInt, data: BigInt): Unit = {
        dut.io.cpu.aw.bits.addr.poke(address)
        dut.io.cpu.aw.bits.size.poke(3)
        dut.io.cpu.aw.bits.prot.poke(0)
        dut.io.cpu.w.bits.data.poke(data)
        dut.io.cpu.w.bits.strb.poke(0xff)
        dut.io.cpu.aw.valid.poke(true)
        dut.io.cpu.w.valid.poke(true)
        driveMemory()
        var guard = 0
        while ((!dut.io.cpu.aw.ready.peek().litToBoolean ||
          !dut.io.cpu.w.ready.peek().litToBoolean) && guard < 160) {
          cycle()
          guard += 1
          driveMemory()
        }
        assert(dut.io.cpu.aw.ready.peek().litToBoolean && dut.io.cpu.w.ready.peek().litToBoolean,
          f"store request timed out at 0x$address%x")
        assert(dut.io.cpu.aw.valid.peek().litToBoolean && dut.io.cpu.w.valid.peek().litToBoolean)
        acceptedStores += 1
        cycle()
        dut.io.cpu.aw.valid.poke(false)
        dut.io.cpu.w.valid.poke(false)
      }

      def drainWrites(count: Int): Unit = {
        (0 until 160).takeWhile(_ => observedResponses < count).foreach(_ => cycle())
        assert(observedResponses >= count,
          s"write responses timed out after receiving $count stores: accepted=$acceptedStores")
      }

      def read(address: BigInt): BigInt = {
        dut.io.cpu.ar.bits.addr.poke(address)
        dut.io.cpu.ar.bits.size.poke(3)
        dut.io.cpu.ar.bits.prot.poke(4)
        dut.io.cpu.ar.valid.poke(true)
        driveMemory()
        dut.io.cpu.ar.ready.expect(true.B)
        cycle()
        dut.io.cpu.ar.valid.poke(false)
        (0 until 160).iterator.map(_ => cycle()._1).collectFirst {
          case Some(value) => value
        }.getOrElse(fail(f"read timed out at 0x$address%x"))
      }

      initialize()
      // 在首笔 miss refill 期间连续接收同一 line 的年轻 store，覆盖栈帧常见的访问形态。
      submitStore(base + 8, firstStore)
      submitStore(base, secondStore)
      submitStore(base + 16, firstStore ^ secondStore)
      submitStore(base + 24, secondStore ^ firstStore)
      drainWrites(4)
      assert(read(base) == secondStore)
      assert(read(base + 8) == firstStore)
      assert(read(base + 16) == (firstStore ^ secondStore))
      assert(read(base + 24) == (secondStore ^ firstStore))
    }
  }

  it should "refill and write back a 64-byte line with one 512-bit memory beat while keeping the CPU port 64-bit" in {
    val cache = CacheConfig(
      enabled = true,
      geometry = CacheGeometry(64, 64, CacheMapping.DirectMapped),
      replacement = CacheReplacement.TreePLRU,
      storage = CacheStorage.Registers
    )
    val base = BigInt("80000000", 16)
    val memoryLine = (0 until 8).foldLeft(BigInt(0)) { (line, lane) =>
      line | ((BigInt("1111111111111111", 16) + lane) << (lane * 64))
    }

    simulate(new CacheController(cache, 32, 64, 0x80000000L, 0x10000000L,
      readOnly = false, memoryDataWidth = 512)) { dut =>
      val nextLine = BigInt(0)
      val memory = mutable.Map(base -> memoryLine, base + 64 -> nextLine)
      var pendingRead: Option[BigInt] = None
      var capturedAw: Option[BigInt] = None
      var capturedW: Option[(BigInt, BigInt, BigInt)] = None
      var pendingWriteResponse = false
      var downstreamReads = 0
      var downstreamWrites = 0
      var lastReadAddress = BigInt(0)
      var lastReadSize = BigInt(0)
      var lastWriteAddress = BigInt(0)
      var lastWriteData = BigInt(0)
      var lastWriteStrobe = BigInt(0)
      var lastWriteSize = BigInt(0)

      def driveMemory(): Unit = {
        dut.io.memory.ar.ready.poke(pendingRead.isEmpty)
        dut.io.memory.r.valid.poke(pendingRead.nonEmpty)
        dut.io.memory.r.bits.data.poke(pendingRead.fold(BigInt(0))(memory))
        dut.io.memory.r.bits.resp.poke(0)
        dut.io.memory.aw.ready.poke(capturedAw.isEmpty && !pendingWriteResponse)
        dut.io.memory.w.ready.poke(capturedW.isEmpty && !pendingWriteResponse)
        dut.io.memory.b.valid.poke(pendingWriteResponse)
        dut.io.memory.b.bits.resp.poke(0)
      }

      def cycle(): CycleResult = {
        driveMemory()
        val arFire = dut.io.memory.ar.valid.peek().litToBoolean && pendingRead.isEmpty
        val arAddress = dut.io.memory.ar.bits.addr.peek().litValue
        val arSize = dut.io.memory.ar.bits.size.peek().litValue
        val rFire = pendingRead.nonEmpty && dut.io.memory.r.ready.peek().litToBoolean
        val awFire = dut.io.memory.aw.valid.peek().litToBoolean &&
          capturedAw.isEmpty && !pendingWriteResponse
        val awAddress = dut.io.memory.aw.bits.addr.peek().litValue
        val awSize = dut.io.memory.aw.bits.size.peek().litValue
        val wFire = dut.io.memory.w.valid.peek().litToBoolean &&
          capturedW.isEmpty && !pendingWriteResponse
        val wPayload = (
          dut.io.memory.w.bits.data.peek().litValue,
          dut.io.memory.w.bits.strb.peek().litValue,
          dut.io.memory.aw.bits.size.peek().litValue
        )
        val bFire = pendingWriteResponse && dut.io.memory.b.ready.peek().litToBoolean
        val cpuRead = Option.when(dut.io.cpu.r.valid.peek().litToBoolean &&
          dut.io.cpu.r.ready.peek().litToBoolean)(dut.io.cpu.r.bits.data.peek().litValue)
        val cpuWrite = dut.io.cpu.b.valid.peek().litToBoolean && dut.io.cpu.b.ready.peek().litToBoolean
        dut.clock.step()
        if (rFire) pendingRead = None
        if (arFire) {
          pendingRead = Some(arAddress)
          downstreamReads += 1
          lastReadAddress = arAddress
          lastReadSize = arSize
        }
        if (bFire) pendingWriteResponse = false
        if (awFire) capturedAw = Some(awAddress)
        if (wFire) capturedW = Some(wPayload)
        for (address <- capturedAw; (data, strobe, size) <- capturedW if !pendingWriteResponse) {
          memory(address) = data
          capturedAw = None
          capturedW = None
          pendingWriteResponse = true
          downstreamWrites += 1
          lastWriteAddress = address
          lastWriteData = data
          lastWriteStrobe = strobe
          lastWriteSize = size
        }
        CycleResult(cpuRead, cpuWrite)
      }

      def read(address: BigInt): BigInt = {
        dut.io.cpu.ar.bits.addr.poke(address)
        dut.io.cpu.ar.bits.size.poke(3)
        dut.io.cpu.ar.bits.prot.poke(4)
        dut.io.cpu.ar.valid.poke(true)
        driveMemory()
        dut.io.cpu.ar.ready.expect(true.B)
        cycle()
        dut.io.cpu.ar.valid.poke(false)
        (0 until 200).iterator.map(_ => cycle().read).collectFirst { case Some(value) => value }
          .getOrElse(fail(s"wide cache read timed out at 0x$address%x"))
      }

      def write(address: BigInt, data: BigInt): Unit = {
        dut.io.cpu.aw.bits.addr.poke(address)
        dut.io.cpu.aw.bits.size.poke(3)
        dut.io.cpu.aw.bits.prot.poke(0)
        dut.io.cpu.w.bits.data.poke(data)
        dut.io.cpu.w.bits.strb.poke(0xff)
        dut.io.cpu.aw.valid.poke(true)
        dut.io.cpu.w.valid.poke(true)
        driveMemory()
        dut.io.cpu.aw.ready.expect(true.B)
        dut.io.cpu.w.ready.expect(true.B)
        cycle()
        dut.io.cpu.aw.valid.poke(false)
        dut.io.cpu.w.valid.poke(false)
        assert((0 until 200).iterator.map(_ => cycle().writeResponse).contains(true),
          f"wide cache write timed out at 0x$address%x")
      }

      dut.io.cpu.aw.valid.poke(false)
      dut.io.cpu.w.valid.poke(false)
      dut.io.cpu.ar.valid.poke(false)
      dut.io.cpu.b.ready.poke(true)
      dut.io.cpu.r.ready.poke(true)
      dut.io.maintenanceRequest.poke(false)
      dut.io.maintenanceInvalidate.poke(false)
      dut.reset.poke(true)
      dut.clock.step(2)
      dut.reset.poke(false)

      assert(read(base + 56) == BigInt("1111111111111118", 16))
      assert(downstreamReads == 1)
      assert(lastReadAddress == base)
      assert(lastReadSize == 6)

      assert(read(base) == BigInt("1111111111111111", 16))
      assert(downstreamReads == 1)

      val dirtyValue = BigInt("0123456789abcdef", 16)
      write(base + 56, dirtyValue)
      assert(downstreamWrites == 0, "a write-back hit must not access HBM immediately")
      assert(read(base + 64) == 0)
      assert(downstreamReads == 2)
      assert(downstreamWrites == 1)
      assert(lastWriteAddress == base)
      assert(lastWriteSize == 6)
      assert(lastWriteStrobe == ((BigInt(1) << 64) - 1))
      assert(lastWriteData ==
        ((memoryLine & ~(BigInt("ffffffffffffffff", 16) << (56 * 8))) | (dirtyValue << (56 * 8))))
    }
  }

  it should "preserve CPU word lanes when bypassing tail MMIO bytes over a 512-bit memory port" in {
    val cache = CacheConfig(
      enabled = true,
      geometry = CacheGeometry(64, 64, CacheMapping.DirectMapped),
      replacement = CacheReplacement.TreePLRU,
      storage = CacheStorage.Registers
    )
    val mmioAddress = BigInt("a100003c", 16)
    val cpuStoreData = BigInt("cafebabe", 16) << 32
    val cpuStoreStrobe = BigInt("f0", 16)
    val wordBaseShift = 56 * 8

    simulate(new CacheController(cache, 32, 64, 0x80000000L, 0x10000000L,
      readOnly = false, memoryDataWidth = 512)) { dut =>
      dut.io.cpu.aw.valid.poke(false)
      dut.io.cpu.w.valid.poke(false)
      dut.io.cpu.ar.valid.poke(false)
      dut.io.cpu.b.ready.poke(true)
      dut.io.cpu.r.ready.poke(true)
      dut.io.maintenanceRequest.poke(false)
      dut.io.maintenanceInvalidate.poke(false)
      dut.io.memory.aw.ready.poke(true)
      dut.io.memory.w.ready.poke(true)
      dut.io.memory.ar.ready.poke(true)
      dut.io.memory.b.valid.poke(false)
      dut.io.memory.b.bits.resp.poke(0)
      dut.io.memory.r.valid.poke(false)
      dut.io.memory.r.bits.data.poke(0)
      dut.io.memory.r.bits.resp.poke(0)
      dut.reset.poke(true)
      dut.clock.step(2)
      dut.reset.poke(false)

      dut.io.cpu.aw.bits.addr.poke(mmioAddress)
      dut.io.cpu.aw.bits.size.poke(2)
      dut.io.cpu.aw.bits.prot.poke(0)
      dut.io.cpu.w.bits.data.poke(cpuStoreData)
      dut.io.cpu.w.bits.strb.poke(cpuStoreStrobe)
      dut.io.cpu.aw.valid.poke(true)
      dut.io.cpu.w.valid.poke(true)
      dut.io.cpu.aw.ready.expect(true.B)
      dut.io.cpu.w.ready.expect(true.B)
      dut.clock.step()
      dut.io.cpu.aw.valid.poke(false)
      dut.io.cpu.w.valid.poke(false)

      dut.io.memory.aw.valid.expect(true.B)
      dut.io.memory.aw.bits.addr.expect(mmioAddress)
      dut.io.memory.w.valid.expect(true.B)
      dut.io.memory.w.bits.data.expect(cpuStoreData << wordBaseShift)
      dut.io.memory.w.bits.strb.expect(cpuStoreStrobe << 56)
      dut.clock.step()
      dut.io.memory.b.valid.poke(true)
      dut.io.memory.b.ready.expect(true.B)
      dut.clock.step()
      dut.io.memory.b.valid.poke(false)
      dut.io.cpu.b.valid.expect(true.B)
      dut.clock.step()

      dut.io.cpu.ar.bits.addr.poke(mmioAddress)
      dut.io.cpu.ar.bits.size.poke(2)
      dut.io.cpu.ar.bits.prot.poke(0)
      dut.io.cpu.ar.valid.poke(true)
      dut.io.cpu.ar.ready.expect(true.B)
      dut.clock.step()
      dut.io.cpu.ar.valid.poke(false)
      dut.io.memory.ar.valid.expect(true.B)
      dut.io.memory.ar.bits.addr.expect(mmioAddress)
      dut.clock.step()

      val cpuReadData = BigInt("11223344", 16) << 32
      dut.io.memory.r.bits.data.poke(cpuReadData << wordBaseShift)
      dut.io.memory.r.valid.poke(true)
      dut.io.memory.r.ready.expect(true.B)
      dut.clock.step()
      dut.io.memory.r.valid.poke(false)
      dut.io.cpu.r.valid.expect(true.B)
      dut.io.cpu.r.bits.data.expect(cpuReadData)
      dut.clock.step()
    }
  }

  "CacheController" should "refill cold lines, hit locally, write back dirty victims, and bypass MMIO" in {
    val cache = CacheConfig(
      enabled = true,
      geometry = CacheGeometry(16, 16, CacheMapping.DirectMapped),
      replacement = CacheReplacement.TreePLRU,
      policy = CachePolicy(
        CacheReadMissPolicy.ReadAllocate,
        CacheWritePolicy.WriteBack,
        CacheWriteMissPolicy.WriteAllocate
      ),
      storage = CacheStorage.Registers
    )

    simulate(new CacheController(cache, 32, 64, 0x80000000L, 0x10000000L, readOnly = false)) { dut =>
      val memory = mutable.Map[BigInt, BigInt](
        BigInt("80000000", 16) -> BigInt("1122334455667788", 16),
        BigInt("80000008", 16) -> BigInt("99aabbccddeeff00", 16),
        BigInt("80000010", 16) -> BigInt("0123456789abcdef", 16),
        BigInt("80000018", 16) -> BigInt("fedcba9876543210", 16),
        BigInt("a0000000", 16) -> BigInt("55aa", 16)
      )
      var pendingRead: Option[BigInt] = None
      var capturedAw: Option[BigInt] = None
      var capturedW: Option[(BigInt, BigInt)] = None
      var pendingWriteResponse = false
      var downstreamReads = 0
      var downstreamWrites = 0

      dut.io.cpu.aw.valid.poke(false)
      dut.io.cpu.w.valid.poke(false)
      dut.io.cpu.ar.valid.poke(false)
      dut.io.cpu.b.ready.poke(true)
      dut.io.cpu.r.ready.poke(true)
      dut.io.maintenanceRequest.poke(false)
      dut.io.maintenanceInvalidate.poke(false)
      dut.reset.poke(true)
      dut.clock.step(2)
      dut.reset.poke(false)

      def driveMemory(): Unit = {
        dut.io.memory.ar.ready.poke(pendingRead.isEmpty)
        dut.io.memory.r.valid.poke(pendingRead.nonEmpty)
        dut.io.memory.r.bits.data.poke(
          pendingRead.fold(BigInt(0))(address => memory.getOrElse(address, BigInt(0))))
        dut.io.memory.r.bits.resp.poke(0)
        dut.io.memory.aw.ready.poke(capturedAw.isEmpty && !pendingWriteResponse)
        dut.io.memory.w.ready.poke(capturedW.isEmpty && !pendingWriteResponse)
        dut.io.memory.b.valid.poke(pendingWriteResponse)
        dut.io.memory.b.bits.resp.poke(0)
      }

      def cycle(): CycleResult = {
        driveMemory()
        val arFire = dut.io.memory.ar.valid.peek().litToBoolean && pendingRead.isEmpty
        val arAddress = dut.io.memory.ar.bits.addr.peek().litValue
        val rFire = pendingRead.nonEmpty && dut.io.memory.r.ready.peek().litToBoolean
        val awFire = dut.io.memory.aw.valid.peek().litToBoolean &&
          capturedAw.isEmpty && !pendingWriteResponse
        val awAddress = dut.io.memory.aw.bits.addr.peek().litValue
        val wFire = dut.io.memory.w.valid.peek().litToBoolean &&
          capturedW.isEmpty && !pendingWriteResponse
        val wPayload = dut.io.memory.w.bits.data.peek().litValue -> dut.io.memory.w.bits.strb.peek().litValue
        val bFire = pendingWriteResponse && dut.io.memory.b.ready.peek().litToBoolean
        val cpuReadFire = dut.io.cpu.r.valid.peek().litToBoolean && dut.io.cpu.r.ready.peek().litToBoolean
        val cpuReadData = dut.io.cpu.r.bits.data.peek().litValue
        val cpuWriteFire = dut.io.cpu.b.valid.peek().litToBoolean && dut.io.cpu.b.ready.peek().litToBoolean

        dut.clock.step()

        if (rFire) pendingRead = None
        if (arFire) {
          pendingRead = Some(arAddress)
          downstreamReads += 1
        }
        if (bFire) pendingWriteResponse = false
        if (awFire) capturedAw = Some(awAddress)
        if (wFire) capturedW = Some(wPayload)
        for (address <- capturedAw; (data, strobe) <- capturedW if !pendingWriteResponse) {
          val old = memory.getOrElse(address, BigInt(0))
          var updated = old
          for (lane <- 0 until 8 if ((strobe >> lane) & 1) == 1) {
            val mask = BigInt(0xff) << (lane * 8)
            updated = (updated & ~mask) | (data & mask)
          }
          memory(address) = updated
          capturedAw = None
          capturedW = None
          pendingWriteResponse = true
          downstreamWrites += 1
        }
        CycleResult(Option.when(cpuReadFire)(cpuReadData), cpuWriteFire)
      }

      def read(address: BigInt): BigInt = {
        dut.io.cpu.ar.bits.addr.poke(address)
        dut.io.cpu.ar.bits.size.poke(3)
        dut.io.cpu.ar.bits.prot.poke(0)
        dut.io.cpu.ar.valid.poke(true)
        driveMemory()
        dut.io.cpu.ar.ready.expect(true.B)
        cycle()
        dut.io.cpu.ar.valid.poke(false)
        var response: Option[BigInt] = None
        var cycles = 0
        while (response.isEmpty && cycles < 200) {
          response = cycle().read
          cycles += 1
        }
        assert(response.nonEmpty, s"cache read timed out at 0x${address.toString(16)}")
        response.get
      }

      def write(address: BigInt, data: BigInt, strobe: BigInt): Unit = {
        dut.io.cpu.aw.bits.addr.poke(address)
        dut.io.cpu.aw.bits.size.poke(3)
        dut.io.cpu.aw.bits.prot.poke(0)
        dut.io.cpu.w.bits.data.poke(data)
        dut.io.cpu.w.bits.strb.poke(strobe)
        dut.io.cpu.aw.valid.poke(true)
        dut.io.cpu.w.valid.poke(true)
        driveMemory()
        dut.io.cpu.aw.ready.expect(true.B)
        dut.io.cpu.w.ready.expect(true.B)
        cycle()
        dut.io.cpu.aw.valid.poke(false)
        dut.io.cpu.w.valid.poke(false)
        var completed = false
        var cycles = 0
        while (!completed && cycles < 200) {
          completed = cycle().writeResponse
          cycles += 1
        }
        assert(completed, s"cache write timed out at 0x${address.toString(16)}")
      }

      val base = BigInt("80000000", 16)
      assert(read(base) == BigInt("1122334455667788", 16))
      assert(downstreamReads == 2)
      assert(read(base + 8) == BigInt("99aabbccddeeff00", 16))
      assert(downstreamReads == 2, "a cache hit must not issue another downstream transaction")

      write(base, BigInt("00000000deadbeef", 16), 0x0f)
      assert(downstreamWrites == 0)
      assert(read(base) == BigInt("11223344deadbeef", 16))

      assert(read(base + 16) == BigInt("0123456789abcdef", 16))
      assert(downstreamWrites == 2, "dirty eviction must write every line beat")
      assert(memory(base) == BigInt("11223344deadbeef", 16))

      val readsBeforeMmio = downstreamReads
      assert(read(BigInt("a0000000", 16)) == BigInt("55aa", 16))
      assert(downstreamReads == readsBeforeMmio + 1, "MMIO must bypass line refill")

      write(base + 16, BigInt("cafebabe", 16), 0x0f)
      dut.io.maintenanceRequest.poke(true)
      var maintenanceCycles = 0
      while (!dut.io.maintenanceDone.peek().litToBoolean && maintenanceCycles < 300) {
        cycle()
        maintenanceCycles += 1
      }
      assert(maintenanceCycles < 300, "cache maintenance timed out")
      assert(dut.io.drained.peek().litToBoolean)
      assert(memory(base + 16) == BigInt("01234567cafebabe", 16))
      dut.io.maintenanceRequest.poke(false)
      cycle()
    }
  }
}
