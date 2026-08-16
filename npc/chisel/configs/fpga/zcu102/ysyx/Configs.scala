package ysyx.fpga.zcu102

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.fpga.zcu102.Zcu102BoardConfig
import _root_.npc.{BaseConfig, BranchPredictorConfig, FpgaIpTerminal, FpgaNpcIntegrationConfig, PipelineDualFwdPerformConfig,
  Rv32IMZicsrConfig, WithTerminalIpCoreConfig, Zcu102SocTerminal}
import _root_.ysyx.YsyxElaborateConfig

/** ZCU102 的 ysyxSoC FPGA 终端构造。 */
class Zcu102YsyxSocFpgaConfig extends CDEConfig(
  new Zcu102BoardConfig ++
    new WithTerminalIpCoreConfig(
      new BranchPredictorConfig ++
        new Rv32IMZicsrConfig ++
        new PipelineDualFwdPerformConfig ++
        new FpgaNpcIntegrationConfig ++
        new BaseConfig
    ) ++
    new YsyxElaborateConfig
) with Zcu102SocTerminal with FpgaIpTerminal
