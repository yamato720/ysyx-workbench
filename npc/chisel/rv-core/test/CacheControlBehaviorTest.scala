package npc

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class CacheControlBehaviorTest extends AnyFlatSpec {
  private def exerciseReplacement(policy: CacheReplacement)(check: CacheReplacementUnit => Unit): Unit = {
    simulate(new CacheReplacementUnit(sets = 1, ways = 4, policy)) { dut =>
      dut.io.querySet.poke(0)
      dut.io.accessSet.poke(0)
      dut.io.accessWay.poke(0)
      dut.io.accessValid.poke(false)
      dut.io.replaceValid.poke(false)
      dut.reset.poke(true)
      dut.clock.step(2)
      dut.reset.poke(false)
      check(dut)
    }
  }

  private def access(dut: CacheReplacementUnit, way: Int, replacement: Boolean = false): Unit = {
    dut.io.accessWay.poke(way)
    dut.io.accessValid.poke(true)
    dut.io.replaceValid.poke(replacement)
    dut.clock.step()
    dut.io.accessValid.poke(false)
    dut.io.replaceValid.poke(false)
  }

  "CacheReplacementUnit" should "select deterministic victims for LRU, Tree-PLRU, FIFO, and Random" in {
    exerciseReplacement(CacheReplacement.LRU) { dut =>
      (0 until 4).foreach(access(dut, _, replacement = true))
      dut.io.victimWay.expect(0)
      access(dut, 0)
      dut.io.victimWay.expect(1)
    }
    exerciseReplacement(CacheReplacement.TreePLRU) { dut =>
      (0 until 4).foreach(access(dut, _, replacement = true))
      dut.io.victimWay.expect(0)
      access(dut, 0)
      dut.io.victimWay.expect(2)
    }
    exerciseReplacement(CacheReplacement.FIFO) { dut =>
      (0 until 4).foreach(access(dut, _, replacement = true))
      dut.io.victimWay.expect(0)
      access(dut, 3)
      dut.io.victimWay.expect(0)
      access(dut, 0, replacement = true)
      dut.io.victimWay.expect(1)
    }
    exerciseReplacement(CacheReplacement.Random) { dut =>
      dut.io.victimWay.expect(2)
      access(dut, 2, replacement = true)
      dut.io.victimWay.expect(1)
    }
  }

  "CacheMaintenanceController" should "serialize FENCE/FENCE.I and skip I$ invalidation when unnecessary" in {
    simulate(new CacheMaintenanceController(hasInstructionCache = true, hasDataCache = true)) { dut =>
      dut.io.fencePending.poke(false)
      dut.io.fenceInvalidatesInstruction.poke(false)
      dut.io.fenceAccepted.poke(false)
      dut.io.backendBusy.poke(false)
      dut.io.externalDrainRequest.poke(false)
      dut.io.dcacheFlushDone.poke(false)
      dut.io.l2FlushDone.poke(false)
      dut.io.icacheInvalidateDone.poke(false)
      dut.reset.poke(true)
      dut.clock.step(2)
      dut.reset.poke(false)

      dut.io.fencePending.poke(true)
      dut.io.fenceInvalidatesInstruction.poke(true)
      dut.io.backendBusy.poke(true)
      dut.clock.step()
      dut.io.dispatchPermit.expect(false.B)
      dut.io.dcacheFlush.expect(false.B)
      dut.io.backendBusy.poke(false)
      dut.clock.step()
      dut.io.dcacheFlush.expect(true.B)
      dut.io.dcacheFlushDone.poke(true)
      dut.clock.step()
      dut.io.dcacheFlushDone.poke(false)
      dut.io.icacheInvalidate.expect(true.B)
      dut.io.icacheInvalidateDone.poke(true)
      dut.clock.step()
      dut.io.icacheInvalidateDone.poke(false)
      dut.io.dispatchPermit.expect(true.B)
      dut.io.fenceAccepted.poke(true)
      dut.clock.step()
      dut.io.fenceAccepted.poke(false)
      dut.io.fencePending.poke(false)
      dut.io.fenceInvalidatesInstruction.poke(false)
      dut.io.dispatchPermit.expect(true.B)

      // A regular FENCE drains dirty D$ state but must not invalidate I$.
      dut.io.fencePending.poke(true)
      dut.clock.step()
      dut.io.dispatchPermit.expect(false.B)
      dut.clock.step()
      dut.io.dcacheFlush.expect(true.B)
      dut.io.dcacheFlushDone.poke(true)
      dut.clock.step()
      dut.io.dcacheFlushDone.poke(false)
      dut.io.icacheInvalidate.expect(false.B)
      dut.io.dispatchPermit.expect(true.B)
      dut.io.fenceAccepted.poke(true)
      dut.clock.step()
      dut.io.fenceAccepted.poke(false)
      dut.io.fencePending.poke(false)

      dut.io.externalDrainRequest.poke(true)
      dut.clock.step(2)
      dut.io.dcacheFlush.expect(true.B)
      dut.io.dcacheFlushDone.poke(true)
      dut.clock.step()
      dut.io.dcacheFlushDone.poke(false)
      dut.io.externalDrained.expect(true.B)
      dut.io.icacheInvalidate.expect(false.B)
      dut.io.externalDrainRequest.poke(false)
      dut.clock.step()
      dut.io.dispatchPermit.expect(true.B)
    }
  }

  it should "cancel a speculative FENCE.I discarded by an older redirect" in {
    simulate(new CacheMaintenanceController(hasInstructionCache = true, hasDataCache = true)) { dut =>
      dut.io.fencePending.poke(false)
      dut.io.fenceInvalidatesInstruction.poke(false)
      dut.io.fenceAccepted.poke(false)
      dut.io.backendBusy.poke(true)
      dut.io.externalDrainRequest.poke(false)
      dut.io.dcacheFlushDone.poke(false)
      dut.io.l2FlushDone.poke(false)
      dut.io.icacheInvalidateDone.poke(false)
      dut.reset.poke(true)
      dut.clock.step(2)
      dut.reset.poke(false)

      dut.io.fencePending.poke(true)
      dut.clock.step()
      dut.io.dispatchPermit.expect(false.B)
      dut.io.fencePending.poke(false)
      dut.io.backendBusy.poke(false)
      dut.clock.step()
      dut.io.dispatchPermit.expect(true.B)
      dut.io.dcacheFlush.expect(false.B)
      dut.io.icacheInvalidate.expect(false.B)
    }
  }

  it should "drain D$ before the shared L2 for fences and external maintenance" in {
    simulate(new CacheMaintenanceController(
      hasInstructionCache = true, hasDataCache = true, hasUnifiedL2 = true)) { dut =>
      dut.io.fencePending.poke(false)
      dut.io.fenceInvalidatesInstruction.poke(false)
      dut.io.fenceAccepted.poke(false)
      dut.io.backendBusy.poke(false)
      dut.io.externalDrainRequest.poke(false)
      dut.io.dcacheFlushDone.poke(false)
      dut.io.l2FlushDone.poke(false)
      dut.io.icacheInvalidateDone.poke(false)
      dut.reset.poke(true)
      dut.clock.step(2)
      dut.reset.poke(false)

      dut.io.externalDrainRequest.poke(true)
      dut.clock.step(2)
      dut.io.dcacheFlush.expect(true.B)
      dut.io.l2Flush.expect(false.B)
      dut.io.dcacheFlushDone.poke(true)
      dut.clock.step()
      dut.io.dcacheFlushDone.poke(false)
      dut.io.l2Flush.expect(true.B)
      dut.io.externalDrained.expect(false.B)
      dut.io.l2FlushDone.poke(true)
      dut.clock.step()
      dut.io.l2FlushDone.poke(false)
      dut.io.externalDrained.expect(true.B)
      dut.io.externalDrainRequest.poke(false)
      dut.clock.step()
      dut.io.dispatchPermit.expect(true.B)
    }
  }

  "InstructionBuffer" should "retain only the FENCE.I head when younger fetches are discarded" in {
    simulate(new InstructionBuffer(entries = 4, ISAConfig(xlen = 32))) { dut =>
      dut.io.flush.poke(false)
      dut.io.dropYounger.poke(false)
      dut.io.in.valid.poke(false)
      dut.io.out.ready.poke(false)
      dut.reset.poke(true)
      dut.clock.step(2)
      dut.reset.poke(false)

      def enqueue(pc: Int): Unit = {
        dut.io.in.bits.pc.poke(pc)
        dut.io.in.bits.instruction.poke(0x13)
        dut.io.in.bits.perfFetchCycles.poke(0)
        dut.io.in.bits.perfDecodeStartCycle.poke(0)
        dut.io.in.valid.poke(true)
        dut.io.in.ready.expect(true.B)
        dut.clock.step()
        dut.io.in.valid.poke(false)
      }

      enqueue(0x100)
      enqueue(0x104)
      enqueue(0x108)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.pc.expect(0x100.U)

      dut.io.dropYounger.poke(true)
      dut.clock.step()
      dut.io.dropYounger.poke(false)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.pc.expect(0x100.U)

      dut.io.out.ready.poke(true)
      dut.clock.step()
      dut.io.out.valid.expect(false.B)
    }
  }

  it should "dispatch a newly fetched instruction in the same cycle when flow-through is enabled" in {
    simulate(new InstructionBuffer(entries = 4, ISAConfig(xlen = 32), flowThrough = true)) { dut =>
      dut.io.flush.poke(false)
      dut.io.dropYounger.poke(false)
      dut.io.out.ready.poke(true)
      dut.io.in.bits.pc.poke(0x200)
      dut.io.in.bits.instruction.poke(0x13)
      dut.io.in.bits.perfFetchStartCycle.poke(4)
      dut.io.in.bits.perfFetchCycles.poke(3)
      dut.io.in.bits.perfDecodeStartCycle.poke(7)
      dut.io.in.valid.poke(true)
      dut.reset.poke(true)
      dut.clock.step(2)
      dut.reset.poke(false)

      dut.io.in.ready.expect(true.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.pc.expect(0x200.U)
      dut.io.out.bits.perfFetchCycles.expect(3.U)
      dut.io.in.valid.poke(false)
      dut.clock.step()
      dut.io.out.valid.expect(false.B)
    }
  }

  it should "remove a retained FENCE.I head when maintenance releases its dispatch" in {
    simulate(new InstructionBuffer(entries = 4, ISAConfig(xlen = 32))) { dut =>
      dut.io.flush.poke(false)
      dut.io.dropYounger.poke(false)
      dut.io.in.valid.poke(false)
      dut.io.out.ready.poke(false)
      dut.reset.poke(true)
      dut.clock.step(2)
      dut.reset.poke(false)

      def enqueue(pc: Int): Unit = {
        dut.io.in.bits.pc.poke(pc)
        dut.io.in.bits.instruction.poke(0x13)
        dut.io.in.bits.perfFetchCycles.poke(0)
        dut.io.in.bits.perfDecodeStartCycle.poke(0)
        dut.io.in.valid.poke(true)
        dut.io.in.ready.expect(true.B)
        dut.clock.step()
        dut.io.in.valid.poke(false)
      }

      enqueue(0x100)
      enqueue(0x104)
      dut.io.dropYounger.poke(true)
      dut.io.out.ready.poke(true)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.pc.expect(0x100.U)
      dut.clock.step()

      dut.io.dropYounger.poke(false)
      dut.io.out.valid.expect(false.B)
    }
  }

  "IFetchAXIAdapter" should "discard an outstanding pre-fence response and refetch the same PC" in {
    simulate(new IFetchAXIAdapter(addrWidth = 32, dataWidth = 32)) { dut =>
      dut.io.pc.poke(0x80000004L)
      dut.io.responseReady.poke(true)
      dut.io.flush.poke(false)
      dut.io.axi.aw.ready.poke(false)
      dut.io.axi.w.ready.poke(false)
      dut.io.axi.b.valid.poke(false)
      dut.io.axi.b.bits.resp.poke(0)
      dut.io.axi.ar.ready.poke(true)
      dut.io.axi.r.valid.poke(false)
      dut.io.axi.r.bits.data.poke(0)
      dut.io.axi.r.bits.resp.poke(0)
      dut.reset.poke(true)
      dut.clock.step(2)
      dut.reset.poke(false)

      dut.io.axi.ar.valid.expect(true.B)
      dut.clock.step()
      dut.io.flush.poke(true)
      dut.clock.step()
      dut.io.flush.poke(false)

      dut.io.axi.r.bits.data.poke("h11111113".U)
      dut.io.axi.r.valid.poke(true)
      dut.io.responseValid.expect(false.B)
      dut.clock.step()
      dut.io.axi.r.valid.poke(false)

      dut.io.axi.ar.valid.expect(true.B)
      dut.clock.step()
      dut.io.axi.r.bits.data.poke("h22222213".U)
      dut.io.axi.r.valid.poke(true)
      dut.io.responseValid.expect(true.B)
      dut.io.inst.expect("h22222213".U)
      dut.clock.step()
    }
  }
}
