package accelerators.spmv

import org.chipsalliance.cde.config.{Config => CDEConfig}

/** 驱动 Cuper A/X 正式输入并生成流水报告的本地 Verilator 构造。 */
class SpmvInputSimulationConfig extends CDEConfig(
  new WithSpmvInputConfig(SpmvInputConfig.Cuper16Hbm)
) with LocalSpmvInputTerminal

/** 端口安全的 X/A pingpong 提前发射构造。 */
class SpmvInputPingPongSimulationConfig extends CDEConfig(
  new WithSpmvInputConfig(SpmvInputConfig.Cuper16HbmPingPong)
) with LocalSpmvInputTerminal

/** Cuperflow 16-PC、每 PC 8-lane X decoder 的本地 Verilator 构造。 */
class SpmvCuperflowSimulationConfig extends CDEConfig(
  new WithSpmvCuperflowConfig(SpmvCuperflowConfig.Simulation)
) with LocalSpmvCuperflowTerminal

/** 同一 Cuperflow 数据通路，local-X 打开第二套 ping/pong 窗口。 */
class SpmvCuperflowPingPongSimulationConfig extends CDEConfig(
  new WithSpmvCuperflowLocalXPingPongConfig ++
    new WithSpmvCuperflowConfig(SpmvCuperflowConfig.Simulation)
) with LocalSpmvCuperflowTerminal
