package npc

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class CachePolicyBehaviorTest extends AnyFlatSpec {
  private case class CpuResult(read: Option[(BigInt, Int)], write: Option[Int])

  private final class Driver(dut: CacheController, val memory: mutable.Map[BigInt, BigInt]) {
    private var pendingRead: Option[(BigInt, Int)] = None
    private var capturedAw: Option[BigInt] = None
    private var capturedW: Option[(BigInt, BigInt)] = None
    private var pendingWriteResponse: Option[Int] = None
    private val queuedReadResponses = mutable.Queue.empty[Int]
    private val queuedWriteResponses = mutable.Queue.empty[Int]
    var downstreamReads = 0
    var downstreamWrites = 0

    dut.io.cpu.aw.valid.poke(false)
    dut.io.cpu.w.valid.poke(false)
    dut.io.cpu.ar.valid.poke(false)
    dut.io.cpu.b.ready.poke(true)
    dut.io.cpu.r.ready.poke(true)
    dut.io.maintenanceRequest.poke(false)
    dut.io.maintenanceInvalidate.poke(false)

    def failNextRead(response: Int = 2): Unit = queuedReadResponses.enqueue(response)
    def failNextWrite(response: Int = 2): Unit = queuedWriteResponses.enqueue(response)

    private def driveMemory(): Unit = {
      dut.io.memory.ar.ready.poke(pendingRead.isEmpty)
      dut.io.memory.r.valid.poke(pendingRead.nonEmpty)
      dut.io.memory.r.bits.data.poke(pendingRead.fold(BigInt(0)) {
        case (address, _) => memory.getOrElse(address, BigInt(0))
      })
      dut.io.memory.r.bits.resp.poke(pendingRead.fold(0)(_._2))
      dut.io.memory.aw.ready.poke(capturedAw.isEmpty && pendingWriteResponse.isEmpty)
      dut.io.memory.w.ready.poke(capturedW.isEmpty && pendingWriteResponse.isEmpty)
      dut.io.memory.b.valid.poke(pendingWriteResponse.nonEmpty)
      dut.io.memory.b.bits.resp.poke(pendingWriteResponse.getOrElse(0))
    }

    private def cycle(): CpuResult = {
      driveMemory()
      val arFire = dut.io.memory.ar.valid.peek().litToBoolean && pendingRead.isEmpty
      val arAddress = dut.io.memory.ar.bits.addr.peek().litValue
      val rFire = pendingRead.nonEmpty && dut.io.memory.r.ready.peek().litToBoolean
      val awFire = dut.io.memory.aw.valid.peek().litToBoolean &&
        capturedAw.isEmpty && pendingWriteResponse.isEmpty
      val awAddress = dut.io.memory.aw.bits.addr.peek().litValue
      val wFire = dut.io.memory.w.valid.peek().litToBoolean &&
        capturedW.isEmpty && pendingWriteResponse.isEmpty
      val wPayload = dut.io.memory.w.bits.data.peek().litValue ->
        dut.io.memory.w.bits.strb.peek().litValue
      val bFire = pendingWriteResponse.nonEmpty && dut.io.memory.b.ready.peek().litToBoolean
      val cpuRead = Option.when(dut.io.cpu.r.valid.peek().litToBoolean)(
        dut.io.cpu.r.bits.data.peek().litValue -> dut.io.cpu.r.bits.resp.peek().litValue.toInt)
      val cpuWrite = Option.when(dut.io.cpu.b.valid.peek().litToBoolean)(
        dut.io.cpu.b.bits.resp.peek().litValue.toInt)

      dut.clock.step()

      if (rFire) pendingRead = None
      if (arFire) {
        val response = if (queuedReadResponses.nonEmpty) queuedReadResponses.dequeue() else 0
        pendingRead = Some(arAddress -> response)
        downstreamReads += 1
      }
      if (bFire) pendingWriteResponse = None
      if (awFire) capturedAw = Some(awAddress)
      if (wFire) capturedW = Some(wPayload)
      for (address <- capturedAw; (data, strobe) <- capturedW if pendingWriteResponse.isEmpty) {
        val response = if (queuedWriteResponses.nonEmpty) queuedWriteResponses.dequeue() else 0
        if (response == 0) {
          var updated = memory.getOrElse(address, BigInt(0))
          for (lane <- 0 until 4 if ((strobe >> lane) & 1) == 1) {
            val mask = BigInt(0xff) << (lane * 8)
            updated = (updated & ~mask) | (data & mask)
          }
          memory(address) = updated
        }
        capturedAw = None
        capturedW = None
        pendingWriteResponse = Some(response)
        downstreamWrites += 1
      }
      CpuResult(cpuRead, cpuWrite)
    }

    def read(address: BigInt, size: Int = 2): (BigInt, Int) = {
      dut.io.cpu.ar.bits.addr.poke(address)
      dut.io.cpu.ar.bits.size.poke(size)
      dut.io.cpu.ar.bits.prot.poke(0)
      dut.io.cpu.ar.valid.poke(true)
      driveMemory()
      dut.io.cpu.ar.ready.expect(true.B)
      cycle()
      dut.io.cpu.ar.valid.poke(false)
      var result: Option[(BigInt, Int)] = None
      var cycles = 0
      while (result.isEmpty && cycles < 200) {
        result = cycle().read
        cycles += 1
      }
      assert(result.nonEmpty, f"read timed out at 0x$address%x")
      result.get
    }

    def write(address: BigInt, data: BigInt, strobe: BigInt, size: Int = 2): Int = {
      dut.io.cpu.aw.bits.addr.poke(address)
      dut.io.cpu.aw.bits.size.poke(size)
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
      var result: Option[Int] = None
      var cycles = 0
      while (result.isEmpty && cycles < 200) {
        result = cycle().write
        cycles += 1
      }
      assert(result.nonEmpty, f"write timed out at 0x$address%x")
      result.get
    }
  }

  private val base = BigInt("80000000", 16)
  private def memory = mutable.Map[BigInt, BigInt](
    base -> BigInt("11223344", 16),
    (base + 4) -> BigInt("55667788", 16),
    (base + 8) -> BigInt("99aabbcc", 16),
    (base + 12) -> BigInt("ddeeff00", 16)
  )

  private def cache(policy: CachePolicy): CacheConfig = CacheConfig(
    enabled = true,
    geometry = CacheGeometry(16, 16, CacheMapping.DirectMapped),
    replacement = CacheReplacement.LRU,
    policy = policy,
    storage = CacheStorage.Registers
  )

  "CacheController policies" should "honor write-through and 32-bit byte, halfword, and word strobes" in {
    val policy = CachePolicy(write = CacheWritePolicy.WriteThrough)
    simulate(new CacheController(cache(policy), 32, 32, 0x80000000L, 0x10000000L, readOnly = false)) { dut =>
      val driver = new Driver(dut, memory)
      dut.reset.poke(true)
      dut.clock.step(2)
      dut.reset.poke(false)

      assert(driver.read(base) == (BigInt("11223344", 16), 0))
      val refillReads = driver.downstreamReads
      assert(driver.write(base, 0xaa, 0x1, size = 0) == 0)
      assert(driver.read(base) == (BigInt("112233aa", 16), 0))
      assert(driver.write(base, 0xbeef, 0x3, size = 1) == 0)
      assert(driver.read(base) == (BigInt("1122beef", 16), 0))
      assert(driver.write(base, BigInt("deadbeef", 16), 0xf) == 0)
      assert(driver.read(base) == (BigInt("deadbeef", 16), 0))
      assert(driver.downstreamReads == refillReads)
      assert(driver.downstreamWrites == 3)
    }
  }

  it should "bypass write misses when no-write-allocate is selected" in {
    val policy = CachePolicy(writeMiss = CacheWriteMissPolicy.NoWriteAllocate)
    simulate(new CacheController(cache(policy), 32, 32, 0x80000000L, 0x10000000L, readOnly = false)) { dut =>
      val driver = new Driver(dut, memory)
      dut.reset.poke(true)
      dut.clock.step(2)
      dut.reset.poke(false)

      assert(driver.write(base, 0xaa, 0x1, size = 0) == 0)
      assert(driver.downstreamWrites == 1)
      assert(driver.downstreamReads == 0)
      assert(driver.read(base) == (BigInt("112233aa", 16), 0))
      assert(driver.downstreamReads == 4)
    }
  }

  it should "bypass every read miss when read-bypass is selected" in {
    val policy = CachePolicy(readMiss = CacheReadMissPolicy.ReadBypass)
    simulate(new CacheController(cache(policy), 32, 32, 0x80000000L, 0x10000000L, readOnly = false)) { dut =>
      val driver = new Driver(dut, memory)
      dut.reset.poke(true)
      dut.clock.step(2)
      dut.reset.poke(false)

      assert(driver.read(base) == (BigInt("11223344", 16), 0))
      assert(driver.read(base) == (BigInt("11223344", 16), 0))
      assert(driver.downstreamReads == 2)
    }
  }

  it should "propagate AXI read and write errors without installing a failed refill" in {
    val policy = CachePolicy(writeMiss = CacheWriteMissPolicy.NoWriteAllocate)
    simulate(new CacheController(cache(policy), 32, 32, 0x80000000L, 0x10000000L, readOnly = false)) { dut =>
      val driver = new Driver(dut, memory)
      dut.reset.poke(true)
      dut.clock.step(2)
      dut.reset.poke(false)

      driver.failNextRead()
      assert(driver.read(base)._2 == 2)
      assert(driver.downstreamReads == 1)
      assert(driver.read(base) == (BigInt("11223344", 16), 0))
      assert(driver.downstreamReads == 5)

      driver.failNextWrite()
      assert(driver.write(base + 16, 0x55, 0x1, size = 0) == 2)
    }
  }

  it should "leave a write-through hit unchanged when the downstream write fails" in {
    val policy = CachePolicy(write = CacheWritePolicy.WriteThrough)
    simulate(new CacheController(cache(policy), 32, 32, 0x80000000L, 0x10000000L, readOnly = false)) { dut =>
      val driver = new Driver(dut, memory)
      dut.reset.poke(true)
      dut.clock.step(2)
      dut.reset.poke(false)

      assert(driver.read(base) == (BigInt("11223344", 16), 0))
      val refillReads = driver.downstreamReads
      driver.failNextWrite()
      assert(driver.write(base, 0xaa, 0x1, size = 0) == 2)
      assert(driver.read(base) == (BigInt("11223344", 16), 0))
      assert(driver.downstreamReads == refillReads)
    }
  }
}
