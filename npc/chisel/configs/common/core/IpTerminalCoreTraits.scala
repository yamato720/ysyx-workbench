package npc

/** NEMU 功能仿真终端直接包含的计算单元集群。 */
trait NemuSimulationIpTerminalCore extends IpComputeSelection {
  override protected final val mulDivComputeUnit: ComputeUnitConfig =
    ComputeUnitConfig(backend = ComputeBackend.Builtin)
}

/** FPGA 终端直接包含的整数厂商 IP 计算单元集群。 */
trait FpgaIpTerminalCore extends FpgaIpComputeSelection
