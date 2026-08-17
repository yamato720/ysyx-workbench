package npc

/** 用于流水线功能检查的整数 NPC。 */
class PipelineCheckConfig extends ConstructionConfig(
  new SdbDebugConfig ++
    new FinalLogConfig ++
    new BranchPredictorConfig ++
    new Rv64IMZicsrConfig ++
    new PipelineDualFwdPerformConfig ++
    new BaseConfig
) with CheckOnlyConstruction

/** 用于乘除法延迟检查的流水线 NPC。 */
class MulDivCheckConfig extends ConstructionConfig(
  new SdbDebugConfig ++
    new FinalLogConfig ++
    new BranchPredictorConfig ++
    new Rv64IMZicsrConfig ++
    new PipelineDualFwdPerformConfig ++
    new BaseConfig
) with CheckOnlyConstruction
