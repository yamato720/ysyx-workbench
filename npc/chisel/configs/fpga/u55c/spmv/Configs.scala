package accelerators.spmv.fpga.u55c

import org.chipsalliance.cde.config.{Config => CDEConfig}
import accelerators.spmv.{
  SpmvAcceleratorConfig,
  U55cSpmvBitstreamTerminal,
  U55cSpmvSynthesisTerminal,
  WithSpmvAcceleratorConfig
}

/** 32 路 HBM、每路 8192 项 FP32 UltraRAM X cache 的只综合资源探针。 */
class U55cSpmv32PcFp32X8192UramResourceProbeConfig extends CDEConfig(
  new WithSpmvAcceleratorConfig(SpmvAcceleratorConfig.U55c32PcFp32X8192Uram) ++
    new U55cSpmvBoardConfig
) with U55cSpmvSynthesisTerminal

/** 32 路 HBM、每路 8192 项 FP64 X cache 的 bitstream 资源探针。 */
class U55cSpmv32PcFp64X8192UramBitstreamConfig extends CDEConfig(
  new WithSpmvAcceleratorConfig(SpmvAcceleratorConfig.U55c32PcFp64X8192Uram8Lane) ++
    new U55cSpmvBoardConfig(coreClockMHz = 225)
) with U55cSpmvBitstreamTerminal
