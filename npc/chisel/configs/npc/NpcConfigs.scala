package scpu

/** 最小独立 NPC 顶层，导出顶层调试 IO。 */
class NpcStandaloneConfig extends NpcConstructionConfig(
  new WithTopDebugConfig ++
    new WithNpcElaborationSettings(NpcBuildSettings.NpcStandalone64) ++
    new BaseNpcConfig,
  capability = "elaborate-only"
)

/** 供 DPI 仿真使用的 NPC，带 M 扩展和顶层调试 IO。 */
class NpcDpiConfig extends NpcConstructionConfig(
  new WithMExtensionConfig ++
    new WithTopDebugConfig ++
    new WithNpcElaborationSettings(NpcBuildSettings.NpcStandalone64) ++
    new BaseNpcConfig,
  capability = "verilator-npc"
)

/** 启用流水线的 DPI 仿真 NPC。 */
class NpcPipelineDpiConfig extends NpcConstructionConfig(
  new WithMExtensionConfig ++
    new WithTopDebugConfig ++
    new WithNpcElaborationSettings(NpcBuildSettings.NpcPipeline64) ++
    new BaseNpcConfig,
  capability = "verilator-npc"
)

/** RV64IMF_Zicsr 对比基线：无流水线、无旁路。 */
class NpcFullIsa64NoPipelineDpiConfig extends NpcConstructionConfig(
  new WithMExtensionConfig ++
    new WithFloatingPointConfig ++
    new WithTopDebugConfig ++
    new WithNpcElaborationSettings(NpcBuildSettings.NpcFullIsa64NoPipeline) ++
    new BaseNpcConfig,
  capability = "verilator-npc"
)

/** RV64IMF_Zicsr 对比构造：流水线开启，但 ID/EX 前递都关闭。 */
class NpcFullIsa64PipelineNoForwardingDpiConfig extends NpcConstructionConfig(
  new WithMExtensionConfig ++
    new WithFloatingPointConfig ++
    new WithTopDebugConfig ++
    new WithNpcElaborationSettings(NpcBuildSettings.NpcFullIsa64PipelineNoForwarding) ++
    new BaseNpcConfig,
  capability = "verilator-npc"
)

/** RV64IMF_Zicsr 对比构造：流水线开启，并同时启用 ID 与 EX 两条前递路径。 */
class NpcFullIsa64PipelineDualForwardingDpiConfig extends NpcConstructionConfig(
  new WithMExtensionConfig ++
    new WithFloatingPointConfig ++
    new WithTopDebugConfig ++
    new WithNpcElaborationSettings(NpcBuildSettings.NpcFullIsa64PipelineDualForwarding) ++
    new BaseNpcConfig,
  capability = "verilator-npc"
)

/** RV64IMF_Zicsr FPGA 核心成品：外部 AXI、派发控制和 NEMU mailbox F 回退。 */
class NpcFullIsa64PipelineDualForwardingFpgaConfig extends NpcConstructionConfig(
  new WithExternalAxiConfig ++
    new WithDispatchControlConfig ++
    new WithMExtensionConfig ++
    new WithFloatingPointConfig ++
    new WithTopDebugConfig ++
    new WithNpcElaborationSettings(NpcBuildSettings.NpcFullIsa64PipelineDualForwardingFpga) ++
    new BaseNpcConfig,
  capability = "elaborate-only"
)

/** 裸核 FPGA 默认 NPC：外部 AXI、调试派发控制、M 扩展和顶层调试 IO。 */
class NpcFpgaConfig extends NpcConstructionConfig(
  new WithExternalAxiConfig ++
    new WithDispatchControlConfig ++
    new WithMExtensionConfig ++
    new WithTopDebugConfig ++
    new WithNpcElaborationSettings(NpcBuildSettings.FpgaNpc32) ++
    new BaseNpcConfig,
  capability = "elaborate-only"
)

/** 供 SoC 或其他外部系统集成的 NPC，导出 AXI master 而不启用 FPGA 派发控制。 */
class NpcExternalAxiConfig extends NpcConstructionConfig(
  new WithExternalAxiConfig ++
    new WithMExtensionConfig ++
    new WithTopDebugConfig ++
    new WithNpcElaborationSettings(NpcBuildSettings.YsyxSimulation32) ++
    new BaseNpcConfig,
  capability = "elaborate-only"
)

/** 用于流水线功能检查的整数 NPC。 */
class NpcPipelineCheckConfig extends NpcConstructionConfig(
  new WithPipelineConfig ++
    new WithMExtensionConfig ++
    new WithTopDebugConfig ++
    new BaseNpcConfig,
  capability = "check-only"
)

/** 用于带 F 扩展流水线功能检查的 NPC。 */
class NpcFloatingCheckConfig extends NpcConstructionConfig(
  new WithPipelineConfig ++
    new WithFloatingPointConfig ++
    new WithMExtensionConfig ++
    new WithTopDebugConfig ++
    new BaseNpcConfig,
  capability = "check-only"
)

/** 用于乘除法延迟检查的流水线 NPC。 */
class NpcMulDivCheckConfig extends NpcConstructionConfig(
  new WithPipelineConfig ++
    new WithMExtensionConfig ++
    new WithTopDebugConfig ++
    new BaseNpcConfig,
  capability = "check-only"
)
