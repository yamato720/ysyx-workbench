package scpu

import org.scalatest.flatspec.AnyFlatSpec

class NpcConfigCompositionTest extends AnyFlatSpec {
  private val fpgaSettings = NpcBuildSettings(
    xlen = 32,
    target = "NPC",
    pipeline = false,
    interlock = true,
    idForwarding = false,
    executeForwarding = false,
    floatingPoint = false,
    zicsr = true,
    arithmeticBackend = ComputeBackend.FPGA,
    arithmeticOutputFifoDepth = 4,
    multiplyCycles = 3,
    multiplyInitiationInterval = 1,
    divCycles = 37,
    divInitiationInterval = 1,
    floatingAddSubCycles = 3,
    floatingAddSubInitiationInterval = 1,
    floatingMultiplyCycles = 4,
    floatingMultiplyInitiationInterval = 1,
    floatingDivideCycles = 29,
    floatingDivideInitiationInterval = 1,
    floatingFmaCycles = 4,
    floatingFmaInitiationInterval = 1,
    floatingSqrtCycles = 29,
    floatingSqrtInitiationInterval = 1,
    floatingConvertCycles = 7,
    floatingConvertInitiationInterval = 1,
    floatingCompareCycles = 3,
    floatingCompareInitiationInterval = 1,
    memoryBase = 0x80000000L,
    memorySize = 0x10000000L
  )

  "NpcConfigFragment ++" should "apply the right fragment first and keep left overrides" in {
    val config = (
      new WithExternalAxiConfig(idWidth = 8) ++
        new WithNpcXlenConfig(32) ++
        new WithMExtensionConfig ++
        new BaseNpcConfig
    ).build

    assert(config.isa.xlen == 32)
    assert(config.isa.M)
    assert(config.axi.useExternalMaster)
    assert(config.axi.dataWidth == 32)
    assert(config.axi.idWidth == 8)
  }

  "NpcFpgaConfig" should "compose the named FPGA construction with elaboration settings" in {
    val config = (
      new NpcFpgaConfig ++
        new WithNpcElaborationSettings(fpgaSettings)
    ).build

    assert(config.isa.xlen == 32)
    assert(config.isa.M)
    assert(config.debug.enableTopDebugIo)
    assert(config.debug.enableDispatchControl)
    assert(config.axi.useExternalMaster)
    assert(config.axi.dataWidth == 32)
  }

  it should "remain composable when an integration adds a later fragment" in {
    val config = (
      new WithPipelineConfig ++
        new NpcFpgaConfig ++
        new WithNpcElaborationSettings(fpgaSettings)
    ).build

    assert(config.isa.xlen == 32)
    assert(config.isa.M)
    assert(config.pipeline.enablePipeline)
    assert(config.debug.enableDispatchControl)
    assert(config.axi.useExternalMaster)
    assert(config.axi.dataWidth == 32)
  }

  "Zicsr fragments" should "make the extension explicit and preserve left precedence" in {
    val disabled = (new WithoutZicsrConfig ++ new BaseNpcConfig).build
    val enabled = (new WithZicsrConfig ++ new WithoutZicsrConfig ++ new BaseNpcConfig).build

    assert(!disabled.isa.Zicsr)
    assert(!disabled.isa.F)
    assert(enabled.isa.Zicsr)
  }
}
