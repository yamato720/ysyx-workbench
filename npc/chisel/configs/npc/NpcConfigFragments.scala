package scpu

/** 将 elaboration 入口读取一次的构造设置写入无依赖 NPC 参数。 */
class WithNpcElaborationSettings(settings: NpcBuildSettings) extends NpcConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig = base.copy(
    isa = base.isa.copy(xlen = settings.xlen, F = settings.floatingPoint, Zicsr = settings.zicsr),
    pipeline = base.pipeline.copy(
      enablePipeline = settings.pipeline,
      enableInterlock = settings.interlock,
      forwarding = base.pipeline.forwarding.copy(
        enableIdForwarding = settings.idForwarding,
        enableExecuteForwarding = settings.executeForwarding
      )
    ),
    operators = OperatorConfig(
      mulDiv = MulDivAlu.Config(
        implementation = settings.arithmeticImplementation,
        completionCycles = settings.divCycles,
        multiplyTiming = ArithmeticIpTiming(settings.multiplyCycles, settings.multiplyInitiationInterval,
          settings.arithmeticOutputFifoDepth),
        dividerInitiationInterval = settings.divInitiationInterval
      ),
      floating = FloatingAlu.Config(
        implementation = settings.arithmeticImplementation,
        addSubTiming = ArithmeticIpTiming(settings.floatingAddSubCycles, settings.floatingAddSubInitiationInterval,
          settings.arithmeticOutputFifoDepth),
        multiplyTiming = ArithmeticIpTiming(settings.floatingMultiplyCycles, settings.floatingMultiplyInitiationInterval,
          settings.arithmeticOutputFifoDepth),
        divideTiming = ArithmeticIpTiming(settings.floatingDivideCycles, settings.floatingDivideInitiationInterval,
          settings.arithmeticOutputFifoDepth),
        fmaTiming = ArithmeticIpTiming(settings.floatingFmaCycles, settings.floatingFmaInitiationInterval,
          settings.arithmeticOutputFifoDepth),
        sqrtTiming = ArithmeticIpTiming(settings.floatingSqrtCycles, settings.floatingSqrtInitiationInterval,
          settings.arithmeticOutputFifoDepth),
        convertTiming = ArithmeticIpTiming(settings.floatingConvertCycles, settings.floatingConvertInitiationInterval,
          settings.arithmeticOutputFifoDepth),
        compareTiming = ArithmeticIpTiming(settings.floatingCompareCycles, settings.floatingCompareInitiationInterval,
          settings.arithmeticOutputFifoDepth)
      )
    ),
    memory = base.memory.copy(
      resetVector = BigInt(settings.memoryBase & 0xffffffffL),
      mainMemoryBase = settings.memoryBase,
      mainMemorySize = settings.memorySize
    ),
    axi = base.axi.copy(dataWidth = settings.xlen)
  )
}

/** 启用 RISC-V M 扩展。 */
class WithMExtensionConfig extends NpcConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(isa = base.isa.copy(M = true))
}

/** 在顶层导出调试 IO。 */
class WithTopDebugConfig extends NpcConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(debug = base.debug.copy(enableTopDebugIo = true))
}

/** 启用提交/派发控制接口，供 FPGA 控制平面使用。 */
class WithDispatchControlConfig extends NpcConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(debug = base.debug.copy(enableDispatchControl = true))
}

/** 导出核心 AXI master，数据位宽随组合后的 XLEN 变化。 */
class WithExternalAxiConfig(idWidth: Int = 4) extends NpcConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig = base.copy(
    axi = base.axi.copy(
      addrWidth = 32,
      dataWidth = base.isa.xlen,
      idWidth = idWidth,
      transactionId = 0,
      useExternalMaster = true
    )
  )
}

/** 强制启用流水线；左侧更高优先级片段仍可覆盖该选择。 */
class WithPipelineConfig extends NpcConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(pipeline = base.pipeline.copy(enablePipeline = true))
}

/** 覆盖 XLEN，并同步更新 AXI 数据位宽。 */
class WithNpcXlenConfig(xlen: Int) extends NpcConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig = base.copy(
    isa = base.isa.copy(xlen = xlen),
    axi = base.axi.copy(dataWidth = xlen)
  )
}

/** 启用 RISC-V F 扩展。 */
class WithFloatingPointConfig extends NpcConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(isa = base.isa.copy(F = true))
}

/** 启用 Zicsr CSR 指令扩展。 */
class WithZicsrConfig extends NpcConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(isa = base.isa.copy(Zicsr = true))
}

/** 禁用 Zicsr；CSR 读改写指令会在译码阶段成为 illegal instruction。 */
class WithoutZicsrConfig extends NpcConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(isa = base.isa.copy(F = false, Zicsr = false))
}

/** 覆盖乘除法单元的完成延迟，用于时序检查构造。 */
class WithMulDivCompletionConfig(cycles: Int) extends NpcConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig = base.copy(
    operators = base.operators.copy(mulDiv = base.operators.mulDiv.copy(completionCycles = cycles))
  )
}
