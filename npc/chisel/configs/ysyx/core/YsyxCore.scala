package ysyx

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.scpu.ExternalAxiConfig

/** 通用 ysyxSoC：默认外部 AXI NPC 加 Rocket 基础配置。
  * 板卡 SoC 构造可在左侧直接叠加任意完整 NPC Config，以覆盖其默认 NPC。
  */
class YsyxSocConfig extends CDEConfig(
  new ExternalAxiConfig ++
    new BaseYsyxConfig
)

/** 提供 ysyxSoC 组合图给板卡终端或直接 Scala 展开使用，不是 Make 终端。 */
class YsyxElaborateConfig extends CDEConfig(
  new YsyxSocConfig
)
