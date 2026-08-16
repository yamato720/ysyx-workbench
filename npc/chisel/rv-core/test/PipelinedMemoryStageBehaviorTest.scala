package npc

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import npc.protocol.ExecuteMemoryPayload

import scala.collection.mutable

class PipelinedMemoryStageBehaviorTest extends AnyFlatSpec {
  private val cfg = ISAConfig(xlen = 64)
  private val base = BigInt("80000000", 16)

  private def clearPayload(bits: ExecuteMemoryPayload): Unit = {
    bits.pc.poke(0)
    bits.instruction.poke(0)
    bits.perfFetchStartCycle.poke(0)
    bits.perfFetchCycles.poke(0)
    bits.perfDecodeStartCycle.poke(0)
    bits.perfDecodeCycles.poke(0)
    bits.perfExecuteStartCycle.poke(0)
    bits.perfExecuteCycles.poke(0)
    bits.perfMemoryStartCycle.poke(0)
    bits.perfMemoryQueueStartCycle.poke(0)
    bits.aluResult.poke(0)
    bits.branchTaken.poke(0)
    bits.branchTarget.poke(0)
    bits.jalrTarget.poke(0)
    bits.storeData.poke(0)
    bits.rd.poke(0)
    bits.funct3.poke(0)
    bits.branch.poke(false)
    bits.loadEnable.poke(false)
    bits.writebackFromMemory.poke(false)
    bits.storeEnable.poke(false)
    bits.registerWriteEnable.poke(false)
    bits.floatRegisterWriteEnable.poke(false)
    bits.floatingInstruction.poke(false)
    bits.floatingExceptionFlags.poke(0)
    bits.csrReadWritebackEnable.poke(false)
    bits.csrAddress.poke(0)
    bits.csrWriteEnable.poke(false)
    bits.csrWriteData.poke(0)
    bits.csrAccessAllowed.poke(false)
    bits.trapEnable.poke(false)
    bits.trapCause.poke(0)
    bits.trapEpc.poke(0)
    bits.mretEnable.poke(false)
    bits.csrReadData.poke(0)
  }

  private def clearRequest(dut: PipelinedMemoryStage): Unit = clearPayload(dut.io.request.bits)

  private def setLoad(dut: PipelinedMemoryStage, address: BigInt, rd: Int): Unit = {
    clearRequest(dut)
    dut.io.request.bits.pc.poke(address)
    dut.io.request.bits.instruction.poke(0x00003003L)
    dut.io.request.bits.perfMemoryStartCycle.poke(0)
    dut.io.request.bits.perfMemoryQueueStartCycle.poke(0)
    dut.io.request.bits.aluResult.poke(address)
    dut.io.request.bits.rd.poke(rd)
    dut.io.request.bits.funct3.poke(3)
    dut.io.request.bits.loadEnable.poke(true)
    dut.io.request.bits.writebackFromMemory.poke(true)
    dut.io.request.bits.registerWriteEnable.poke(true)
  }

  private def setInteger(dut: PipelinedMemoryStage, pc: BigInt, rd: Int): Unit = {
    clearRequest(dut)
    dut.io.request.bits.pc.poke(pc)
    dut.io.request.bits.instruction.poke(0x00000013L)
    dut.io.request.bits.perfMemoryStartCycle.poke(0)
    dut.io.request.bits.perfMemoryQueueStartCycle.poke(0)
    dut.io.request.bits.aluResult.poke(0x1234)
    dut.io.request.bits.rd.poke(rd)
    dut.io.request.bits.registerWriteEnable.poke(true)
  }

  private def setStore(dut: PipelinedMemoryStage, address: BigInt,
                       data: BigInt, accessType: Int): Unit = {
    clearRequest(dut)
    dut.io.request.bits.pc.poke(address)
    dut.io.request.bits.instruction.poke(0x00002023L)
    dut.io.request.bits.perfMemoryStartCycle.poke(0)
    dut.io.request.bits.perfMemoryQueueStartCycle.poke(0)
    dut.io.request.bits.aluResult.poke(address)
    dut.io.request.bits.storeData.poke(data)
    dut.io.request.bits.funct3.poke(accessType)
    dut.io.request.bits.storeEnable.poke(true)
  }

  private def initialize(dut: PipelinedMemoryStage): Unit = {
    dut.io.request.valid.poke(false)
    clearPayload(dut.io.arithmeticRequest.bits)
    dut.io.arithmeticRequest.valid.poke(false)
    dut.io.arithmeticCompletion.foreach { completion =>
      completion.valid.poke(false)
      completion.bits.tag.poke(0)
      completion.bits.result.poke(0)
      completion.bits.exceptionFlags.poke(0)
      completion.bits.illegal.poke(false)
    }
    dut.io.response.ready.poke(true)
    dut.io.flush.poke(false)
    dut.io.axi.aw.ready.poke(true)
    dut.io.axi.w.ready.poke(true)
    dut.io.axi.b.valid.poke(false)
    dut.io.axi.b.bits.resp.poke(0)
    dut.io.axi.ar.ready.poke(true)
    dut.io.axi.r.valid.poke(false)
    dut.io.axi.r.bits.data.poke(0)
    dut.io.axi.r.bits.resp.poke(0)
  }

