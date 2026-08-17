package npc

/** 与运行终端无关的计算单元后端合同。
  *
  * 它只选择 M 扩展的实现通道，不携带乘除法时序。时序由 Config 片段或
  * FPGA attachment 写入。
  */
trait IpComputeSelection {
  /** M 扩展计算单元采用的后端。 */
  protected def mulDivComputeUnit: ComputeUnitConfig

  /** 将本配置的计算后端写入 NPC 配置，不覆盖已有时序。 */
  final def computeUnitConfig: ConfigFragment =
    new WithMulDivComputeConfig(mulDivComputeUnit)
}

/** 本地功能模型乘除法后端。 */
object BuiltinCompute extends IpComputeSelection {
  override protected val mulDivComputeUnit: ComputeUnitConfig =
    ComputeUnitConfig(backend = ComputeBackend.Builtin)
}

/** FPGA 整数厂商 IP 的计算单元选择。 */
trait FpgaIpComputeSelection extends IpComputeSelection {
  override protected final val mulDivComputeUnit: ComputeUnitConfig =
    ComputeUnitConfig(backend = ComputeBackend.FPGA)
}

object FpgaCompute extends FpgaIpComputeSelection
