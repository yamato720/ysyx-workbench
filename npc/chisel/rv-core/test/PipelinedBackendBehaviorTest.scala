package npc

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable

/** 直接驱动后端派发口，检查两拍整数 EX 与流水 MEM 重叠时仍保持 RAW 前递。 */
class PipelinedBackendBehaviorTest extends AnyFlatSpec {
  private val config = new PipelinedTwoCycleWideL2SimulationCoreConfig().build
  private val oneStageConfig = new HbmJitterCacheSimulationCoreConfig().build

  private def clearDispatch(dut: NpcBackend): Unit = {
    val bits = dut.io.dispatch.bits
    bits.pc.poke(0)
    bits.instruction.poke(0)
    bits.predictedNextPc.poke(4)
    bits.perfFetchStartCycle.poke(0)
    bits.perfFetchCycles.poke(0)
    bits.perfDecodeStartCycle.poke(0)
    bits.immediate.poke(0)
    bits.rd.poke(0)
    bits.rs1.poke(0)
    bits.rs2.poke(0)
    bits.rs3.poke(0)
    bits.funct3.poke(0)
    bits.funct7.poke(0)
    bits.csrAddress.poke(0)
    bits.branch.poke(false)
    bits.loadEnable.poke(false)
    bits.writebackFromMemory.poke(false)
    bits.storeEnable.poke(false)
    bits.useImmediate.poke(false)
    bits.registerWriteEnable.poke(false)
    bits.usesRs1.poke(false)
    bits.usesRs2.poke(false)
    bits.executionUnit.poke(NpcExecutionUnit.integer)
    bits.aluCtrl.poke(NpcAluOp.Integer.ADD.asUInt)
    bits.privilegedInstruction.poke(false)
    bits.trapEnable.poke(false)
    bits.trapCause.poke(0)
    bits.mretEnable.poke(false)
    bits.csrEnable.poke(false)
    bits.csrOperation.poke(0)
    bits.csrUseImmediate.poke(false)
    bits.csrReadWritebackEnable.poke(false)
    bits.floatingOperation.poke(false)
    bits.floatingInstruction.poke(false)
    bits.floatRegisterWriteEnable.poke(false)
    bits.usesFrs1.poke(false)
    bits.usesFrs2.poke(false)
    bits.usesFrs3.poke(false)
  }

  private def setAddi(dut: NpcBackend, pc: BigInt, rd: Int, rs1: Int, immediate: BigInt): Unit = {
    clearDispatch(dut)
    dut.io.dispatch.bits.pc.poke(pc)
    dut.io.dispatch.bits.instruction.poke(0x00000013L)
    dut.io.dispatch.bits.predictedNextPc.poke(pc + 4)
    dut.io.dispatch.bits.rd.poke(rd)
    dut.io.dispatch.bits.rs1.poke(rs1)
    dut.io.dispatch.bits.immediate.poke(immediate)
    dut.io.dispatch.bits.useImmediate.poke(true)
    dut.io.dispatch.bits.registerWriteEnable.poke(true)
    dut.io.dispatch.bits.usesRs1.poke(rs1 != 0)
  }

  private def setLoad(dut: NpcBackend, pc: BigInt, rd: Int, address: BigInt): Unit = {
    clearDispatch(dut)
    dut.io.dispatch.bits.pc.poke(pc)
    dut.io.dispatch.bits.instruction.poke(0x00003003L)
    dut.io.dispatch.bits.predictedNextPc.poke(pc + 4)
    dut.io.dispatch.bits.rd.poke(rd)
    dut.io.dispatch.bits.immediate.poke(address)
    dut.io.dispatch.bits.funct3.poke(3)
    dut.io.dispatch.bits.loadEnable.poke(true)
    dut.io.dispatch.bits.writebackFromMemory.poke(true)
    dut.io.dispatch.bits.useImmediate.poke(true)
    dut.io.dispatch.bits.registerWriteEnable.poke(true)
  }

  private def setStore(dut: NpcBackend, pc: BigInt, rs1: Int, rs2: Int, immediate: BigInt): Unit = {
    clearDispatch(dut)
    dut.io.dispatch.bits.pc.poke(pc)
    dut.io.dispatch.bits.instruction.poke(0x00000023L)
    dut.io.dispatch.bits.predictedNextPc.poke(pc + 4)
    dut.io.dispatch.bits.rs1.poke(rs1)
    dut.io.dispatch.bits.rs2.poke(rs2)
    dut.io.dispatch.bits.immediate.poke(immediate)
    dut.io.dispatch.bits.funct3.poke(3)
    dut.io.dispatch.bits.storeEnable.poke(true)
    dut.io.dispatch.bits.useImmediate.poke(true)
    dut.io.dispatch.bits.usesRs1.poke(true)
    dut.io.dispatch.bits.usesRs2.poke(true)
  }