  "PipelinedMemoryStage" should "accept four independent loads before AXI responses and return them in order" in {
    simulate(new PipelinedMemoryStage(cfg = cfg)) { dut =>
      val pendingReads = mutable.Queue.empty[BigInt]
      val acceptedAddresses = mutable.ArrayBuffer.empty[BigInt]
      val returnedAddresses = mutable.ArrayBuffer.empty[BigInt]
      var cycle = 0L
      var faultMode = false
      val responseReleaseCycle = 12L

      initialize(dut)
      dut.reset.poke(true)
      dut.clock.step(2)
      dut.reset.poke(false)

      def stepCycle(): Unit = {
        dut.io.cycle.poke(cycle)
        val releaseResponses = faultMode || cycle >= responseReleaseCycle
        dut.io.axi.r.valid.poke(releaseResponses && pendingReads.nonEmpty)
        dut.io.axi.r.bits.data.poke(pendingReads.headOption.map(_ + 0x1000).getOrElse(BigInt(0)))
        dut.io.axi.r.bits.resp.poke(if (faultMode) 2 else 0)

        val arFire = dut.io.axi.ar.valid.peek().litToBoolean && dut.io.axi.ar.ready.peek().litToBoolean
        val arAddress = dut.io.axi.ar.bits.addr.peek().litValue
        val rFire = dut.io.axi.r.valid.peek().litToBoolean && dut.io.axi.r.ready.peek().litToBoolean
        val responseFire = dut.io.response.valid.peek().litToBoolean &&
          dut.io.response.ready.peek().litToBoolean
        if (responseFire && dut.io.response.bits.writebackFromMemory.peek().litToBoolean) {
          returnedAddresses += dut.io.response.bits.pc.peek().litValue
        }

        dut.clock.step()
        if (rFire) pendingReads.dequeue()
        if (arFire) {
          pendingReads.enqueue(arAddress)
          acceptedAddresses += arAddress
        }
        cycle += 1
      }

      for (index <- 0 until 4) {
        val address = base + index * 8
        setLoad(dut, address, rd = index + 1)
        dut.io.request.valid.poke(true)
        dut.io.request.ready.expect(true.B)
        assert(dut.io.request.valid.peek().litToBoolean && dut.io.request.ready.peek().litToBoolean)
        stepCycle()
        dut.io.request.valid.poke(false)
      }

      setLoad(dut, base + 32, rd = 5)
      dut.io.request.valid.poke(true)
      dut.io.request.ready.expect(false.B)
      dut.io.request.valid.poke(false)
      assert(!dut.io.drained.peek().litToBoolean)

      var guard = 0
      while (returnedAddresses.size < 4 && guard < 80) {
        stepCycle()
        guard += 1
      }
      assert(acceptedAddresses.toSeq == (0 until 4).map(index => base + index * 8))
      assert(returnedAddresses.toSeq == acceptedAddresses.toSeq)
      assert(dut.io.fault.valid.peek().litToBoolean == false)

      while (!dut.io.drained.peek().litToBoolean && guard < 120) {
        stepCycle()
        guard += 1
      }
      assert(dut.io.drained.peek().litToBoolean)

      // 同一仿真上下文中再测试年轻 fault，避免多个 Verilator 编译任务并行触发
      // 工具内部优化竞态；reset 后 FIFO 和 fault 状态都重新开始。
      pendingReads.clear()
      acceptedAddresses.clear()
      returnedAddresses.clear()
      cycle = 0
      faultMode = true
      dut.io.request.valid.poke(false)
      dut.io.response.ready.poke(false)
      dut.reset.poke(true)
      dut.clock.step(2)
      dut.reset.poke(false)

      setInteger(dut, pc = 0x100, rd = 6)
      dut.io.request.valid.poke(true)
      dut.io.request.ready.expect(true.B)
      stepCycle()
      dut.io.request.valid.poke(false)

      setLoad(dut, base, rd = 7)
      dut.io.request.valid.poke(true)
      dut.io.request.ready.expect(true.B)
      stepCycle()
      dut.io.request.valid.poke(false)

      // 保持 WB 反压，让年轻 load 的 fault completion 先进入完成 FIFO。
      (0 until 8).foreach(_ => stepCycle())
      dut.io.fault.valid.expect(true.B)
      dut.io.response.ready.poke(true)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.pc.expect(0x100.U)
      dut.io.response.bits.registerWriteEnable.expect(true.B)
      stepCycle()
    }
  }

