package accelerators.spmv.fpga.u55c

import org.chipsalliance.cde.config.{Config => CDEConfig}
import accelerators.spmv.{
  SpmvAcceleratorConfig,
  SpmvCuperflowConfig,
  SpmvInputConfig,
  U55cSpmvBitstreamTerminal,
  U55cSpmvCuperflowBitstreamTerminal,
  U55cSpmvCuperflowSynthesisTerminal,
  U55cSpmvInputRuntimeTerminal,
  U55cSpmvSynthesisTerminal,
  WithSpmvAcceleratorConfig,
  WithSpmvCuperflowConfig,
  WithSpmvCuperflowLocalXPingPongConfig,
  WithSpmvInputConfig
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

/** U55C 实机验证：19 路 HBM 输入、pingpong local_X 与 FP64 乘法 checksum。 */
class U55cSpmvInputPingPongFpgaConfig extends CDEConfig(
  new WithSpmvInputConfig(SpmvInputConfig.Cuper16HbmPingPongU55c) ++
    new U55cSpmvBoardConfig
) with U55cSpmvInputRuntimeTerminal

/** U55C 实机验证：300 MHz HBM shell、250 MHz 输入与 FP64 乘法核心。 */
class U55cSpmvInputPingPong250MHzFpgaConfig extends CDEConfig(
  new WithSpmvInputConfig(SpmvInputConfig.Cuper16HbmPingPongU55c) ++
    new U55cSpmvBoardConfig(coreClockMHz = 250)
) with U55cSpmvInputRuntimeTerminal

/** 当前 Cuperflow map -> X -> A RTL 的 U55C 250 MHz 只综合入口。 */
class U55cSpmvCuperflow250MHzSynthesisConfig extends CDEConfig(
  new WithSpmvCuperflowConfig(SpmvCuperflowConfig.U55c) ++
    new U55cSpmvBoardConfig(coreClockMHz = 250)
) with U55cSpmvCuperflowSynthesisTerminal

/** 当前 Cuperflow map -> X -> A RTL 的 U55C 250 MHz bitstream 入口。 */
class U55cSpmvCuperflow250MHzBitstreamConfig extends CDEConfig(
  new WithSpmvCuperflowConfig(SpmvCuperflowConfig.U55c) ++
    new U55cSpmvBoardConfig(coreClockMHz = 250)
) with U55cSpmvCuperflowBitstreamTerminal

/** 将 Cuperflow 缩至 8 个 HBM PC 的 250 MHz 时序收敛探针。 */
class U55cSpmvCuperflow8Pc250MHzTimingProbeConfig extends CDEConfig(
  new WithSpmvCuperflowConfig(SpmvCuperflowConfig.U55c.copy(hbmPcCount = 8)) ++
    new U55cSpmvBoardConfig(coreClockMHz = 250)
) with U55cSpmvCuperflowBitstreamTerminal

/** 仅保留一个 HBM PC 的 250 MHz 时序隔离探针。 */
class U55cSpmvCuperflow1Pc250MHzTimingProbeConfig extends CDEConfig(
  new WithSpmvCuperflowConfig(SpmvCuperflowConfig.U55c.copy(hbmPcCount = 1)) ++
    new U55cSpmvBoardConfig(coreClockMHz = 250)
) with U55cSpmvCuperflowSynthesisTerminal

/** 仅保留一个 HBM PC 的 250 MHz Cuperflow bitstream 入口。 */
class U55cSpmvCuperflow1Pc250MHzBitstreamConfig extends CDEConfig(
  new WithSpmvCuperflowConfig(SpmvCuperflowConfig.U55c.copy(hbmPcCount = 1)) ++
    new U55cSpmvBoardConfig(coreClockMHz = 250)
) with U55cSpmvCuperflowBitstreamTerminal

/** 同一 Cuperflow FPGA 通路，local-X 打开第二套 ping/pong 窗口。 */
class U55cSpmvCuperflowPingPong250MHzSynthesisConfig extends CDEConfig(
  new WithSpmvCuperflowLocalXPingPongConfig ++
    new WithSpmvCuperflowConfig(SpmvCuperflowConfig.U55c) ++
    new U55cSpmvBoardConfig(coreClockMHz = 250)
) with U55cSpmvCuperflowSynthesisTerminal

/** 同一 Cuperflow FPGA bitstream 通路，local-X 打开第二套 ping/pong 窗口。 */
class U55cSpmvCuperflowPingPong250MHzBitstreamConfig extends CDEConfig(
  new WithSpmvCuperflowLocalXPingPongConfig ++
    new WithSpmvCuperflowConfig(SpmvCuperflowConfig.U55c) ++
    new U55cSpmvBoardConfig(coreClockMHz = 250)
) with U55cSpmvCuperflowBitstreamTerminal
