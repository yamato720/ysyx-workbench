package npc

/** 最小独立 NPC 本地仿真终端，导出顶层调试 IO。 */
class StandaloneConfig extends ConstructionConfig(
  new StandaloneCoreConfig
) with LocalNpcTerminal with NemuSimulationIpTerminal

/** 默认 NPC 本地仿真终端，带 M 扩展和顶层调试 IO。 */
class SimulationConfig extends ConstructionConfig(
  new SimulationCoreConfig
) with LocalNpcTerminal with NemuSimulationIpTerminal

/** 显式启用教学 I$/D$ 与四项顺序取指缓冲的本地仿真终端。 */
class CacheSimulationConfig extends ConstructionConfig(
  new CacheSimulationCoreConfig
) with LocalNpcTerminal with NemuSimulationIpTerminal

/**
  * 仅含 L1 的宽 HBM 本地时序实验。73--81 cycle 的确定性 DPI 响应区间是经过校准的
  * 功能模型，不是逐周期精确的 HBM 控制器仿真。
  */
class HbmJitterCacheSimulationConfig extends ConstructionConfig(
  new HbmJitterCacheSimulationCoreConfig
) with LocalNpcTerminal with NemuSimulationIpTerminal

/** L1+L2 宽 HBM DPI 抖动构造，用于和仅 L1 端点进行本地周期对比。 */
class HbmJitterL2CacheSimulationConfig extends ConstructionConfig(
  new HbmJitterL2CacheSimulationCoreConfig
) with LocalNpcTerminal with NemuSimulationIpTerminal

/** 本地 RV64IM 两拍 L1/L2、512-bit DPI Fabric 仿真终端。 */
class PipelinedTwoCycleWideL2SimulationConfig extends ConstructionConfig(
  new PipelinedTwoCycleWideL2SimulationCoreConfig
) with LocalNpcTerminal with NemuSimulationIpTerminal

/** 仅 L1 的宽 HBM DPI 抖动构造，启用交互式 SDB VCD 采集。 */
class HbmJitterCacheVcdSimulationConfig extends ConstructionConfig(
  new HbmJitterCacheSimulationCoreConfig
) with LocalNpcTerminal with NemuSimulationIpTerminal {
  override protected val configuredNemu: NemuHostConfig = NemuHostConfig.LocalVcdTrace
}

/** 启用流水线的 NPC 本地仿真终端。 */
class PipelineSimulationConfig extends ConstructionConfig(
  new PipelineSimulationCoreConfig
) with LocalNpcTerminal with NemuSimulationIpTerminal

/** RV64IMF_Zicsr 对比基线：无流水线、无旁路。 */
class FullIsa64NoPipelineSimulationConfig extends ConstructionConfig(
  new FullIsa64NoPipelineSimulationCoreConfig
) with LocalNpcTerminal with NemuSimulationIpTerminal

/** RV64IMF_Zicsr 对比构造：流水线开启，但 ID/EX 前递都关闭。 */
class FullIsa64PipelineNoForwardingSimulationConfig extends ConstructionConfig(
  new FullIsa64PipelineNoForwardingSimulationCoreConfig
) with LocalNpcTerminal with NemuSimulationIpTerminal

/** RV64IMF_Zicsr 对比构造：流水线开启，并同时启用 ID 与 EX 两条前递路径。 */
class FullIsa64PipelineDualForwardingSimulationConfig extends ConstructionConfig(
  new FullIsa64PipelineDualForwardingSimulationCoreConfig
) with LocalNpcTerminal with NemuSimulationIpTerminal

/** ZCU102 RV32 算子能力和时序的本地周期精确模拟，不引入 FPO 数值近似。 */
class Zcu102Rv32OperatorSimulationConfig extends ConstructionConfig(
  new Zcu102Rv32OperatorSimulationCoreConfig
) with LocalNpcTerminal with NemuSimulationIpTerminal

/** U55C RV32 算子能力和时序的本地周期精确模拟。 */
class U55cRv32OperatorSimulationConfig extends ConstructionConfig(
  new U55cRv32OperatorSimulationCoreConfig
) with LocalNpcTerminal with NemuSimulationIpTerminal

/** U55C RV64 M/F 算子时序模拟，覆盖 RV64 W 指令而不链接厂商黑盒。 */
class U55cRv64OperatorSimulationConfig extends ConstructionConfig(
  new U55cRv64OperatorSimulationCoreConfig
) with LocalNpcTerminal with NemuSimulationIpTerminal
