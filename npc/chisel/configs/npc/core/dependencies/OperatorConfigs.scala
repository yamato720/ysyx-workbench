package scpu

/** 只覆盖一个算术域的计算实现。 */
class WithMulDivComputeConfig(implementation: ComputeUnitConfig) extends ConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig = base.copy(
    operators = base.operators.copy(mulDiv = base.operators.mulDiv.copy(implementation = implementation))
  )
}

/** 只覆盖浮点算术域的计算实现。 */
class WithFloatingComputeConfig(implementation: ComputeUnitConfig) extends ConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig = base.copy(
    operators = base.operators.copy(floating = base.operators.floating.copy(implementation = implementation))
  )
}

/** 对整数和浮点算术域同时应用同一计算实现。 */
class WithComputeConfig(implementation: ComputeUnitConfig) extends ConfigBundle(
  new WithMulDivComputeConfig(implementation) ++
    new WithFloatingComputeConfig(implementation)
)

/** 使用周期精确的 Chisel/Verilator 模型。 */
class WithModelComputeConfig extends ConfigBundle(
  new WithComputeConfig(ComputeUnitConfig(backend = ComputeBackend.Builtin))
)

/** 使用 DPI 算术端点。 */
class WithDpiComputeConfig(dpi: DpiComputeConfig = DpiComputeConfig(enable = true)) extends ConfigBundle(
  new WithComputeConfig(ComputeUnitConfig(backend = ComputeBackend.DPI, dpi = dpi))
)

/** 使用通用厂商 IP 适配器。 */
class WithGenericIpComputeConfig(ip: IpComputeConfig = IpComputeConfig()) extends ConfigBundle(
  new WithArithmeticOutputFifoDepthConfig(ip.outputFifoDepth) ++
    new WithGenericIpComputeFragment(ip)
)

private class WithGenericIpComputeFragment(ip: IpComputeConfig) extends ConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig = {
    require(!base.isa.F,
      "Generic IP arithmetic cannot be used with RISC-V F: Vivado 2022.2 FPO lacks dynamic RISC-V rounding, " +
        "NX reporting, and unsigned float-to-integer conversion. Use WithModelComputeConfig or WithFpgaComputeConfig.")
    new WithComputeConfig(ComputeUnitConfig(backend = ComputeBackend.IP, ip = ip)).applyTo(base)
  }
}

/** 使用 FPGA 整数 IP 与浮点 mailbox 回退端点。 */
class WithFpgaComputeConfig extends ConfigBundle(
  new WithComputeConfig(ComputeUnitConfig(backend = ComputeBackend.FPGA))
)

/** 覆盖已启用扩展的逐操作路由；仅由检查构造或板级 CDE 覆盖使用。 */
class WithOperatorRoutesConfig(routes: OperatorRouteConfig) extends ConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig = base.copy(
    operators = base.operators.copy(routes = base.operators.routes.overlay(routes))
  )
}

/** 同步算术实现与全部请求响应 FIFO 的深度。 */
class WithArithmeticOutputFifoDepthConfig(depth: Int) extends ConfigFragment {
  require(depth >= 1, s"Arithmetic output FIFO depth must be positive, got $depth")

  override private[scpu] def applyTo(base: NpcConfig): NpcConfig = {
    def withFifoDepth(timing: ArithmeticIpTiming): ArithmeticIpTiming = timing.copy(responseFifoDepth = depth)
    def withIpDepth(implementation: ComputeUnitConfig): ComputeUnitConfig = implementation.copy(
      ip = implementation.ip.copy(outputFifoDepth = depth)
    )

    val mulDiv = base.operators.mulDiv
    val floating = base.operators.floating
    base.copy(operators = base.operators.copy(
      mulDiv = mulDiv.copy(
        implementation = withIpDepth(mulDiv.implementation),
        multiplyTiming = withFifoDepth(mulDiv.multiplyTiming)
      ),
      floating = floating.copy(
        implementation = withIpDepth(floating.implementation),
        addSubTiming = withFifoDepth(floating.addSubTiming),
        multiplyTiming = withFifoDepth(floating.multiplyTiming),
        divideTiming = withFifoDepth(floating.divideTiming),
        fmaTiming = withFifoDepth(floating.fmaTiming),
        sqrtTiming = withFifoDepth(floating.sqrtTiming),
        convertTiming = withFifoDepth(floating.convertTiming),
        compareTiming = withFifoDepth(floating.compareTiming)
      )
    ))
  }
}

/** 覆盖整数和浮点算术的完成时序，并保留现有计算实现。 */
class WithArithmeticTimingConfig(timing: OperatorIpTimingConfig) extends ConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig = {
    def withFifoDepth(implementation: ComputeUnitConfig): ComputeUnitConfig = implementation.copy(
      ip = implementation.ip.copy(outputFifoDepth = timing.outputFifoDepth)
    )

    val mulDiv = base.operators.mulDiv
    val floating = base.operators.floating
    base.copy(operators = base.operators.copy(
      mulDiv = mulDiv.copy(
        implementation = withFifoDepth(mulDiv.implementation),
        completionCycles = timing.divCycles,
        multiplyTiming = timing.timing(timing.multiplyCycles, timing.multiplyInitiationInterval),
        dividerInitiationInterval = timing.divInitiationInterval
      ),
      floating = floating.copy(
        implementation = withFifoDepth(floating.implementation),
        addSubTiming = timing.timing(timing.floatingAddSubCycles, timing.floatingAddSubInitiationInterval),
        multiplyTiming = timing.timing(timing.floatingMultiplyCycles, timing.floatingMultiplyInitiationInterval),
        divideTiming = timing.timing(timing.floatingDivideCycles, timing.floatingDivideInitiationInterval),
        fmaTiming = timing.timing(timing.floatingFmaCycles, timing.floatingFmaInitiationInterval),
        sqrtTiming = timing.timing(timing.floatingSqrtCycles, timing.floatingSqrtInitiationInterval),
        convertTiming = timing.timing(timing.floatingConvertCycles, timing.floatingConvertInitiationInterval),
        compareTiming = timing.timing(timing.floatingCompareCycles, timing.floatingCompareInitiationInterval)
      )
    ))
  }
}

/** 全部默认算术时序。 */
class WithDefaultArithmeticTimingConfig extends ConfigBundle(
  new WithArithmeticTimingConfig(OperatorIpTimingConfig.Default)
)

/** 覆盖乘除法单元的完成延迟，用于时序检查构造。 */
class WithMulDivCompletionConfig(cycles: Int) extends ConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig = base.copy(
    operators = base.operators.copy(mulDiv = base.operators.mulDiv.copy(completionCycles = cycles))
  )
}
