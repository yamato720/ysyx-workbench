package spmv

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import java.nio.file.{Files, Path}
import npc.{SpmvAcceleratorConfig, SpmvOnChipStorage}
import org.scalatest.flatspec.AnyFlatSpec
import scala.jdk.CollectionConverters._

class SpmvResourceProbeTest extends AnyFlatSpec {
  private val smallConfig = SpmvAcceleratorConfig(
    hbmPcCount = 2,
    axiAddrWidth = 64,
    axiDataWidth = 512,
    axiIdWidth = 4,
    elementWidth = 32,
    elementsPerPc = 32,
    readElementsPerCycle = 1,
    storage = SpmvOnChipStorage.UltraRam,
    burstBeats = 2,
    outstandingBurstsPerPc = 1,
    clockMHz = 300
  )

  private def packed(elements: Seq[Int]): BigInt =
    elements.zipWithIndex.foldLeft(BigInt(0)) { case (value, (element, index)) =>
      value | (BigInt(element & 0xffffffffL) << (index * 32))
    }

  private def xor(elements: Seq[Int]): BigInt =
    elements.foldLeft(BigInt(0))((value, element) => value ^ BigInt(element & 0xffffffffL))

  private def deleteTree(root: Path): Unit = {
    val paths = Files.walk(root)
    try paths.iterator.asScala.toVector.reverse.foreach(Files.deleteIfExists)
    finally paths.close()
  }

