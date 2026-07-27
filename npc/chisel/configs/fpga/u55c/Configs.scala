package npc.fpga.u55c

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.npc.{
  FpgaConfig,
  FpgaIpTerminal,
  Rv64PipelineDualForwardingFpgaConfig,
  Rv64PipelineDualForwardingTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecuteFpgaConfig
}
import _root_.npc.{U55cNpcTerminal, U55cSocTerminal}
import _root_.ysyx.YsyxElaborateConfig

/** U55C 的所有可运行终端构造。
  *
  * 板卡层只固定 U55C 的物理策略；终端可选择裸 NPC 或 ysyxSoC。两者共用 `fpga`
  * 构造作用域，自动目录以 `TARGET=NPC|SOC` 选择对应 elaborator，而不再把它编码进
  * 文件布局或构造行为。
  */

/** U55C 的裸 NPC 终端构造：板卡策略加 FPGA 默认核心。 */
class U55cNpcFpgaConfig extends CDEConfig(
  new U55cBoardConfig ++
    new FpgaConfig
) with U55cNpcTerminal with FpgaIpTerminal

/** U55C 的 RV64IM_Zicsr 裸 NPC 终端构造。
  *
  * FPGA 构造显式禁用 F/D 与指令 assist；浮点学习仅由本地仿真终端提供。
  */
class U55cRv64NpcFpgaConfig extends CDEConfig(
  new U55cBoardConfig ++
    new Rv64PipelineDualForwardingFpgaConfig
) with U55cNpcTerminal with FpgaIpTerminal

/** U55C RV64IM 裸 NPC 的 300 MHz 时序实验终端。
  *
  * 保持完整运行 ABI 与单实现策略，便于将结果和 125 MHz 终端逐项比较；频率仅属于
  * 板卡物理策略，整数 IP 宽度仍由右侧 RV64 核自动推导。为切分 RV64 乘法器的 DSP
  * 组合链，整数乘法固定为 5 级流水且保持 II=1；其余算术 IP 时序维持 U55C 默认值。
  */
class U55cRv64Npc300MHzFpgaConfig extends CDEConfig(
  new U55c300MHzBoardConfig ++
    new Rv64PipelineDualForwardingTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecuteFpgaConfig
) with U55cNpcTerminal with FpgaIpTerminal

/** U55C 的 ysyxSoC FPGA 终端构造。
  *
  * `YsyxElaborateConfig` 右侧提供 Rocket/ysyxSoC 与默认外部 AXI 核心；左侧完整
  * L1 `FpgaConfig` 用相同的 `NpcCoreConfigKey` 覆盖该预设核。`U55cBoardConfig`
  * 已经绑定 `FpgaBoardKey`，SoC 因此自动选择 FPGA 外设与 mailbox 分支。
  */
class U55cYsyxSocFpgaConfig extends CDEConfig(
  new U55cBoardConfig ++
    new FpgaConfig ++
    new YsyxElaborateConfig
) with U55cSocTerminal with FpgaIpTerminal
