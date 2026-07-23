package scpu.fpga.u55c

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.scpu.{FpgaConfig, FpgaConstructionConfig, FpgaNpcTerminalConfig, FpgaSocTerminalConfig}
import _root_.scpu.{FpgaToolchainConfig, FullIsa64PipelineDualForwardingFpgaConfig, NemuHostConfig}
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
) with FpgaConstructionConfig with FpgaNpcTerminalConfig {
  override protected val configuredNemu: NemuHostConfig = NemuHostConfig.U55cBase
  override protected val configuredFpga: FpgaToolchainConfig = FpgaToolchainConfig.U55cBase
}

/** U55C 的 RV64IMF_Zicsr 裸 NPC 终端构造。
  *
  * ID/EX 前递保留在核心；PL 只实现无舍入浮点操作，其余 F 操作经 mailbox 交给
  * NEMU。当前 XRT 没有可消费的 IRQ 文件描述符，因此宿主以轮询完成同一序号的响应。
  */
class U55cFullIsa64NpcFpgaConfig extends CDEConfig(
  new U55cBoardConfig ++
    new FullIsa64PipelineDualForwardingFpgaConfig
) with FpgaConstructionConfig with FpgaNpcTerminalConfig {
  override protected val configuredNemu: NemuHostConfig = NemuHostConfig.U55cBase
  override protected val configuredFpga: FpgaToolchainConfig = FpgaToolchainConfig.U55cBase
}

/** U55C RV64IMF 裸 NPC 的 250 MHz 时序实验终端。
  *
  * 保持完整运行 ABI 与单实现策略，便于将结果和 125 MHz 终端逐项比较；频率仅属于
  * 板卡物理策略，整数 IP 宽度仍由右侧 RV64 核自动推导。
  */
class U55cFullIsa64Npc250MHzFpgaConfig extends CDEConfig(
  new U55cBoardConfig(250) ++
    new FullIsa64PipelineDualForwardingFpgaConfig
) with FpgaConstructionConfig with FpgaNpcTerminalConfig {
  override protected val configuredNemu: NemuHostConfig = NemuHostConfig.U55cBase
  override protected val configuredFpga: FpgaToolchainConfig = FpgaToolchainConfig.U55cBase
}

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
) with FpgaConstructionConfig with FpgaSocTerminalConfig {
  override protected val configuredNemu: NemuHostConfig = NemuHostConfig.U55cBase
  override protected val configuredFpga: FpgaToolchainConfig = FpgaToolchainConfig.U55cBase
}