  "SpmvResourceProbeTop" should "serialize AXI beats into independent URAM caches under backpressure" in {
    val laneElements = Seq((1 to 32), (101 to 132))
    val laneBeats = laneElements.map(_.grouped(16).map(packed).toVector)

    simulate(new SpmvResourceProbeTop(smallConfig)) { dut =>
      dut.io.start.poke(false.B)
      for (lane <- 0 until smallConfig.hbmPcCount) {
        dut.io.baseAddresses(lane).poke((lane * 0x1000L).U)
        dut.io.axi(lane).ar.ready.poke(false.B)
        dut.io.axi(lane).r.valid.poke(false.B)
        dut.io.axi(lane).r.bits.id.poke(0.U)
        dut.io.axi(lane).r.bits.data.poke(0.U)
        dut.io.axi(lane).r.bits.resp.poke(0.U)
        dut.io.axi(lane).r.bits.last.poke(false.B)
      }
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)

      for (_ <- 0 until 3) {
        for (lane <- 0 until smallConfig.hbmPcCount) {
          dut.io.axi(lane).ar.valid.expect(true.B)
          dut.io.axi(lane).ar.bits.addr.expect((lane * 0x1000L).U)
          dut.io.axi(lane).ar.bits.len.expect(1.U)
          dut.io.axi(lane).ar.bits.size.expect(6.U)
          dut.io.axi(lane).ar.bits.burst.expect(1.U)
        }
        dut.clock.step()
      }
      for (lane <- 0 until smallConfig.hbmPcCount) dut.io.axi(lane).ar.ready.poke(true.B)
      dut.clock.step()
      for (lane <- 0 until smallConfig.hbmPcCount) dut.io.axi(lane).ar.ready.poke(false.B)

      for (lane <- 0 until smallConfig.hbmPcCount) {
        dut.io.axi(lane).r.valid.poke(true.B)
        dut.io.axi(lane).r.bits.data.poke(laneBeats(lane)(0).U)
        dut.io.axi(lane).r.bits.resp.poke(0.U)
        dut.io.axi(lane).r.bits.last.poke((lane == 0).B)
        dut.io.axi(lane).r.ready.expect(true.B)
      }
      dut.clock.step()

      for (lane <- 0 until smallConfig.hbmPcCount) {
        dut.io.axi(lane).r.bits.data.poke(laneBeats(lane)(1).U)
        dut.io.axi(lane).r.bits.resp.poke((if (lane == 0) 2 else 0).U)
        dut.io.axi(lane).r.bits.last.poke((lane == 0).B)
      }
      var blockedCycles = 0
      while (!(0 until smallConfig.hbmPcCount).forall(lane =>
        dut.io.axi(lane).r.ready.peek().litToBoolean) && blockedCycles < 40) {
        for (lane <- 0 until smallConfig.hbmPcCount) {
          dut.io.axi(lane).r.ready.expect(false.B)
          dut.io.axi(lane).r.bits.data.expect(laneBeats(lane)(1).U)
        }
        dut.clock.step()
        blockedCycles += 1
      }
      assert(blockedCycles == 16, s"单 beat 缓冲应阻塞第二拍 16 周期，实际为 $blockedCycles")
      dut.clock.step()
      for (lane <- 0 until smallConfig.hbmPcCount) dut.io.axi(lane).r.valid.poke(false.B)

      var completionCycles = 0
      while (dut.io.doneMask.peek().litValue != 3 && completionCycles < 100) {
        dut.clock.step()
        completionCycles += 1
      }
      dut.io.doneMask.expect(3.U)
      dut.io.errorMask.expect(3.U)
      dut.io.aggregateChecksum.expect((xor(laneElements(0)) ^ xor(laneElements(1))).U)
    }
  }

  it should "reject a base address that would let a full burst cross 4 KiB" in {
    simulate(new SpmvResourceProbeLane(smallConfig)) { dut =>
      dut.io.start.poke(false.B)
      dut.io.baseAddress.poke(64.U)
      dut.io.axi.ar.ready.poke(false.B)
      dut.io.axi.r.valid.poke(false.B)
      dut.io.axi.r.bits.id.poke(0.U)
      dut.io.axi.r.bits.data.poke(0.U)
      dut.io.axi.r.bits.resp.poke(0.U)
      dut.io.axi.r.bits.last.poke(false.B)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)
      dut.io.done.expect(true.B)
      dut.io.error.expect(true.B)
      dut.io.axi.ar.valid.expect(false.B)
    }
  }

  it should "retain 32 AXI masters and one cache instance below every lane" in {
    val directory = Files.createTempDirectory("spmv-rtl-structure-")
    try {
      _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
        new SpmvResourceProbeTop(SpmvAcceleratorConfig.U55c32PcFp32X8192Uram),
        Array("--target-dir", directory.toString, "--split-verilog"),
        Array("--disable-annotation-unknown")
      )
      val top = Files.readString(directory.resolve("SpmvResourceProbeTop.sv"))
      val lane = Files.readString(directory.resolve("SpmvResourceProbeLane.sv"))
      val masterIndexes = """io_axi_(\d+)_ar_ready""".r.findAllMatchIn(top)
        .map(_.group(1).toInt).toSet
      val laneInstances = """SpmvResourceProbeLane pc\d\d \(""".r.findAllMatchIn(top).size

      assert(masterIndexes == (0 until 32).toSet)
      assert(laneInstances == 32)
      assert("""\) xCacheUramBank\d\d \(""".r.findAllMatchIn(lane).size == 1)
      assert(Files.readString(directory.resolve("SpmvUramMemory.sv")).contains("URAM288_BASE"))
    } finally deleteTree(directory)
  }

  it should "keep four dual-port banks for the eight-lane FP64 cache" in {
    val directory = Files.createTempDirectory("spmv-fp64-rtl-structure-")
    try {
      _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
        new SpmvResourceProbeLane(SpmvAcceleratorConfig.U55c32PcFp64X8192Uram8Lane),
        Array("--target-dir", directory.toString, "--split-verilog"),
        Array("--disable-annotation-unknown")
      )
      val lane = Files.readString(directory.resolve("SpmvResourceProbeLane.sv"))
      assert("""\) xCacheUramBank\d\d \(""".r.findAllMatchIn(lane).size == 4)
      assert("""\.bReadEnable\s*\(scanReadEnable\)""".r.findAllMatchIn(lane).size == 4)
      assert("""\.aReadEnable\s*\(scanReadEnable\)""".r.findAllMatchIn(lane).size == 4)
      assert("""_xCacheUramBank\d\d_[ab]ReadData""".r.findAllMatchIn(lane).map(_.matched).toSet.size == 8)
    } finally deleteTree(directory)
  }
}
