package npc

/** 覆盖 XLEN，并同步更新 AXI 数据位宽。 */
class WithXlenConfig(xlen: Int) extends ConfigFragment {
  require(xlen == 32 || xlen == 64, s"NPC XLEN must be 32 or 64, got $xlen")

  override private[npc] def applyTo(base: NpcConfig): NpcConfig = base.copy(
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
  * 它显式清除默认参数中的 M 和 Zicsr，使上层 ISA 预设完整表达自己启用的扩展。
  * XLEN 保持由右侧基础或左侧覆盖片段决定。
  */
class BaseIsaConfig extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig = base.copy(
    isa = base.isa.copy(
      M = false,
      Zicsr = false
    )
  )
}

/** 架构的逐指令详情硬件依赖：提交 PC 与指令字。 */
class WithInstructionLogConfig extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(isa = base.isa.copy(instructionLog = true))
}

/** 启用 RISC-V M 扩展。 */
class WithMExtensionConfig extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(
      isa = base.isa.copy(M = true),
      operators = base.operators.copy(routes =
        base.operators.routes.fillMissing(OperatorRouteConfig.modelM(base.isa.xlen, base.operators.mulDiv)))
    )
}

/** 显式关闭 RISC-V M 扩展。 */
class WithoutMExtensionConfig extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(isa = base.isa.copy(M = false))
}

/** 启用 Zicsr CSR 指令扩展。 */
class WithZicsrConfig extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(isa = base.isa.copy(Zicsr = true))
}

/** 禁用 Zicsr；CSR 指令会在译码阶段成为非法指令。 */
class WithoutZicsrConfig extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(isa = base.isa.copy(Zicsr = false))
}

/** 启用缓存维护指令 FENCE.I。 */
class WithZifenceiConfig extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(isa = base.isa.copy(Zifencei = true))
}

/** 显式禁用 Zifencei；若同时启用缓存，最终参数校验会拒绝该组合。 */
class WithoutZifenceiConfig extends ConfigFragment {
  override private[npc] def applyTo(base: NpcConfig): NpcConfig =
    base.copy(isa = base.isa.copy(Zifencei = false))
}