  private def setBeq(dut: NpcBackend, pc: BigInt, predictedNextPc: BigInt, target: BigInt): Unit = {
    clearDispatch(dut)
    dut.io.dispatch.bits.pc.poke(pc)
    dut.io.dispatch.bits.instruction.poke(0x00000463L)
    dut.io.dispatch.bits.predictedNextPc.poke(predictedNextPc)
    dut.io.dispatch.bits.immediate.poke(target - pc)
    dut.io.dispatch.bits.branch.poke(true)
    dut.io.dispatch.bits.aluCtrl.poke(NpcAluOp.Integer.BEQ.asUInt)
  }

  private def initialize(dut: NpcBackend): Unit = {
    dut.io.dispatch.valid.poke(false)
    dut.io.interrupt.poke(false)
    dut.io.axi.aw.ready.poke(true)
    dut.io.axi.w.ready.poke(true)
    dut.io.axi.b.valid.poke(true)
    dut.io.axi.b.bits.resp.poke(0)
    dut.io.axi.ar.ready.poke(true)
    dut.io.axi.r.valid.poke(false)
    dut.io.axi.r.bits.data.poke(0)
    dut.io.axi.r.bits.resp.poke(0)
  }

  "NpcBackend" should "forward an ALU result to consecutive stores while MEM is pipelined" in {
    simulate(new NpcBackend(config)) { dut =>
      initialize(dut)
      dut.reset.poke(true)
      dut.clock.step(2)
      dut.reset.poke(false)

      def send(): Unit = {
        dut.io.dispatch.valid.poke(true)
        var guard = 0
        while (!dut.io.dispatch.ready.peek().litToBoolean && guard < 20) {
          dut.clock.step()
          guard += 1
        }
        assert(dut.io.dispatch.ready.peek().litToBoolean)
        dut.clock.step()
        dut.io.dispatch.valid.poke(false)
      }

      setAddi(dut, 0x100, rd = 2, rs1 = 0, immediate = 0x9000)
      send()
      setAddi(dut, 0x104, rd = 2, rs1 = 2, immediate = -0x50)
      send()
      setStore(dut, 0x108, rs1 = 2, rs2 = 9, immediate = 0x38)
      send()
      setStore(dut, 0x10c, rs1 = 2, rs2 = 18, immediate = 0x30)
      send()

      val stores = mutable.ArrayBuffer.empty[BigInt]
      var guard = 0
      while (stores.size < 2 && guard < 120) {
        if (dut.io.debug.commitStoreValid.peek().litToBoolean) {
          stores += dut.io.debug.commitStoreAddress.peek().litValue
        }
        dut.clock.step()
        guard += 1
      }

      assert(stores.toSeq == Seq(BigInt("8fe8", 16), BigInt("8fe0", 16)))
    }
  }

  "NpcBackend" should "keep a continuous dispatch stream ordered across a two-stage RAW chain" in {
    simulate(new NpcBackend(config)) { dut =>
      initialize(dut)
      dut.reset.poke(true)
      dut.clock.step(2)
      dut.reset.poke(false)

      val instructions = Seq[() => Unit](
        () => setAddi(dut, 0x200, rd = 2, rs1 = 0, immediate = 0x9000),
        () => setAddi(dut, 0x204, rd = 2, rs1 = 2, immediate = -0x50),
        () => setStore(dut, 0x208, rs1 = 2, rs2 = 9, immediate = 0x38),
        () => setStore(dut, 0x20c, rs1 = 2, rs2 = 18, immediate = 0x30)
      )

      dut.io.dispatch.valid.poke(true)
      var instructionIndex = 0
      var guard = 0
      while (instructionIndex < instructions.size && guard < 120) {
        instructions(instructionIndex)()
        if (dut.io.dispatch.ready.peek().litToBoolean) {
          instructionIndex += 1
        }
        dut.clock.step()
        guard += 1
      }
      dut.io.dispatch.valid.poke(false)
      assert(instructionIndex == instructions.size)

      val stores = mutable.ArrayBuffer.empty[BigInt]
      guard = 0
      while (stores.size < 2 && guard < 120) {
        if (dut.io.debug.commitStoreValid.peek().litToBoolean) {
          stores += dut.io.debug.commitStoreAddress.peek().litValue
        }
        dut.clock.step()
        guard += 1
      }

      assert(stores.toSeq == Seq(BigInt("8fe8", 16), BigInt("8fe0", 16)))
    }
  }

