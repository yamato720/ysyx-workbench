package scpu

/**
  * 为 L2 SoC、L3 FPGA 和 L4 板卡复用而准备的完整 L1 NPC 核。
  *
  * 这里的类固定核心硬件 ABI，但不选择运行宿主，也不混入 Make 终端 marker；它们只能作为
  * 更高层 CDE `++` 链中的 L1 覆盖项。可直接 `make config=` 的本地仿真终端位于上级
  * `Configs.scala`。
  */

/** RV64IMF_Zicsr FPGA 核心成品：外部 AXI、派发控制和 NEMU mailbox F 回退。 */
class FullIsa64PipelineDualForwardingFpgaConfig extends ConstructionConfig(
  new Rv64IMFZicsrConfig ++
    new PipelineDualFwdPerformConfig ++
    new WithExternalAxiConfig ++
    new WithDispatchControlConfig ++
    new WithTopDebugConfig ++
    new WithFpgaComputeConfig ++
    new WithDefaultArithmeticTimingConfig ++
    new WithFpgaMainMemoryConfig ++
    new BaseConfig
)

/** 裸核 FPGA 默认 NPC：外部 AXI、调试派发控制、M 扩展和顶层调试 IO。 */
class FpgaConfig extends ConstructionConfig(
  new Rv32IMFZicsrConfig ++
    new PipelineDualFwdPerformConfig ++
    new WithExternalAxiConfig ++
    new WithDispatchControlConfig ++
    new WithTopDebugConfig ++
    new WithFpgaComputeConfig ++
    new WithDefaultArithmeticTimingConfig ++
    new WithFpgaMainMemoryConfig ++
    new BaseConfig
)

/** 供 SoC 或其他外部系统集成的 NPC，导出 AXI master 而不启用 FPGA 派发控制。 */
class ExternalAxiConfig extends ConstructionConfig(
  new Rv32IMZicsrConfig ++
    new PipelineDualFwdPerformConfig ++
    new WithExternalAxiConfig ++
    new WithTopDebugConfig ++
    new WithModelComputeConfig ++
    new WithDefaultArithmeticTimingConfig ++
    new WithSoCMainMemoryConfig ++
    new BaseConfig
)
