package scpu.fpga.zcu102

import scpu.fpga.XilinxArithmeticIpProvider

/** ZCU102 provider 复用公共 Xilinx adapter，板卡 Tcl/XCI 由 ZCU102 构造流程消费。 */
object Zcu102IpComponents extends XilinxArithmeticIpProvider {
  override protected val providerName: String = "xilinx-zcu102"
}
