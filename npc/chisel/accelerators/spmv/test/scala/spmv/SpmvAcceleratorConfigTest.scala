package spmv

import npc._
import org.scalatest.flatspec.AnyFlatSpec
import org.chipsalliance.cde.config.Parameters

class SpmvAcceleratorConfigTest extends AnyFlatSpec {
  private val base = SpmvAcceleratorConfig.U55c32PcFp32X8192Uram
  private val fp64 = SpmvAcceleratorConfig.U55c32PcFp64X8192Uram8Lane

  "SpmvAcceleratorConfig" should "derive the formal FP32 resource-probe geometry" in {
    assert(base.elementsPerBeat == 16)
    assert(base.beatsPerPc == 512)
    assert(base.burstsPerPc == 8)
    assert(base.burstBytes == 4096)
    assert(base.baseAlignmentBytes == 4096)
    assert(base.bytesPerPc == 32768)
    assert(base.totalCacheBytes == 1048576)
  }

  it should "accept FP64 and a one-beat AXI configuration" in {
    val fp64 = base.copy(
      hbmPcCount = 2,
      elementWidth = 64,
      elementsPerPc = 64,
      burstBeats = 4
    )
    assert(fp64.elementsPerBeat == 8)
    assert(fp64.beatsPerPc == 8)
    assert(fp64.burstsPerPc == 2)
    assert(fp64.baseAlignmentBytes == 256)

    val oneBeat = base.copy(
      hbmPcCount = 1,
      axiDataWidth = 64,
      elementWidth = 64,
      elementsPerPc = 1,
      burstBeats = 1
    )
    assert(oneBeat.beatsPerPc == 1)
    assert(oneBeat.baseAlignmentBytes == 8)
  }

  it should "describe four dual-port banks that provide eight FP64 lanes" in {
    assert(fp64.elementWidth == 64)
    assert(fp64.elementsPerBeat == 8)
    assert(fp64.beatsPerPc == 1024)
    assert(fp64.burstsPerPc == 16)
    assert(fp64.burstBytes == 4096)
    assert(fp64.baseAlignmentBytes == 4096)
    assert(fp64.bytesPerPc == 65536)
    assert(fp64.totalCacheBytes == 2097152)
    assert(fp64.uramBanksPerPc == 4)
    assert(fp64.uramBankDepth == 2048)
    assert(fp64.readElementsPerCycle == 8)
    assert(fp64.writeElementsPerCycle == 8)
    assert(fp64.clockMHz == 225)
  }

  it should "reject invalid channel, AXI, capacity, burst, and bandwidth combinations" in {
    assertThrows[IllegalArgumentException](base.copy(hbmPcCount = 0))
    assertThrows[IllegalArgumentException](base.copy(hbmPcCount = 33))
    assertThrows[IllegalArgumentException](base.copy(axiAddrWidth = 32))
    assertThrows[IllegalArgumentException](base.copy(axiDataWidth = 96))
    assertThrows[IllegalArgumentException](base.copy(axiIdWidth = 0))
    assertThrows[IllegalArgumentException](base.copy(elementWidth = 16))
    assertThrows[IllegalArgumentException](base.copy(elementsPerPc = 24))
    assertThrows[IllegalArgumentException](base.copy(elementsPerPc = 32, burstBeats = 4))
    assertThrows[IllegalArgumentException](base.copy(burstBeats = 3))
    assertThrows[IllegalArgumentException](base.copy(burstBeats = 128))
    assertThrows[IllegalArgumentException](base.copy(readElementsPerCycle = 3))
    assertThrows[IllegalArgumentException](fp64.copy(readElementsPerCycle = 4))
    assertThrows[IllegalArgumentException](fp64.copy(writeElementsPerCycle = 4))
    assertThrows[IllegalArgumentException](fp64.copy(uramBanksPerPc = 2, readElementsPerCycle = 8))
    assertThrows[IllegalArgumentException](base.copy(outstandingBurstsPerPc = 2))
    assertThrows[IllegalArgumentException](base.copy(clockMHz = 0))
  }

  "SpmvConstructionProfile" should "publish accelerator fields without CPU or NEMU fields" in {
    val entry = ConfigCatalog.Entry(
      shortName = "ProbeConfig",
      className = "npc.fpga.u55c.ProbeConfig",
      scope = "fpga",
      board = Some("u55c"),
      target = "SPMV"
    )
    val construction = new FpgaSynthesisConstruction with AcceleratorHostConstruction {
      override protected def configuredFpga: FpgaToolchainConfig = FpgaToolchainConfig.U55cBase
      override protected def configuredAcceleratorHost: AcceleratorHostConfig = AcceleratorHostConfig.SpmvGolden
    }
    val values = SpmvConstructionProfile.values(entry, construction, base, Seq.empty).toMap

    assert(values("CAPABILITY") == "synthesize-only")
    assert(values("HOST_ABI") == "none")
    assert(values("ACCELERATOR_HOST_KIND") == "spmv")
    assert(values("ACCELERATOR_HOST_ABI") == "spmv-golden-v1")
    assert(values("PROTOCOL_ABI") == "spmv-resource-probe-v1")
    assert(values("SPMV_ELEMENT_FORMAT") == "fp32-bit-pattern")
    assert(values("SPMV_BASE_ALIGNMENT_BYTES") == "4096")
    assert(!values.contains("XLEN"))
    assert(!values.contains("ISA_STRING"))
    assert(!values.keys.exists(_.startsWith("NEMU_")))
    assert(!values.contains("PIPELINE"))
  }

  it should "publish the FP64 eight-lane bitstream ABI without CPU fields" in {
    val entry = ConfigCatalog.Entry(
      shortName = "BitstreamProbeConfig",
      className = "npc.fpga.u55c.BitstreamProbeConfig",
      scope = "fpga",
      board = Some("u55c"),
      target = "SPMV"
    )
    val construction = new FpgaBitstreamConstruction with AcceleratorHostConstruction {
      override protected def configuredFpga: FpgaToolchainConfig = FpgaToolchainConfig.U55cBase
      override protected def configuredAcceleratorHost: AcceleratorHostConfig = AcceleratorHostConfig.SpmvGolden
    }
    val values = SpmvConstructionProfile.values(entry, construction, fp64, Seq.empty).toMap

    assert(values("CAPABILITY") == "bitstream-only")
    assert(values("PROTOCOL_ABI") == "spmv-resource-probe-v2")
    assert(values("SPMV_ELEMENT_WIDTH") == "64")
    assert(values("SPMV_URAM_BANKS_PER_PC") == "4")
    assert(values("SPMV_URAM_BANK_DEPTH") == "2048")
    assert(values("SPMV_PARALLEL_READ_LANES") == "8")
    assert(values("SPMV_PARALLEL_WRITE_LANES") == "8")
    assert(values("SPMV_CLOCK_MHZ") == "225")
    assert(!values.contains("XLEN"))
    assert(!values.keys.exists(_.startsWith("NEMU_")))
  }

}
