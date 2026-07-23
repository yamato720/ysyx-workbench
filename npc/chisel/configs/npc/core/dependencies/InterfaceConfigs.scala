package scpu

/** 在顶层导出调试 IO。 */
class WithTopDebugConfig extends ConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(debug = base.debug.copy(enableTopDebugIo = true))
}

/** 启用提交/派发控制接口，供 FPGA 控制平面使用。 */
class WithDispatchControlConfig extends ConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(debug = base.debug.copy(enableDispatchControl = true))
}

/** 导出核心 AXI master，数据位宽随组合后的 XLEN 变化。 */
class WithExternalAxiConfig(idWidth: Int = 4) extends ConfigFragment {
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
