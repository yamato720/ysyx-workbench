package npc.fpga.u55c

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.fpga.u55c._
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
import _root_.npc.{U55cNpcPerformanceMonitorTerminal, U55cNpcTerminal}

/** U55C 裸 NPC 的所有可运行终端构造。 */

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

/** U55C RV64 300 MHz cache 性能监测终端。
  *
  * 该 batch-only v13 终端启用教学 I$/D$，把 commit trace 写入 HBM[1]，并通过
  * control mailbox 暴露 cache 几何与计数器，因此必须与普通 monitor 和 cache SDB
  * 终端分别执行完整 FPGA rebuild。
  */
class U55cRv64CacheNpc300MHzPerformanceMonitorFpgaConfig extends CDEConfig(
  new U55c300MHzPerformanceMonitorBoardConfig ++
    new CacheRv64PipelineDualForwardingTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecuteFpgaConfig
) with U55cNpcPerformanceMonitorTerminal with FpgaIpTerminal

/** U55C RV64 150 MHz cache 性能监测终端。
  *
  * NPC 核运行在 150 MHz；HBM/control shell 保持平台固定的 300 MHz `DATA_CLK`，
  * monitor wrapper 通过显式 MMCM 与异步通道完成跨时钟域连接。
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

/** 使用硬件生成慢速核心时钟的 U55C RV64IM performance-monitor 终端。
  *
  * HBM/control shell 保持 300 MHz，并通过异步 AXI converter 跨时钟域，因此核心
  * 实际频率不会超过类名后缀指定的值。
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
