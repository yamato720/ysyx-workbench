package npc.fpga.u55c

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.fpga.u55c._
import _root_.npc.{
  BaseConfig,
  BranchPredictorConfig,
  ConstructionConfig,
  FinalLogConfig,
  FpgaNpcIntegrationConfig,
  PipelineDualFwdPerformConfig,
  PipelineDualFwdTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecutePerformConfig,
  Rv32IMZicsrConfig,
  Rv64IMZicsrConfig,
  SdbDebugConfig,
  TraceConfig
}
import _root_.npc.{U55cNpcPerformanceMonitorTerminal, U55cNpcTerminal}

/** U55C 裸 NPC 的所有可运行终端构造。 */

/** U55C 的裸 NPC 终端构造：板卡策略加 FPGA 默认核心。 */
class U55cNpcFpgaConfig extends CDEConfig(
  new U55cBoardConfig ++
    new ConstructionConfig(
      new SdbDebugConfig ++
        new BranchPredictorConfig ++
        new Rv32IMZicsrConfig ++
        new PipelineDualFwdPerformConfig ++
        new FpgaNpcIntegrationConfig ++
        new BaseConfig
    )
) with U55cNpcTerminal

/** U55C 的 RV64IM_Zicsr 裸 NPC 终端构造。
  *
  * FPGA 构造显式禁用 F/D 与指令 assist；浮点学习仅由本地仿真终端提供。
  */
class U55cRv64NpcFpgaConfig extends CDEConfig(
  new U55cBoardConfig ++
    new ConstructionConfig(
      new SdbDebugConfig ++
        new BranchPredictorConfig ++
        new Rv64IMZicsrConfig ++
        new PipelineDualFwdPerformConfig ++
        new FpgaNpcIntegrationConfig ++
        new BaseConfig
    )
) with U55cNpcTerminal

/** U55C RV64IM 的 100 MHz 高性能上板终端。
  *
  * 与 300 MHz 时序实验使用相同的核心、前端和整数 IP 配方，但关闭性能监测与 FPGA
  * 运行时 SDB；NEMU 只保留普通 U55C host，不生成调试或性能报告数据。
  */
class U55cRv64Npc100MHzFpgaConfig extends CDEConfig(
  new U55c100MHzBoardConfig ++
    new ConstructionConfig(
      new BranchPredictorConfig ++
        new Rv64IMZicsrConfig ++
        new PipelineDualFwdTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecutePerformConfig ++
        new FpgaNpcIntegrationConfig ++
        new BaseConfig
    )
) with U55cNpcTerminal

/** U55C RV64IM 的 225 MHz 高性能上板终端，关闭性能监测与运行时 SDB。 */
class U55cRv64Npc225MHzFpgaConfig extends CDEConfig(
  new U55c225MHzBoardConfig ++
    new ConstructionConfig(
      new BranchPredictorConfig ++
        new Rv64IMZicsrConfig ++
        new PipelineDualFwdTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecutePerformConfig ++
        new FpgaNpcIntegrationConfig ++
        new BaseConfig
    )
) with U55cNpcTerminal

/** U55C RV64IM 裸 NPC 的 300 MHz 时序实验终端。
  *
  * 保持完整运行 ABI 与单实现策略，便于将结果和默认 U55C 终端逐项比较；频率仅属于
  * 板卡物理策略，整数 IP 宽度仍由右侧 RV64 核自动推导。为切分 RV64 乘法器的 DSP
  * 组合链，整数乘法固定为 5 级流水且保持 II=1；其余算术 IP 时序维持 U55C 默认值。
  */
class U55cRv64Npc300MHzFpgaConfig extends CDEConfig(
  new U55c300MHzSdbBoardConfig ++
    new ConstructionConfig(
      new SdbDebugConfig ++
        new BranchPredictorConfig ++
        new Rv64IMZicsrConfig ++
        new PipelineDualFwdTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecutePerformConfig ++
        new FpgaNpcIntegrationConfig ++
        new BaseConfig
    )
) with U55cNpcTerminal

