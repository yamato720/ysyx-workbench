package ysyx

import org.chipsalliance.cde.config.{Config => CDEConfig}
import freechips.rocketchip.system.{DefaultRV32Config, Edge32BitConfig}

/** ysyxSoC 的 Rocket 基础配置：RV32 与 32 位边缘总线。 */
class BaseYsyxConfig extends CDEConfig(
  new Edge32BitConfig ++
    new DefaultRV32Config
)

/** 为 SoC 构造写入运行平台标识；外围模块据此选择 DPI 或 FPGA 行为。 */
class WithYsyxPlatformConfig(platform: YsyxPlatform) extends CDEConfig((site, here, up) => {
  case YsyxPlatformKey => platform
})
