package npc

import npc.ip.arithmetic.{ArithmeticIpProvider, SimulationIpComponents}

/**
  * 平台组装点。
  *
  * 流水线、译码、提交和 ISA 状态只保留一份；仿真与 FPGA 在这里提供各自的算术实现。
  * 返回值均使用核心定义的接口，因此核心源码不需要依赖厂商 IP 或 DPI 类型。
  */
trait NpcCoreComponents {
  def name: String
  def arithmeticIp: ArithmeticIpProvider

  def exposesDispatchControl(config: NpcConfig): Boolean = false
  def supportsUram: Boolean = false
}

/** 普通 Verilator/NEMU 构建使用的模型和 DPI 算子组件。 */
object SimulationCoreComponents extends NpcCoreComponents {
  override val name: String = "simulation"
  override val arithmeticIp: ArithmeticIpProvider = SimulationIpComponents
}

/** Components backed by an externally generated arithmetic implementation.
  *
  * The core only needs the provider contract.  FPGA integration owns the
  * physical provider and adapts its board attachment through this factory.
  */
object NpcCoreComponents {
  def externalArithmetic(name: String, arithmeticIp: ArithmeticIpProvider): NpcCoreComponents = {
    require(name.nonEmpty, "external arithmetic component name must not be empty")
    new ExternalArithmeticCoreComponents(name, arithmeticIp)
  }
}

private final class ExternalArithmeticCoreComponents(
  override val name: String,
  override val arithmeticIp: ArithmeticIpProvider
) extends NpcCoreComponents {
  override def exposesDispatchControl(config: NpcConfig): Boolean = true
  override def supportsUram: Boolean = true
}
