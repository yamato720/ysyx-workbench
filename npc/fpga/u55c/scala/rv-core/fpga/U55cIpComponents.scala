package npc.fpga.u55c

import npc.fpga.XilinxArithmeticIpProvider

/** U55C provider 复用公共 Xilinx adapter，板卡 Tcl/XCI 由 U55C 构造流程消费。 */
object U55cIpComponents extends XilinxArithmeticIpProvider {
  override protected val providerName: String = "xilinx-u55c"
}
