package scpu

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
