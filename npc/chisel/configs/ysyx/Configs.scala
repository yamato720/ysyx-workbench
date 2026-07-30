package ysyx

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.npc.{LocalSocTerminal, NemuSimulationIpTerminal}

/** ysyxSoC 仿真构造，使用默认的 Simulation 平台行为。 */
class YsyxSimulationConfig extends CDEConfig(
  new YsyxSocConfig
) with LocalSocTerminal with NemuSimulationIpTerminal

/** 显式启用教学缓存层级的 ysyxSoC 本地仿真终端。 */
class CacheYsyxSimulationConfig extends CDEConfig(
  new CacheYsyxSocConfig
) with LocalSocTerminal with NemuSimulationIpTerminal
