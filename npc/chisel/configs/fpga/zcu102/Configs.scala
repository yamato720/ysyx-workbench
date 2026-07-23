package scpu.fpga.zcu102

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.scpu.{FpgaConfig, Zcu102NpcTerminal, Zcu102SocTerminal}
import _root_.ysyx.YsyxElaborateConfig

/** ZCU102 的所有可运行终端构造。
  *
  * 板卡终端共用 `fpga` 作用域；`TARGET=NPC|SOC` 决定使用裸核或 ysyxSoC FPGA
  * elaborator，避免以文件名或构造行为重复分类。
  */

/** ZCU102 的裸 NPC 终端构造：板卡策略加 FPGA 默认核心。 */
class Zcu102NpcFpgaConfig extends CDEConfig(
  new Zcu102BoardConfig ++
    new FpgaConfig
) with Zcu102NpcTerminal

/** ZCU102 的 ysyxSoC FPGA 终端构造。
  *
  * 左侧完整 L1 `FpgaConfig` 覆盖右侧 `YsyxElaborateConfig` 默认的外部 AXI
  * 核心；`Zcu102BoardConfig` 的板卡键同时让 SoC 采用 FPGA 外设分支。
  */
class Zcu102YsyxSocFpgaConfig extends CDEConfig(
  new Zcu102BoardConfig ++
    new FpgaConfig ++
    new YsyxElaborateConfig
) with Zcu102SocTerminal