  it should "bypass an empty MEM stage for a non-memory instruction" in {
    simulate(new NpcBackend(config)) { dut =>
      initialize(dut)
      dut.reset.poke(true)
      dut.clock.step(2)
      dut.reset.poke(false)

      def send(): Unit = {
        dut.io.dispatch.valid.poke(true)
        var guard = 0
        while (!dut.io.dispatch.ready.peek().litToBoolean && guard < 20) {
          dut.clock.step()
          guard += 1
        }
        assert(dut.io.dispatch.ready.peek().litToBoolean)
        dut.clock.step()
        dut.io.dispatch.valid.poke(false)
      }

      setAddi(dut, 0x300, rd = 5, rs1 = 0, immediate = 7)
      send()

      var sawMemoryStageBusy = false
      val commits = mutable.ArrayBuffer.empty[BigInt]
      var guard = 0
      while (commits.isEmpty && guard < 40) {
        sawMemoryStageBusy ||= dut.io.debug.memoryWaitingForLsu.peek().litToBoolean
        if (dut.io.debug.sampleCommitValid.peek().litToBoolean) {
          commits += dut.io.debug.sampleCommitPc.peek().litValue
        }
        dut.clock.step()
        guard += 1
      }

      assert(commits.toSeq == Seq(BigInt(0x300)))
      assert(!sawMemoryStageBusy)
    }
  }

  it should "write an isolated one-stage integer instruction into WB without an ID/EX residency" in {
    simulate(new NpcBackend(oneStageConfig)) { dut =>
      initialize(dut)
      dut.reset.poke(true)
      dut.clock.step(2)
      dut.reset.poke(false)

      val decodeStart = dut.io.debug.cycleCount.peek().litValue
      setAddi(dut, 0x340, rd = 5, rs1 = 0, immediate = 7)
      dut.io.dispatch.bits.perfFetchStartCycle.poke(decodeStart - 1)
      dut.io.dispatch.bits.perfFetchCycles.poke(1)
      dut.io.dispatch.bits.perfDecodeStartCycle.poke(decodeStart)
      dut.io.dispatch.valid.poke(true)
      dut.io.dispatch.ready.expect(true.B)
      dut.clock.step()
      dut.io.dispatch.valid.poke(false)

      var observed = false
      var guard = 0
      while (!observed && guard < 40) {
        dut.clock.step()
        if (dut.io.debug.commitValid.peek().litToBoolean) {
          dut.io.debug.commitDecodeStartCycle.expect(decodeStart.U)
          dut.io.debug.commitExecuteStartCycle.expect(decodeStart.U)
          dut.io.debug.commitMemoryStartCycle.expect(decodeStart.U)
          dut.io.debug.commitWritebackStartCycle.expect(decodeStart.U)
          dut.io.debug.commitDecodeCycles.expect(0.U)
          dut.io.debug.commitExecuteCycles.expect(1.U)
          dut.io.debug.commitMemoryCycles.expect(0.U)
          observed = true
        }
        guard += 1
      }
      assert(observed)
    }
  }

  it should "keep dispatch-to-WB bypass active for consecutive independent integers" in {
    simulate(new NpcBackend(oneStageConfig)) { dut =>
      initialize(dut)
      dut.reset.poke(true)
      dut.clock.step(2)
      dut.reset.poke(false)

      val instructionCount = 12
      var instructionIndex = 0
      val decodeCycles = mutable.ArrayBuffer.empty[BigInt]
      def sampleCommit(): Unit = {
        if (dut.io.debug.sampleCommitValid.peek().litToBoolean) {
          decodeCycles += dut.io.debug.sampleDecodeCycles.peek().litValue
        }
      }
      dut.io.dispatch.valid.poke(true)
      while (instructionIndex < instructionCount) {
        sampleCommit()
        val cycle = dut.io.debug.cycleCount.peek().litValue
        setAddi(dut, 0x380 + instructionIndex * 4, rd = 5 + instructionIndex % 11,
          rs1 = 0, immediate = instructionIndex + 1)
        dut.io.dispatch.bits.perfFetchStartCycle.poke(cycle - 1)
        dut.io.dispatch.bits.perfFetchCycles.poke(1)
        dut.io.dispatch.bits.perfDecodeStartCycle.poke(cycle)
        dut.io.dispatch.ready.expect(true.B)
        dut.clock.step()
        instructionIndex += 1
      }
      dut.io.dispatch.valid.poke(false)

      var guard = 0
      while (decodeCycles.size < instructionCount && guard < 40) {
        sampleCommit()
        dut.clock.step()
        guard += 1
      }

      assert(decodeCycles.size == instructionCount)
      assert(decodeCycles.forall(_ == 0), s"unexpected ID/EX residency: $decodeCycles")
    }
  }

