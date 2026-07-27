package npc

/** NEMU/Verilator 使用的周期精确功能模型终端。 */
trait NemuSimulationIpTerminal extends NemuSimulationIpTerminalCore with IpConstruction {
  override protected final def configuredIp: IpComputeSelection = this
  override def operatorTiming: OperatorIpTimingConfig = OperatorIpTimingConfig.Default
}

object NemuSimulationIpTerminal extends NemuSimulationIpTerminal {
  /** 用 FPGA 或其他 IP 配置的时序执行相同的本地功能模型。 */
  def from(ipSelection: IpComputeSelection): NemuSimulationIpTerminal = apply(ipSelection.operatorTiming)

  def apply(timing: OperatorIpTimingConfig): NemuSimulationIpTerminal =
    new NemuSimulationIpTerminal {
      override val operatorTiming: OperatorIpTimingConfig = timing
    }
}

/** FPGA 使用的整数厂商 IP 终端。浮点后端由 FPGA ISA 策略保持禁用。 */
trait FpgaIpTerminal extends FpgaIpTerminalCore with IpConstruction {
  override protected final def configuredIp: IpComputeSelection = this
  override def operatorTiming: OperatorIpTimingConfig = OperatorIpTimingConfig.Default
}

/** 未选择具体板卡 attachment 前的 FPGA 默认时序。 */
object FpgaIpTerminal extends FpgaIpTerminal {
  /** 以指定 IP 时序挂载 FPGA 计算单元；具体 provider 仍由板卡 attachment 覆盖。 */
  def from(ipSelection: IpComputeSelection): FpgaIpTerminal = apply(ipSelection.operatorTiming)

  def apply(timing: OperatorIpTimingConfig): FpgaIpTerminal =
    new FpgaIpTerminal {
      override val operatorTiming: OperatorIpTimingConfig = timing
    }
}
