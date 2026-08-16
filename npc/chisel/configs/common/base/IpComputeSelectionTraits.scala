package npc

/** 与运行终端无关的计算单元 IP 合同。
  *
  * 它只描述算子时序和计算后端，不包含 Make scope、target 或运行宿主。根部的
  * `NemuSimulationIpTerminal` 与 `FpgaIpTerminal` 在此合同之上表达各自的终端语义。
  */
trait IpComputeSelection {
  /** 算子 IP 的端到端时序与响应 FIFO 属性。 */
  def operatorTiming: OperatorIpTimingConfig

  /** M 扩展计算单元采用的后端。 */
  protected def mulDivComputeUnit: ComputeUnitConfig

  /** 将本配置的计算单元与时序属性写入 NPC 配置。 */
  final def computeUnitConfig: ConfigFragment =
    new WithArithmeticTimingConfig(operatorTiming) ++
      new WithMulDivComputeConfig(mulDivComputeUnit)
}

/** FPGA 整数厂商 IP 的非终端计算单元选择。 */
trait FpgaIpComputeSelection extends IpComputeSelection {
  override protected final val mulDivComputeUnit: ComputeUnitConfig =
    ComputeUnitConfig(backend = ComputeBackend.FPGA)
}
