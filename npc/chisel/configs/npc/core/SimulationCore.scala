package scpu

/** 本地 NPC 终端可直接引用的完整硬件组合。
  *
  * 本层把 ISA、性能、接口、算术与内存底层片段组合成具名核心；终端只负责挂载
  * 运行宿主和 Make 终端 trait，不再直接依赖 `base/` 中的原子片段。
  */

/** 最小 RV64I_Zicsr 标量核心。 */
class StandaloneCoreConfig extends ConfigBundle(
  new Rv64IZicsrConfig ++
    new ScalarPerformConfig ++
    new WithTopDebugConfig ++
    new WithModelComputeConfig ++
    new WithDefaultArithmeticTimingConfig ++
    new WithBareMainMemoryConfig ++
    new BaseConfig
)

/** 默认 RV64IM_Zicsr 标量核心。 */
class SimulationCoreConfig extends ConfigBundle(
  new Rv64IMZicsrConfig ++
    new ScalarPerformConfig ++
    new WithTopDebugConfig ++
    new WithModelComputeConfig ++
    new WithDefaultArithmeticTimingConfig ++
    new WithBareMainMemoryConfig ++
    new BaseConfig
)

/** 默认 RV64IM_Zicsr 双前递流水核心。 */
class PipelineSimulationCoreConfig extends ConfigBundle(
  new Rv64IMZicsrConfig ++
    new PipelineDualFwdPerformConfig ++
    new WithTopDebugConfig ++
    new WithModelComputeConfig ++
    new WithDefaultArithmeticTimingConfig ++
    new WithBareMainMemoryConfig ++
    new BaseConfig
)

/** RV64IMF_Zicsr 无流水线性能基线。 */
class FullIsa64NoPipelineSimulationCoreConfig extends ConfigBundle(
  new Rv64IMFZicsrConfig ++
    new ScalarPerformConfig ++
    new WithTopDebugConfig ++
    new WithModelComputeConfig ++
    new WithDefaultArithmeticTimingConfig ++
    new WithBareMainMemoryConfig ++
    new BaseConfig
)

/** RV64IMF_Zicsr 流水线无前递核心。 */
class FullIsa64PipelineNoForwardingSimulationCoreConfig extends ConfigBundle(
  new Rv64IMFZicsrConfig ++
    new PipelinePerformConfig ++
    new WithTopDebugConfig ++
    new WithModelComputeConfig ++
    new WithDefaultArithmeticTimingConfig ++
    new WithBareMainMemoryConfig ++
    new BaseConfig
)

/** RV64IMF_Zicsr 流水线双前递核心。 */
class FullIsa64PipelineDualForwardingSimulationCoreConfig extends ConfigBundle(
  new Rv64IMFZicsrConfig ++
    new PipelineDualFwdPerformConfig ++
    new WithTopDebugConfig ++
    new WithModelComputeConfig ++
    new WithDefaultArithmeticTimingConfig ++
    new WithBareMainMemoryConfig ++
    new BaseConfig
)

/** ZCU102 RV32 算子能力的本地周期模型核心。 */
class Zcu102Rv32OperatorSimulationCoreConfig extends ConfigBundle(
  new Rv32IMFZicsrConfig ++
    new PipelineDualFwdPerformConfig ++
    new WithTopDebugConfig ++
    new WithModelComputeConfig ++
    new WithArithmeticTimingConfig(OperatorIpTimingConfig.Default) ++
    new WithBareMainMemoryConfig ++
    new BaseConfig
)

/** U55C RV32 算子能力的本地周期模型核心。 */
class U55cRv32OperatorSimulationCoreConfig extends ConfigBundle(
  new Rv32IMFZicsrConfig ++
    new PipelineDualFwdPerformConfig ++
    new WithTopDebugConfig ++
    new WithModelComputeConfig ++
    new WithArithmeticTimingConfig(OperatorIpTimingConfig.Default) ++
    new WithBareMainMemoryConfig ++
    new BaseConfig
)

/** U55C RV64 M/F 算子能力的本地周期模型核心。 */
class U55cRv64OperatorSimulationCoreConfig extends ConfigBundle(
  new Rv64IMFZicsrConfig ++
    new PipelineDualFwdPerformConfig ++
    new WithTopDebugConfig ++
    new WithModelComputeConfig ++
    new WithArithmeticTimingConfig(OperatorIpTimingConfig.Default) ++
    new WithBareMainMemoryConfig ++
    new BaseConfig
)
