package fpga

import npc.ip.arithmetic._

/** 公共 Xilinx provider：只绑定外部算术端点，时序和模块名仍来自 CDE 路由。 */
trait XilinxArithmeticIpProvider extends ArithmeticIpProvider {
  protected def providerName: String
  override final def name: String = providerName

  override final def makeIntegerMultiplier(width: Int, tagWidth: Int, timing: ArithmeticIpTiming,
    spec: ArithmeticEndpointSpec): ArithmeticOperatorEndpoint =
    SimulationIpComponents.makeIntegerMultiplier(width, tagWidth, timing, spec)

  override final def makeIntegerDivider(width: Int, tagWidth: Int, timing: ArithmeticIpTiming,
    spec: ArithmeticEndpointSpec): ArithmeticOperatorEndpoint =
    SimulationIpComponents.makeIntegerDivider(width, tagWidth, timing, spec)

  override final def makeFloating(width: Int, tagWidth: Int, timing: ArithmeticIpTiming,
    spec: ArithmeticEndpointSpec): ArithmeticOperatorEndpoint =
    SimulationIpComponents.makeFloating(width, tagWidth, timing, spec)
}

/** 未绑定物理板卡的公共 Xilinx 算术实现。 */
object CommonXilinxArithmeticIpComponents extends XilinxArithmeticIpProvider {
  override protected val providerName: String = "xilinx-common"
}
