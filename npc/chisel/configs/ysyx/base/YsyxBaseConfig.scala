package ysyx

import org.chipsalliance.cde.config.{Config => CDEConfig}
import freechips.rocketchip.system.{DefaultRV32Config, Edge32BitConfig}

/** ysyxSoC 的 Rocket 基础配置：RV32 与 32 位边缘总线。 */
class BaseYsyxConfig extends CDEConfig(
  new Edge32BitConfig ++
    new DefaultRV32Config
)
