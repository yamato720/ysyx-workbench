package scpu.fpga

import chisel3._
import scpu._

/** FPGA 构建选择的组件集合；共享流水线只通过中立接口调用这些端点。 */
object FpgaCoreComponents extends NpcCoreComponents {
  override val name: String = "fpga"

  override def exposesArithmeticAssist(config: NpcConfig): Boolean = config.operators.routes.requiresHostFallback
  override def exposesDispatchControl(config: NpcConfig): Boolean = true

  override def makeMulDivAlu(width: Int, config: MulDivAlu.Config, routes: OperatorRouteConfig): MulDivAlu = {
    // 整数 IP 的 Chisel BlackBox 声明是厂商无关的流接口；具体 Xilinx RTL 适配器
    // 和 XCI 配方仅位于 fpga/ip。逐操作路由决定是否真的发往该适配器。
    Module(new MulDivAlu(width, config, routes))
  }

  override def makeFloatingAlu(width: Int, config: FloatingAlu.Config, routes: OperatorRouteConfig): FloatingAluBase =
    Module(new FpgaFloatingAlu(width, config, routes))
}
