package scpu

import chisel3._

/**
  * 平台组装点。
  *
  * 流水线、译码、提交和 ISA 状态只保留一份；仿真与 FPGA 在这里提供各自的算术实现。
  * 返回值均使用核心定义的接口，因此核心源码不需要依赖厂商 IP 或 DPI 类型。
  */
trait NpcCoreComponents {
  def name: String

  def exposesArithmeticAssist(config: NpcConfig): Boolean = false
  def exposesDispatchControl(config: NpcConfig): Boolean = false

  def makeMulDivAlu(width: Int, config: MulDivAlu.Config): MulDivAlu
  def makeFloatingAlu(width: Int, config: FloatingAlu.Config): FloatingAluBase
}

/** 普通 Verilator/NEMU 构建使用的模型和 DPI 算子组件。 */
object SimulationCoreComponents extends NpcCoreComponents {
  override val name: String = "simulation"

  override def makeMulDivAlu(width: Int, config: MulDivAlu.Config): MulDivAlu =
    Module(new MulDivAlu(width, config))

  override def makeFloatingAlu(width: Int, config: FloatingAlu.Config): FloatingAluBase =
    Module(new FloatingAlu(width, config))
}
