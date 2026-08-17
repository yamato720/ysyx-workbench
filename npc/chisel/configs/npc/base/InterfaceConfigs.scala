package npc

/** 在顶层导出调试 IO（提交/完成等运行环端口）。 */
class WithTopDebugConfig extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(debug = base.debug.copy(enableTopDebugIo = true))
}

/** 导出逐提交观测引脚。 */
class WithTraceConfig extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(debug = base.debug.copy(enableTopDebugIo = true, enableTrace = true))
}

/** 导出 NEMU SDB 互动引脚。 */
class WithSdbDebugConfig extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(debug = base.debug.copy(enableTopDebugIo = true, enableSdbDebug = true))
}

/** 导出结束时聚合计数引脚。 */
class WithFinalLogConfig extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(debug = base.debug.copy(enableTopDebugIo = true, enableFinalLog = true))
}

/** 启用提交/派发控制接口，供 FPGA 控制平面使用。 */
class WithDispatchControlConfig extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(debug = base.debug.copy(enableDispatchControl = true))
}

/** 导出核心 AXI master；CPU-side Lite 宽度随 XLEN，缓存主存端可单独加宽。 */
class WithExternalAxiConfig(idWidth: Int = 4, externalDataWidth: Int = 0) extends ConfigFragment {
  require(externalDataWidth == 0 ||
    (externalDataWidth >= 32 && (externalDataWidth & (externalDataWidth - 1)) == 0),
    s"external AXI data width must be a power of two and at least 32, got $externalDataWidth")

  override private[npc] def applyTo(base: NpcConfig): NpcConfig = base.copy(
    axi = base.axi.copy(
      addrWidth = 32,
      dataWidth = base.isa.xlen,
      idWidth = idWidth,
      transactionId = 0,
      useExternalMaster = true,
      // 零值表示延后解析的 CPU 宽度默认值。Config fragment 按从右到左组合，
      // 因而 ISA 选择可以合法地位于当前 fragment 之后。
      externalDataWidth = externalDataWidth
    )
  )
}
