package npc.spmv

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.npc.{
  LocalSpmvInputTerminal,
  SpmvInputConfig,
  WithSpmvInputConfig
}

/** 只验证 Cuper 输入顶层静态接口的本地 Verilator smoke 构造。 */
class SpmvInputSimulationConfig extends CDEConfig(
  new WithSpmvInputConfig(SpmvInputConfig.Cuper16Hbm)
) with LocalSpmvInputTerminal
