package fpga

import accelerators.spmv._
import accelerators.spmv.fpga.u55c.{
  U55cSpmvCuperflow1Pc250MHzBitstreamConfig,
  U55cSpmvCuperflow1Pc250MHzTimingProbeConfig,
  U55cSpmvCuperflow8Pc250MHzTimingProbeConfig
}
import npc.ConfigCatalog
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec

class SpmvCuperflowFpgaConfigTest extends AnyFlatSpec {
  "U55cSpmvCuperflow1Pc250MHzTimingProbeConfig" should
      "freeze a single independent HBM PC without changing the per-PC lane width" in {
    val construction = new U55cSpmvCuperflow1Pc250MHzTimingProbeConfig
    implicit val parameters: Parameters = construction
    val config = parameters(SpmvCuperflowConfigKey).get
    val entry = ConfigCatalog.resolve(construction.getClass.getName, Set("fpga"))
    val values = SpmvCuperflowFpgaProfile.values(entry, construction, config, Seq.empty).toMap

    assert(construction.capability == "synthesize-only")
    assert(config.hbmPcCount == 1)
    assert(values("SPMV_CUPERFLOW_HBM_PC_COUNT") == "1")
    assert(values("SPMV_FP64_MUL_CORE_COUNT") == "1")
    assert(values("SPMV_FP64_MUL_TOTAL_LANES") == "8")
  }

  "U55cSpmvCuperflow8Pc250MHzTimingProbeConfig" should
      "freeze the eight-PC, 250 MHz Cuperflow timing-probe geometry" in {
    val construction = new U55cSpmvCuperflow8Pc250MHzTimingProbeConfig
    implicit val parameters: Parameters = construction
    val config = parameters(SpmvCuperflowConfigKey).get
    val entry = ConfigCatalog.resolve(construction.getClass.getName, Set("fpga"))
    val values = SpmvCuperflowFpgaProfile.values(entry, construction, config, Seq.empty).toMap

    assert(construction.capability == "bitstream-only")
    assert(config.hbmPcCount == 8)
    assert(values("SPMV_CUPERFLOW_HBM_PC_COUNT") == "8")
    assert(values("SPMV_FP64_MUL_CORE_COUNT") == "8")
    assert(values("SPMV_FP64_MUL_TOTAL_LANES") == "64")
  }

  "U55cSpmvCuperflow1Pc250MHzBitstreamConfig" should
      "freeze the single-PC geometry for the Vitis link flow" in {
    val construction = new U55cSpmvCuperflow1Pc250MHzBitstreamConfig
    implicit val parameters: Parameters = construction
    val config = parameters(SpmvCuperflowConfigKey).get
    val entry = ConfigCatalog.resolve(construction.getClass.getName, Set("fpga"))
    val values = SpmvCuperflowFpgaProfile.values(entry, construction, config, Seq.empty).toMap

    assert(construction.capability == "bitstream-only")
    assert(config.hbmPcCount == 1)
    assert(values("SPMV_CUPERFLOW_HBM_PC_COUNT") == "1")
    assert(values("SPMV_FP64_MUL_CORE_COUNT") == "1")
    assert(values("SPMV_FP64_MUL_TOTAL_LANES") == "8")
  }
}
