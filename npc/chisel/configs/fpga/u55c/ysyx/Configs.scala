package ysyx.fpga.u55c

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.fpga.u55c.U55cBoardConfig
import _root_.npc.{BaseConfig, BranchPredictorConfig, FpgaNpcIntegrationConfig, PipelineDualFwdPerformConfig,
  Rv32IMZicsrConfig, SdbDebugConfig, U55cSocTerminal, WithTerminalIpCoreConfig}
import _root_.ysyx.YsyxElaborateConfig

/** U55C 的 ysyxSoC FPGA 终端构造。 */
class U55cYsyxSocFpgaConfig extends CDEConfig(
  new U55cBoardConfig ++
    new WithTerminalIpCoreConfig(
      new SdbDebugConfig ++
        new BranchPredictorConfig ++
        new Rv32IMZicsrConfig ++
        new PipelineDualFwdPerformConfig ++
        new FpgaNpcIntegrationConfig ++
        new BaseConfig
    ) ++
    new YsyxElaborateConfig
) with U55cSocTerminal
