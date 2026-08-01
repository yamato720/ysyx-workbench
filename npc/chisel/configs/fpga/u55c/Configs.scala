package npc.fpga.u55c

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.npc.{
  CacheFpgaConfig,
  CacheRv64PipelineDualForwardingTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecuteFpgaConfig,
  WideHbmCacheRv64PipelineDualForwardingTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecuteFpgaConfig,
  WideHbmL2CacheRv64PipelineDualForwardingTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecuteFpgaConfig,
  FpgaConfig,
  FpgaIpTerminal,
  Rv64PipelineDualForwardingFpgaConfig,
  Rv64PipelineDualForwardingTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecuteFpgaConfig
}
import _root_.npc.{U55cNpcPerformanceMonitorTerminal, U55cNpcTerminal, U55cSocTerminal}
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

/** U55C RV32 裸 NPC 的显式缓存终端；需要独立 rebuild 生成硬件 ABI。 */
class U55cCacheNpcFpgaConfig extends CDEConfig(
  new U55cBoardConfig ++
    new CacheFpgaConfig
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
  * 保持完整运行 ABI 与单实现策略，便于将结果和默认 U55C 终端逐项比较；频率仅属于
  * 板卡物理策略，整数 IP 宽度仍由右侧 RV64 核自动推导。为切分 RV64 乘法器的 DSP
  * 组合链，整数乘法固定为 5 级流水且保持 II=1；其余算术 IP 时序维持 U55C 默认值。
  */
class U55cRv64Npc300MHzFpgaConfig extends CDEConfig(
  new U55c300MHzSdbBoardConfig ++
    new Rv64PipelineDualForwardingTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecuteFpgaConfig
) with U55cNpcTerminal with FpgaIpTerminal

/** U55C RV64 300 MHz 时序核心的显式缓存终端。 */
class U55cRv64CacheNpc300MHzFpgaConfig extends CDEConfig(
  new U55c300MHzSdbBoardConfig ++
    new CacheRv64PipelineDualForwardingTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecuteFpgaConfig
) with U55cNpcTerminal with FpgaIpTerminal

/** U55C RV64IM 300 MHz 的批处理性能监测构造。
 *
 * 该构造使用 v13 ABI、HBM[1] 和独立 256-bit trace master；它只接受
 * `run-bat`，且在硬件中移除 SDB halt/step 与宽状态快照路径。交互调试
 * 继续使用 v11 的 `U55cRv64Npc300MHzFpgaConfig`。
 */
class U55cRv64Npc300MHzPerformanceMonitorFpgaConfig extends CDEConfig(
  new U55c300MHzPerformanceMonitorBoardConfig ++
    new Rv64PipelineDualForwardingTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecuteFpgaConfig
) with U55cNpcPerformanceMonitorTerminal with FpgaIpTerminal

/** U55C RV64 300 MHz cache performance monitor.
 *
 * This is the batch-only v13 monitor with the teaching I$/D$ hierarchy. It
 * writes the normal commit trace to HBM[1] and exposes cache geometry plus
 * live counters through the control mailbox, so it requires a distinct full
 * FPGA rebuild from either the ordinary monitor or the cached SDB terminal.
 */
class U55cRv64CacheNpc300MHzPerformanceMonitorFpgaConfig extends CDEConfig(
  new U55c300MHzPerformanceMonitorBoardConfig ++
    new CacheRv64PipelineDualForwardingTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecuteFpgaConfig
) with U55cNpcPerformanceMonitorTerminal with FpgaIpTerminal

/** U55C RV64 150 MHz cache performance monitor.
 *
 * The NPC core is clocked at 150 MHz; the HBM/control shell remains on the
 * platform's fixed 300 MHz DATA_CLK and uses the monitor wrapper's explicit
 * MMCM and asynchronous channel crossings.
 */
class U55cRv64CacheNpc150MHzPerformanceMonitorFpgaConfig extends CDEConfig(
  new U55cPerformanceMonitorBoardConfig(150) ++
    new CacheRv64PipelineDualForwardingTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecuteFpgaConfig
) with U55cNpcPerformanceMonitorTerminal with FpgaIpTerminal

/**
  * U55C RV64 宽 HBM 缓存监测构造。
  *
  * I$/D$ 使用 64-byte line，`m_axi_gmem` 为 512 bit；每次正常 line refill
  * 和 dirty writeback 恰好各使用一个完整 HBM beat。它与 16-byte 教学缓存监测构造
  * 使用独立 ABI。
  */
class U55cRv64Hbm512CacheNpc150MHzPerformanceMonitorFpgaConfig extends CDEConfig(
  new U55cPerformanceMonitorBoardConfig(150) ++
    new WideHbmCacheRv64PipelineDualForwardingTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecuteFpgaConfig
) with U55cNpcPerformanceMonitorTerminal with FpgaIpTerminal

/** U55C RV64 宽 HBM 缓存监测构造，带共享 write-back L2。
  *
  * CPU 保持 64 bit，I$/D$ 使用 64-byte line。物理 L2 位于两者 AXI-Lite 仲裁器之后、
  * 512-bit AXI4-Full HBM bridge 之前；MMIO 仍由其前方的 host-MMIO slave 消费。
  * 该构造使用独立的 v13 FPGA ABI，必须完整执行 `make -C npc rebuild`。
  */
class U55cRv64Hbm512L2CacheNpc150MHzPerformanceMonitorFpgaConfig extends CDEConfig(
  new U55cPerformanceMonitorBoardConfig(150) ++
    new WideHbmL2CacheRv64PipelineDualForwardingTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecuteFpgaConfig
) with U55cNpcPerformanceMonitorTerminal with FpgaIpTerminal

/** U55C RV64IM performance-monitor terminals with a hardware-generated slow
  * core clock.  The HBM/control shell remains at 300 MHz and is crossed with
  * asynchronous AXI converters, so the core cannot run above the suffix.
  */
class U55cRv64Npc100MHzPerformanceMonitorFpgaConfig extends CDEConfig(
  new U55cPerformanceMonitorBoardConfig(100) ++
    new Rv64PipelineDualForwardingTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecuteFpgaConfig
) with U55cNpcPerformanceMonitorTerminal with FpgaIpTerminal

class U55cRv64Npc125MHzPerformanceMonitorFpgaConfig extends CDEConfig(
  new U55cPerformanceMonitorBoardConfig(125) ++
    new Rv64PipelineDualForwardingTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecuteFpgaConfig
) with U55cNpcPerformanceMonitorTerminal with FpgaIpTerminal

class U55cRv64Npc150MHzPerformanceMonitorFpgaConfig extends CDEConfig(
  new U55cPerformanceMonitorBoardConfig(150) ++
    new Rv64PipelineDualForwardingTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecuteFpgaConfig
) with U55cNpcPerformanceMonitorTerminal with FpgaIpTerminal

class U55cRv64Npc200MHzPerformanceMonitorFpgaConfig extends CDEConfig(
  new U55cPerformanceMonitorBoardConfig(200) ++
    new Rv64PipelineDualForwardingTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecuteFpgaConfig
) with U55cNpcPerformanceMonitorTerminal with FpgaIpTerminal

class U55cRv64Npc250MHzPerformanceMonitorFpgaConfig extends CDEConfig(
  new U55cPerformanceMonitorBoardConfig(250) ++
    new Rv64PipelineDualForwardingTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecuteFpgaConfig
) with U55cNpcPerformanceMonitorTerminal with FpgaIpTerminal

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

/** U55C ysyxSoC 的显式缓存终端。 */
class U55cCacheYsyxSocFpgaConfig extends CDEConfig(
  new U55cBoardConfig ++
    new CacheFpgaConfig ++
    new YsyxElaborateConfig
) with U55cSocTerminal with FpgaIpTerminal