  it should "align store data with the byte strobe for an offset word" in {
    simulate(new PipelinedMemoryStage(cfg = cfg)) { dut =>
      initialize(dut)
      dut.reset.poke(true)
      dut.clock.step(2)
      dut.reset.poke(false)

      setStore(dut, base + 4, data = 6, accessType = 2)
      dut.io.request.valid.poke(true)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()
      dut.io.request.valid.poke(false)

      dut.io.axi.aw.valid.expect(true.B)
      dut.io.axi.aw.bits.addr.expect((base + 4).U)
      dut.io.axi.w.valid.expect(true.B)
      dut.io.axi.w.bits.data.expect((BigInt(6) << 32).U)
      dut.io.axi.w.bits.strb.expect("hf0".U)
    }
  }

  it should "forward a completed younger arithmetic slot without retiring it before an older load" in {
    simulate(new PipelinedMemoryStage(cfg = cfg, enableOutstandingCompletionForwarding = true)) { dut =>
      initialize(dut)
      dut.reset.poke(true)
      dut.clock.step(2)
      dut.reset.poke(false)
      var cycle = 0L

      def step(): Unit = {
        dut.io.cycle.poke(cycle)
        dut.clock.step()
        cycle += 1
      }

      setLoad(dut, base, rd = 3)
      dut.io.request.valid.poke(true)
      dut.io.request.ready.expect(true.B)
      step()
      dut.io.request.valid.poke(false)

      clearPayload(dut.io.arithmeticRequest.bits)
      dut.io.arithmeticRequest.bits.pc.poke(base + 4)
      dut.io.arithmeticRequest.bits.instruction.poke(0x02000033L)
      dut.io.arithmeticRequest.bits.rd.poke(4)
      dut.io.arithmeticRequest.bits.registerWriteEnable.poke(true)
      dut.io.arithmeticRequest.valid.poke(true)
      dut.io.arithmeticRequest.ready.expect(true.B)
      val arithmeticTag = dut.io.arithmeticAllocateTag.peek().litValue
      step()
      dut.io.arithmeticRequest.valid.poke(false)

      dut.io.arithmeticCompletion(0).bits.tag.poke(arithmeticTag)
      dut.io.arithmeticCompletion(0).bits.result.poke(0x55)
      dut.io.arithmeticCompletion(0).bits.exceptionFlags.poke(0)
      dut.io.arithmeticCompletion(0).bits.illegal.poke(false)
      dut.io.arithmeticCompletion(0).valid.poke(true)
      dut.io.arithmeticCompletion(0).ready.expect(true.B)
      step()
      dut.io.arithmeticCompletion(0).valid.poke(false)

      dut.io.completionCandidates(0).valid.expect(true.B)
      dut.io.completionCandidates(0).rd.expect(4.U)
      dut.io.completionCandidates(0).data.expect(0x55.U)
      dut.io.completionCandidates(0).dataValid.expect(true.B)
      dut.io.completionCandidates(1).valid.expect(true.B)
      dut.io.completionCandidates(1).rd.expect(3.U)
      dut.io.completionCandidates(1).dataValid.expect(false.B)
      dut.io.response.valid.expect(false.B)

      dut.io.axi.r.valid.poke(true)
      dut.io.axi.r.bits.data.poke(0x1234)
      val returnedPcs = mutable.ArrayBuffer.empty[BigInt]
      var readSent = false
      var guard = 0
      while (returnedPcs.size < 2 && guard < 20) {
        if (dut.io.response.valid.peek().litToBoolean && dut.io.response.ready.peek().litToBoolean) {
          returnedPcs += dut.io.response.bits.pc.peek().litValue
        }
        val readFire = dut.io.axi.r.valid.peek().litToBoolean && dut.io.axi.r.ready.peek().litToBoolean
        step()
        if (readFire && !readSent) {
          dut.io.axi.r.valid.poke(false)
          readSent = true
        }
        guard += 1
      }
      assert(returnedPcs.toSeq == Seq(base, base + 4))
    }
  }

  it should "forward a returning load in its completion handshake cycle" in {
    simulate(new PipelinedMemoryStage(cfg = cfg, enableOutstandingCompletionForwarding = true)) { dut =>
      initialize(dut)
      dut.reset.poke(true)
      dut.clock.step(2)
      dut.reset.poke(false)

      setLoad(dut, base, rd = 9)
      dut.io.request.valid.poke(true)
      dut.io.request.ready.expect(true.B)
      // 空 pending FIFO 必须在请求握手的同拍开始 D$ 事务；缓存命中仍由下一拍
      // 同步读响应，不改变其可见性或按序退休。
      dut.io.axi.ar.valid.expect(true.B)
      dut.clock.step()
      dut.io.request.valid.poke(false)

      // 下一拍 R 握手时必须已可用于 ID 前递。
      dut.clock.step()
      dut.io.axi.r.valid.poke(true)
      dut.io.axi.r.bits.data.poke(0x12345678L)
      dut.io.axi.r.bits.resp.poke(0)
      dut.io.axi.r.ready.expect(true.B)
      dut.io.completionCandidates(0).valid.expect(true.B)
      dut.io.completionCandidates(0).rd.expect(9.U)
      dut.io.completionCandidates(0).data.expect(0x12345678L.U)
      dut.io.completionCandidates(0).dataValid.expect(true.B)
      dut.clock.step()
    }
  }
}
