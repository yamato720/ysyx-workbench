package npc

/**
  * 为 L2 SoC、L3 FPGA 和 L4 板卡复用而准备的完整 L1 NPC 核。
  *
  * 这里的类固定核心硬件 ABI，但不选择运行宿主，也不挂载 Make 终端 trait；它们只能作为
  * 更高层 CDE `++` 链中的 L1 覆盖项。可直接 `make config=` 的本地仿真终端位于上级
  * `Configs.scala`。
  */

/** RV64IM_Zicsr FPGA 核心成品：外部 AXI、派发控制和 FPGA 主存。 */
class Rv64PipelineDualForwardingFpgaConfig extends ConstructionConfig(
  new Rv64IMZicsrConfig ++
    new PipelineDualFwdPerformConfig ++
    new WithExternalAxiConfig ++
    new WithDispatchControlConfig ++
    new WithTopDebugConfig ++
    new WithFpgaMainMemoryConfig ++
    new BaseConfig
)

/** RV64IM_Zicsr FPGA 核心：双前递和两拍普通整数执行路径。 */
class Rv64PipelineDualForwardingTwoStageIntegerExecuteFpgaConfig extends ConstructionConfig(
  new Rv64IMZicsrConfig ++
    new PipelineDualFwdTwoStageIntegerExecutePerformConfig ++
    new WithExternalAxiConfig ++
    new WithDispatchControlConfig ++
    new WithTopDebugConfig ++
    new WithFpgaMainMemoryConfig ++
    new BaseConfig
)

/** RV64IM_Zicsr FPGA 核心：两拍普通整数执行，并寄存首个取指请求。 */
class Rv64PipelineDualForwardingTwoStageIntegerExecuteRegisteredFetchFpgaConfig extends ConstructionConfig(
  new Rv64IMZicsrConfig ++
    new PipelineDualFwdTwoStageIntegerExecuteRegisteredFetchPerformConfig ++
    new WithExternalAxiConfig ++
    new WithDispatchControlConfig ++
    new WithTopDebugConfig ++
    new WithFpgaMainMemoryConfig ++
    new BaseConfig
)

/** RV64IM_Zicsr FPGA 核心：两拍普通整数执行、寄存首个取指请求，并拆分串行整数 ALU。 */
class Rv64PipelineDualForwardingTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluFpgaConfig
  extends ConstructionConfig(
    new Rv64IMZicsrConfig ++
      new PipelineDualFwdTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluPerformConfig ++
      new WithExternalAxiConfig ++
      new WithDispatchControlConfig ++
      new WithTopDebugConfig ++
      new WithFpgaMainMemoryConfig ++
      new BaseConfig
  )

/** RV64IM_Zicsr FPGA 核心：普通整数两拍，CSR、异常、mret 的串行控制路径三拍。 */
class Rv64PipelineDualForwardingTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecuteFpgaConfig
  extends ConstructionConfig(
    new Rv64IMZicsrConfig ++
      new PipelineDualFwdTwoStageIntegerExecuteRegisteredFetchSeparateSerialIntegerAluThreeStageSerialExecutePerformConfig ++
      new WithExternalAxiConfig ++
      new WithDispatchControlConfig ++
      new WithTopDebugConfig ++
      new WithFpgaMainMemoryConfig ++
      new BaseConfig
  )

/** 裸核 FPGA 默认 NPC：外部 AXI、调试派发控制、M 扩展和顶层调试 IO。 */
class FpgaConfig extends ConstructionConfig(
  new Rv32IMZicsrConfig ++
    new PipelineDualFwdPerformConfig ++
    new WithExternalAxiConfig ++
    new WithDispatchControlConfig ++
    new WithTopDebugConfig ++
    new WithFpgaMainMemoryConfig ++
    new BaseConfig
)

/** 供 SoC 或其他外部系统集成的 NPC，导出 AXI master 而不启用 FPGA 派发控制。 */
class ExternalAxiConfig extends ConfigBundle(
  new Rv32IMZicsrConfig ++
    new PipelineDualFwdPerformConfig ++
    new WithExternalAxiConfig ++
    new WithTopDebugConfig ++
    new WithSoCMainMemoryConfig ++
    new BaseConfig
)
