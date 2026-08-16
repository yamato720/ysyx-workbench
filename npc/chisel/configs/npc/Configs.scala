package npc

/** 最小独立 NPC 本地仿真终端，导出顶层调试 IO。 */
class StandaloneConfig extends ConstructionConfig(
  new BranchPredictorConfig ++
    new Rv64IZicsrConfig ++
    new ScalarPerformConfig ++
    new BareNpcIntegrationConfig ++
    new BaseConfig
) with LocalNpcTerminal with NemuSimulationIpTerminal

/** 默认 RV64IM_Zicsr 标量本地仿真终端。 */
class SimulationConfig extends ConstructionConfig(
  new BranchPredictorConfig ++
    new Rv64IMZicsrConfig ++
    new ScalarPerformConfig ++
    new BareNpcIntegrationConfig ++
    new BaseConfig
) with LocalNpcTerminal with NemuSimulationIpTerminal

/** 教学 I$/D$ 本地仿真终端：4 KiB、16-byte line、2-way Tree-PLRU。 */
class CacheSimulationConfig extends ConstructionConfig(
  new BranchPredictorConfig ++
    new Rv64IMZicsrConfig ++
    new ScalarPerformConfig ++
    new PipelinedTwoCycleTeachingCacheConfig ++
    new BareNpcIntegrationConfig ++
    new BaseConfig
) with LocalNpcTerminal with NemuSimulationIpTerminal

/** 仅含宽 HBM 风格 L1 的本地时序终端：64-byte line 与 73--81 cycle DPI 抖动。 */
class HbmJitterCacheSimulationConfig extends ConstructionConfig(
  new BranchPredictorConfig ++
    new WithNpcOutstandingCompletionForwardingConfig ++
    new Rv64IMZicsrConfig ++
    new PipelineDualFwdOneStageIntegerExecuteDirectWritebackRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecutePerformConfig ++
    new PipelinedTwoCycleWideHbmCacheConfig ++
    new LocalWideHbmJitterIntegrationConfig ++
    new BaseConfig
) with LocalNpcTerminal with NemuSimulationIpTerminal

/** 宽 HBM 风格 L1/L2 本地时序终端：在宽 L1 后增加共享 256 KiB、8-way L2。 */
class HbmJitterL2CacheSimulationConfig extends ConstructionConfig(
  new BranchPredictorConfig ++
    new WithNpcOutstandingCompletionForwardingConfig ++
    new Rv64IMZicsrConfig ++
    new PipelineDualFwdOneStageIntegerExecuteDirectWritebackRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecutePerformConfig ++
    new PipelinedTwoCycleWideHbmL2CacheConfig ++
    new LocalWideHbmJitterIntegrationConfig ++
    new BaseConfig
) with LocalNpcTerminal with NemuSimulationIpTerminal

/** 默认 RV64IM_Zicsr 双前递流水本地仿真终端。 */
class PipelineSimulationConfig extends ConstructionConfig(
  new BranchPredictorConfig ++
    new Rv64IMZicsrConfig ++
    new PipelineDualFwdPerformConfig ++
    new BareNpcIntegrationConfig ++
    new BaseConfig
) with LocalNpcTerminal with NemuSimulationIpTerminal

/** RV64IM_Zicsr 无流水线性能基线。 */
class FullIsa64NoPipelineSimulationConfig extends ConstructionConfig(
  new BranchPredictorConfig ++
    new Rv64IMZicsrConfig ++
    new ScalarPerformConfig ++
    new BareNpcIntegrationConfig ++
    new BaseConfig
) with LocalNpcTerminal with NemuSimulationIpTerminal

/** RV64IM_Zicsr 流水线无前递对照终端。 */
class FullIsa64PipelineNoForwardingSimulationConfig extends ConstructionConfig(
  new BranchPredictorConfig ++
    new Rv64IMZicsrConfig ++
    new PipelinePerformConfig ++
    new BareNpcIntegrationConfig ++
    new BaseConfig
) with LocalNpcTerminal with NemuSimulationIpTerminal

/** RV64IM_Zicsr 流水线双前递对照终端。 */
class FullIsa64PipelineDualForwardingSimulationConfig extends ConstructionConfig(
  new BranchPredictorConfig ++
    new Rv64IMZicsrConfig ++
    new PipelineDualFwdPerformConfig ++
    new BareNpcIntegrationConfig ++
    new BaseConfig
) with LocalNpcTerminal with NemuSimulationIpTerminal

/** ZCU102 RV32 算子能力的本地周期模型终端。 */
class Zcu102Rv32OperatorSimulationConfig extends ConstructionConfig(
  new BranchPredictorConfig ++
    new Rv32IMZicsrConfig ++
    new PipelineDualFwdPerformConfig ++
    new BareNpcIntegrationConfig ++
    new BaseConfig
) with LocalNpcTerminal with NemuSimulationIpTerminal

/** U55C RV32 算子能力的本地周期模型终端。 */
class U55cRv32OperatorSimulationConfig extends ConstructionConfig(
  new BranchPredictorConfig ++
    new Rv32IMZicsrConfig ++
    new PipelineDualFwdPerformConfig ++
    new BareNpcIntegrationConfig ++
    new BaseConfig
) with LocalNpcTerminal with NemuSimulationIpTerminal

/** U55C RV64 M 算子能力的本地周期模型终端。 */
class U55cRv64OperatorSimulationConfig extends ConstructionConfig(
  new BranchPredictorConfig ++
    new Rv64IMZicsrConfig ++
    new PipelineDualFwdPerformConfig ++
    new BareNpcIntegrationConfig ++
    new BaseConfig
) with LocalNpcTerminal with NemuSimulationIpTerminal
