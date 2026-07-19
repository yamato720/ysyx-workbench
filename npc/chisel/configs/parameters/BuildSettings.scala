package scpu

/** Scala Config 内部使用的固定 NPC ABI 参数。
  *
  * 这些值只能由命名 Config 组合，不再从 Make 变量、JVM property 或环境变量读取。
  */
final case class NpcBuildSettings(
  xlen: Int,
  target: String,
  pipeline: Boolean,
  interlock: Boolean,
  idForwarding: Boolean,
  executeForwarding: Boolean,
  floatingPoint: Boolean,
  zicsr: Boolean,
  arithmeticBackend: ComputeBackend,
  arithmeticOutputFifoDepth: Int,
  multiplyCycles: Int,
  multiplyInitiationInterval: Int,
  divCycles: Int,
  divInitiationInterval: Int,
  floatingAddSubCycles: Int,
  floatingAddSubInitiationInterval: Int,
  floatingMultiplyCycles: Int,
  floatingMultiplyInitiationInterval: Int,
  floatingDivideCycles: Int,
  floatingDivideInitiationInterval: Int,
  floatingFmaCycles: Int,
  floatingFmaInitiationInterval: Int,
  floatingSqrtCycles: Int,
  floatingSqrtInitiationInterval: Int,
  floatingConvertCycles: Int,
  floatingConvertInitiationInterval: Int,
  floatingCompareCycles: Int,
  floatingCompareInitiationInterval: Int,
  memoryBase: Long,
  memorySize: Long
) {
  require(xlen == 32 || xlen == 64, s"NPC XLEN must be 32 or 64, got $xlen")
  require(target == "NPC" || target == "SOC", s"NPC target must be NPC or SOC, got $target")
  require(divCycles >= 1, s"NPC DIV completion cycles must be positive, got $divCycles")
  require(!floatingPoint || arithmeticBackend != ComputeBackend.IP,
    "NPC_ARITH_BACKEND=ip cannot be used with NPC_F=1: Vivado 2022.2 FPO lacks " +
      "dynamic RISC-V rounding (including RMM), NX reporting, and unsigned float-to-integer conversion. " +
      "Use NPC_ARITH_BACKEND=model for ISA-correct RV32F/RV64F execution.")

  private[scpu] val arithmeticImplementation = ComputeUnitConfig(
    backend = arithmeticBackend,
    ip = IpComputeConfig(outputFifoDepth = arithmeticOutputFifoDepth)
  )
}

object NpcBuildSettings {
  private def fixed(
    xlen: Int,
    target: String,
    pipeline: Boolean,
    floatingPoint: Boolean,
    zicsr: Boolean = true,
    backend: ComputeBackend,
    memorySize: Long
  ): NpcBuildSettings = NpcBuildSettings(
    xlen = xlen,
    target = target,
    pipeline = pipeline,
    interlock = true,
    idForwarding = pipeline,
    executeForwarding = pipeline,
    floatingPoint = floatingPoint,
    zicsr = zicsr,
    arithmeticBackend = backend,
    arithmeticOutputFifoDepth = 4,
    multiplyCycles = 3,
    multiplyInitiationInterval = 1,
    divCycles = 37,
    divInitiationInterval = 1,
    floatingAddSubCycles = 3,
    floatingAddSubInitiationInterval = 1,
    floatingMultiplyCycles = 4,
    floatingMultiplyInitiationInterval = 1,
    floatingDivideCycles = 29,
    floatingDivideInitiationInterval = 1,
    floatingFmaCycles = 4,
    floatingFmaInitiationInterval = 1,
    floatingSqrtCycles = 29,
    floatingSqrtInitiationInterval = 1,
    floatingConvertCycles = 7,
    floatingConvertInitiationInterval = 1,
    floatingCompareCycles = 3,
    floatingCompareInitiationInterval = 1,
    memoryBase = 0x80000000L,
    memorySize = memorySize
  )

  val NpcStandalone64: NpcBuildSettings = fixed(
    xlen = 64,
    target = "NPC",
    pipeline = false,
    floatingPoint = false,
    backend = ComputeBackend.Builtin,
    memorySize = 0x10000000L
  )

  val NpcPipeline64: NpcBuildSettings = NpcStandalone64.copy(
    pipeline = true,
    idForwarding = true,
    executeForwarding = true
  )

  /** RV64IMF_Zicsr 性能对比的三组固定核心 ABI。
    *
    * 三者只改变流水线及两条旁路通路，避免将 ISA、算术时序或存储 ABI 的变化
    * 混入性能结论。无流水线构造明确关闭旁路字段，便于 profile 和构造记录直接
    * 反映实际比较维度。
    */
  val NpcFullIsa64NoPipeline: NpcBuildSettings = NpcStandalone64.copy(
    pipeline = false,
    idForwarding = false,
    executeForwarding = false,
    floatingPoint = true
  )

  val NpcFullIsa64PipelineNoForwarding: NpcBuildSettings = NpcFullIsa64NoPipeline.copy(
    pipeline = true,
    idForwarding = false,
    executeForwarding = false
  )

  val NpcFullIsa64PipelineDualForwarding: NpcBuildSettings = NpcFullIsa64NoPipeline.copy(
    pipeline = true,
    idForwarding = true,
    executeForwarding = true
  )

  /** 与双路径前递性能构造保持相同 ISA/时序，但选择 FPGA 组件策略。 */
  val NpcFullIsa64PipelineDualForwardingFpga: NpcBuildSettings =
    NpcFullIsa64PipelineDualForwarding.copy(
      arithmeticBackend = ComputeBackend.FPGA,
      memorySize = 0x08000000L
    )

  val YsyxSimulation32: NpcBuildSettings = fixed(
    xlen = 32,
    target = "SOC",
    pipeline = true,
    floatingPoint = false,
    backend = ComputeBackend.Builtin,
    memorySize = 0x08000000L
  )

  val FpgaNpc32: NpcBuildSettings = fixed(
    xlen = 32,
    target = "NPC",
    pipeline = true,
    floatingPoint = true,
    backend = ComputeBackend.FPGA,
    memorySize = 0x08000000L
  )

  val FpgaSoc32: NpcBuildSettings = FpgaNpc32.copy(target = "SOC")
}
