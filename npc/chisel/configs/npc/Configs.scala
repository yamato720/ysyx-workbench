package scpu

/** 最小独立 NPC 本地仿真终端，导出顶层调试 IO。 */
class StandaloneConfig extends ConstructionConfig(
  new Rv64IZicsrConfig ++
    new ScalarPerformConfig ++
    new WithTopDebugConfig ++
    new WithModelComputeConfig ++
    new WithDefaultArithmeticTimingConfig ++
    new WithBareMainMemoryConfig ++
    new BaseConfig
) with NemuSimulationConstructionConfig with NpcTerminalConfig {
  override protected val configuredNemu: NemuHostConfig = NemuHostConfig.LocalPipelineTrace
}

/** 默认 NPC 本地仿真终端，带 M 扩展和顶层调试 IO。 */
class SimulationConfig extends ConstructionConfig(
  new Rv64IMZicsrConfig ++
    new ScalarPerformConfig ++
    new WithTopDebugConfig ++
    new WithModelComputeConfig ++
    new WithDefaultArithmeticTimingConfig ++
    new WithBareMainMemoryConfig ++
    new BaseConfig
) with NemuSimulationConstructionConfig with NpcTerminalConfig {
  override protected val configuredNemu: NemuHostConfig = NemuHostConfig.LocalPipelineTrace
}

/** 启用流水线的 NPC 本地仿真终端。 */
class PipelineSimulationConfig extends ConstructionConfig(
  new Rv64IMZicsrConfig ++
    new PipelineDualFwdPerformConfig ++
    new WithTopDebugConfig ++
    new WithModelComputeConfig ++
    new WithDefaultArithmeticTimingConfig ++
    new WithBareMainMemoryConfig ++
    new BaseConfig
) with NemuSimulationConstructionConfig with NpcTerminalConfig {
  override protected val configuredNemu: NemuHostConfig = NemuHostConfig.LocalPipelineTrace
}

/** RV64IMF_Zicsr 对比基线：无流水线、无旁路。 */
class FullIsa64NoPipelineSimulationConfig extends ConstructionConfig(
  new Rv64IMFZicsrConfig ++
    new ScalarPerformConfig ++
    new WithTopDebugConfig ++
    new WithModelComputeConfig ++
    new WithDefaultArithmeticTimingConfig ++
    new WithBareMainMemoryConfig ++
    new BaseConfig
) with NemuSimulationConstructionConfig with NpcTerminalConfig {
  override protected val configuredNemu: NemuHostConfig = NemuHostConfig.LocalPipelineTrace
}

/** RV64IMF_Zicsr 对比构造：流水线开启，但 ID/EX 前递都关闭。 */
class FullIsa64PipelineNoForwardingSimulationConfig extends ConstructionConfig(
  new Rv64IMFZicsrConfig ++
    new PipelinePerformConfig ++
    new WithTopDebugConfig ++
    new WithModelComputeConfig ++
    new WithDefaultArithmeticTimingConfig ++
    new WithBareMainMemoryConfig ++
    new BaseConfig
) with NemuSimulationConstructionConfig with NpcTerminalConfig {
  override protected val configuredNemu: NemuHostConfig = NemuHostConfig.LocalPipelineTrace
}

/** RV64IMF_Zicsr 对比构造：流水线开启，并同时启用 ID 与 EX 两条前递路径。 */
class FullIsa64PipelineDualForwardingSimulationConfig extends ConstructionConfig(
  new Rv64IMFZicsrConfig ++
    new PipelineDualFwdPerformConfig ++
    new WithTopDebugConfig ++
    new WithModelComputeConfig ++
    new WithDefaultArithmeticTimingConfig ++
    new WithBareMainMemoryConfig ++
    new BaseConfig
) with NemuSimulationConstructionConfig with NpcTerminalConfig {
  override protected val configuredNemu: NemuHostConfig = NemuHostConfig.LocalPipelineTrace
}

/** ZCU102 RV32 算子能力和时序的本地周期精确模拟，不引入 FPO 数值近似。 */
class Zcu102Rv32OperatorSimulationConfig extends ConstructionConfig(
  new Rv32IMFZicsrConfig ++
    new PipelineDualFwdPerformConfig ++
    new WithTopDebugConfig ++
    new WithModelComputeConfig ++
    new WithArithmeticTimingConfig(OperatorIpTimingConfig.Default) ++
    new WithBareMainMemoryConfig ++
    new BaseConfig
) with NemuSimulationConstructionConfig with NpcTerminalConfig {
  override protected val configuredNemu: NemuHostConfig = NemuHostConfig.LocalPipelineTrace
}

/** U55C RV32 算子能力和时序的本地周期精确模拟。 */
class U55cRv32OperatorSimulationConfig extends ConstructionConfig(
  new Rv32IMFZicsrConfig ++
    new PipelineDualFwdPerformConfig ++
    new WithTopDebugConfig ++
    new WithModelComputeConfig ++
    new WithArithmeticTimingConfig(OperatorIpTimingConfig.Default) ++
    new WithBareMainMemoryConfig ++
    new BaseConfig
) with NemuSimulationConstructionConfig with NpcTerminalConfig {
  override protected val configuredNemu: NemuHostConfig = NemuHostConfig.LocalPipelineTrace
}

/** U55C RV64 M/F 算子时序模拟，覆盖 RV64 W 指令而不链接厂商黑盒。 */
class U55cRv64OperatorSimulationConfig extends ConstructionConfig(
  new Rv64IMFZicsrConfig ++
    new PipelineDualFwdPerformConfig ++
    new WithTopDebugConfig ++
    new WithModelComputeConfig ++
    new WithArithmeticTimingConfig(OperatorIpTimingConfig.Default) ++
    new WithBareMainMemoryConfig ++
    new BaseConfig
) with NemuSimulationConstructionConfig with NpcTerminalConfig {
  override protected val configuredNemu: NemuHostConfig = NemuHostConfig.LocalPipelineTrace
}

/** 用于流水线功能检查的整数 NPC。 */
class PipelineCheckConfig extends ConstructionConfig(
  new Rv64IMZicsrConfig ++
    new PipelineDualFwdPerformConfig ++
    new WithTopDebugConfig ++
    new BaseConfig
) with CheckOnlyConstructionConfigBase

/** 用于带 F 扩展流水线功能检查的 NPC。 */
class FloatingCheckConfig extends ConstructionConfig(
  new Rv64IMFZicsrConfig ++
    new PipelineDualFwdPerformConfig ++
    new WithTopDebugConfig ++
    new BaseConfig
) with CheckOnlyConstructionConfigBase

/** 用于乘除法延迟检查的流水线 NPC。 */
class MulDivCheckConfig extends ConstructionConfig(
  new Rv64IMZicsrConfig ++
    new PipelineDualFwdPerformConfig ++
    new WithTopDebugConfig ++
    new BaseConfig
) with CheckOnlyConstructionConfigBase
