package scpu

/** 覆盖 XLEN，并同步更新 AXI 数据位宽。 */
class WithXlenConfig(xlen: Int) extends ConfigFragment {
  require(xlen == 32 || xlen == 64, s"NPC XLEN must be 32 or 64, got $xlen")

  override private[scpu] def applyTo(base: NpcConfig): NpcConfig = base.copy(
    isa = base.isa.copy(xlen = xlen),
    axi = base.axi.copy(dataWidth = xlen),
    operators = base.operators.copy(routes = OperatorRouteConfig(
      base.operators.routes.routes.map { case (operation, route) =>
        operation -> route.copy(operandWidth = xlen)
      }
    ))
  )
}

/** 只保留 RISC-V I 的 NPC ISA 起点。
  *
  * 它显式清除默认参数中的 M、F 和 Zicsr，使上层 ISA 预设完整表达自己启用的扩展。
  * XLEN 保持由右侧基础或左侧覆盖片段决定。
  */
class BaseIsaConfig extends ConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig = base.copy(
    isa = base.isa.copy(M = false, F = false, Zicsr = false)
  )
}

/** 启用 RISC-V M 扩展。 */
class WithMExtensionConfig extends ConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(
      isa = base.isa.copy(M = true),
      operators = base.operators.copy(routes = base.operators.routes.fillMissing(
        OperatorRouteConfig.modelM(base.isa.xlen, base.operators.mulDiv)))
    )
}

/** 显式关闭 RISC-V M 扩展。 */
class WithoutMExtensionConfig extends ConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(isa = base.isa.copy(M = false))
}

/** 启用 RISC-V F 扩展。 */
class WithFloatingPointConfig extends ConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig = {
    require(base.isa.Zicsr, "RISC-V F requires Zicsr for FCSR and FS state")
    require(base.operators.mulDiv.implementation.backend != ComputeBackend.IP,
      "Generic IP arithmetic cannot be used with RISC-V F: Vivado 2022.2 FPO lacks dynamic RISC-V rounding, " +
        "NX reporting, and unsigned float-to-integer conversion. Use WithModelComputeConfig or WithFpgaComputeConfig.")
    base.copy(
      isa = base.isa.copy(F = true),
      operators = base.operators.copy(routes = base.operators.routes.fillMissing(
        OperatorRouteConfig.modelF(base.isa.xlen, base.operators.floating)))
    )
  }
}

/** 显式关闭 RISC-V F 扩展。 */
class WithoutFloatingPointConfig extends ConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(isa = base.isa.copy(F = false))
}

/** 启用 Zicsr CSR 指令扩展。 */
class WithZicsrConfig extends ConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(isa = base.isa.copy(Zicsr = true))
}

/** 禁用 Zicsr 与依赖 FCSR 的 F 扩展；CSR 指令会在译码阶段成为非法指令。 */
class WithoutZicsrConfig extends ConfigFragment {
  override private[scpu] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(isa = base.isa.copy(F = false, Zicsr = false))
}