  it should "write a correctly predicted branch directly to WB and recover only wrong predictions" in {
    def observeBranch(predictedNextPc: BigInt): (Option[BigInt], Boolean, Option[BigInt], Option[BigInt]) = {
      var observed: Option[BigInt] = None
      var sawExecuteMemoryFire = false
      var decodeCycles: Option[BigInt] = None
      var committedNextPc: Option[BigInt] = None
      simulate(new NpcBackend(oneStageConfig)) { dut =>
        initialize(dut)
        dut.reset.poke(true)
        dut.clock.step(2)
        dut.reset.poke(false)

        setBeq(dut, pc = 0x500, predictedNextPc = predictedNextPc, target = 0x508)
        dut.io.dispatch.valid.poke(true)
        dut.io.dispatch.ready.expect(true.B)
        dut.clock.step()
        dut.io.dispatch.valid.poke(false)

        for (_ <- 0 until 12) {
          if (dut.io.redirectValid.peek().litToBoolean) {
            observed = Some(dut.io.redirectTarget.peek().litValue)
          }
          sawExecuteMemoryFire ||= dut.io.debug.executeMemoryFire.peek().litToBoolean
          if (dut.io.debug.sampleCommitValid.peek().litToBoolean &&
              dut.io.debug.sampleCommitPc.peek().litValue == BigInt(0x500)) {
            decodeCycles = Some(dut.io.debug.sampleDecodeCycles.peek().litValue)
            committedNextPc = Some(dut.io.debug.sampleCommitNextPc.peek().litValue)
          }
          dut.clock.step()
        }
      }
      (observed, sawExecuteMemoryFire, decodeCycles, committedNextPc)
    }

    val (correctRedirect, correctExecuteMemoryFire, correctDecodeCycles, correctNextPc) = observeBranch(0x508)
    assert(correctRedirect.isEmpty)
    assert(!correctExecuteMemoryFire)
    assert(correctDecodeCycles.contains(BigInt(0)))
    assert(correctNextPc.contains(BigInt(0x508)))

    val (wrongRedirect, wrongExecuteMemoryFire, wrongDecodeCycles, wrongNextPc) = observeBranch(0x504)
    assert(wrongRedirect.contains(BigInt(0x508)))
    assert(wrongExecuteMemoryFire)
    assert(wrongDecodeCycles.contains(BigInt(1)))
    assert(wrongNextPc.contains(BigInt(0x508)))
  }

  it should "keep a non-memory bypass candidate behind an older outstanding load" in {
    simulate(new NpcBackend(config)) { dut =>
      initialize(dut)
      dut.reset.poke(true)
      dut.clock.step(2)
      dut.reset.poke(false)

      def send(): Unit = {
        dut.io.dispatch.valid.poke(true)
        var guard = 0
        while (!dut.io.dispatch.ready.peek().litToBoolean && guard < 40) {
          dut.clock.step()
          guard += 1
        }
        assert(dut.io.dispatch.ready.peek().litToBoolean)
        dut.clock.step()
        dut.io.dispatch.valid.poke(false)
      }

      setLoad(dut, 0x400, rd = 6, address = 0x80000000L)
      send()
      setAddi(dut, 0x404, rd = 7, rs1 = 0, immediate = 1)
      send()

      // R 保持无效时，较老 load 未完成，年轻 addi 不能通过直通路径抢先提交。
      for (_ <- 0 until 10) {
        dut.io.debug.sampleCommitValid.expect(false.B)
        dut.clock.step()
      }

      dut.io.axi.r.valid.poke(true)
      dut.io.axi.r.bits.data.poke(0x1234L)
      val commits = mutable.ArrayBuffer.empty[BigInt]
      var responsePending = true
      var guard = 0
      while (commits.size < 2 && guard < 80) {
        if (dut.io.debug.sampleCommitValid.peek().litToBoolean) {
          commits += dut.io.debug.sampleCommitPc.peek().litValue
        }
        val readResponseFire = dut.io.axi.r.valid.peek().litToBoolean &&
          dut.io.axi.r.ready.peek().litToBoolean
        dut.clock.step()
        if (readResponseFire && responsePending) {
          dut.io.axi.r.valid.poke(false)
          responsePending = false
        }
        guard += 1
      }

      assert(commits.toSeq == Seq(BigInt(0x400), BigInt(0x404)))
    }
  }
}