/** U55C RV64IM 300 MHz 的批处理性能监测构造。
 *
 * 该构造使用 v13 ABI、HBM[1] 和独立 256-bit trace master；它只接受
 * `run-bat`，且在硬件中移除 SDB halt/step 与宽状态快照路径。交互调试
 * 继续使用 v11 的 `U55cRv64Npc300MHzFpgaConfig`。
 */
class U55cRv64Npc300MHzPerformanceMonitorFpgaConfig extends CDEConfig(
  new U55c300MHzPerformanceMonitorBoardConfig ++
    new ConstructionConfig(
      new TraceConfig ++
        new FinalLogConfig ++
        new BranchPredictorConfig ++
        new Rv64IMZicsrConfig ++
        new PipelineDualFwdTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecutePerformConfig ++
        new FpgaNpcIntegrationConfig ++
        new BaseConfig
    )
) with U55cNpcPerformanceMonitorTerminal

/** 使用硬件生成慢速核心时钟的 U55C RV64IM performance-monitor 终端。
  *
  * HBM/control shell 保持 300 MHz，并通过异步 AXI converter 跨时钟域，因此核心
  * 实际频率不会超过类名后缀指定的值。
  */
class U55cRv64Npc100MHzPerformanceMonitorFpgaConfig extends CDEConfig(
  new U55cPerformanceMonitorBoardConfig(100) ++
    new ConstructionConfig(
      new TraceConfig ++
        new FinalLogConfig ++
        new BranchPredictorConfig ++
        new Rv64IMZicsrConfig ++
        new PipelineDualFwdTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecutePerformConfig ++
        new FpgaNpcIntegrationConfig ++
        new BaseConfig
    )
) with U55cNpcPerformanceMonitorTerminal

class U55cRv64Npc125MHzPerformanceMonitorFpgaConfig extends CDEConfig(
  new U55cPerformanceMonitorBoardConfig(125) ++
    new ConstructionConfig(
      new TraceConfig ++
        new FinalLogConfig ++
        new BranchPredictorConfig ++
        new Rv64IMZicsrConfig ++
        new PipelineDualFwdTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecutePerformConfig ++
        new FpgaNpcIntegrationConfig ++
        new BaseConfig
    )
) with U55cNpcPerformanceMonitorTerminal

class U55cRv64Npc150MHzPerformanceMonitorFpgaConfig extends CDEConfig(
  new U55cPerformanceMonitorBoardConfig(150) ++
    new ConstructionConfig(
      new TraceConfig ++
        new FinalLogConfig ++
        new BranchPredictorConfig ++
        new Rv64IMZicsrConfig ++
        new PipelineDualFwdTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecutePerformConfig ++
        new FpgaNpcIntegrationConfig ++
        new BaseConfig
    )
) with U55cNpcPerformanceMonitorTerminal

class U55cRv64Npc200MHzPerformanceMonitorFpgaConfig extends CDEConfig(
  new U55cPerformanceMonitorBoardConfig(200) ++
    new ConstructionConfig(
      new TraceConfig ++
        new FinalLogConfig ++
        new BranchPredictorConfig ++
        new Rv64IMZicsrConfig ++
        new PipelineDualFwdTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecutePerformConfig ++
        new FpgaNpcIntegrationConfig ++
        new BaseConfig
    )
) with U55cNpcPerformanceMonitorTerminal

class U55cRv64Npc250MHzPerformanceMonitorFpgaConfig extends CDEConfig(
  new U55cPerformanceMonitorBoardConfig(250) ++
    new ConstructionConfig(
      new TraceConfig ++
        new FinalLogConfig ++
        new BranchPredictorConfig ++
        new Rv64IMZicsrConfig ++
        new PipelineDualFwdTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecutePerformConfig ++
        new FpgaNpcIntegrationConfig ++
        new BaseConfig
    )
) with U55cNpcPerformanceMonitorTerminal
