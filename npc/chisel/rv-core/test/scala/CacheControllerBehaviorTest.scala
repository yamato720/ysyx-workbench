package npc

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class CacheControllerBehaviorTest extends AnyFlatSpec {
  private case class CycleResult(read: Option[BigInt], writeResponse: Boolean)

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
