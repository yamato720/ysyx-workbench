package npc.fpga.u55c

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.fpga.u55c._
import _root_.npc.{
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
