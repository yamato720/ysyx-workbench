package scpu

/** NEMU host 的固定后端。硬件终端直接选择其中之一，不由 Make 推断。 */
sealed abstract class NemuBackend(val id: String, val kconfigSymbol: String)

object NemuBackend {
  case object LocalVerilator extends NemuBackend("local", "CONFIG_FPGA_BACKEND_NONE")
  case object U55c extends NemuBackend("u55c", "CONFIG_FPGA_BACKEND_U55C")
  case object Zcu102 extends NemuBackend("zcu102", "CONFIG_FPGA_BACKEND_ZCU102")
}
