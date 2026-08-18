package accelerators.spmv.inputmul.pingpong

import accelerators.spmv.{SpmvInputConfig, SpmvXPortSchedule}
import accelerators.spmv.inputmul.common.SpmvInputMulTop

/** X 写入与 A 读取重叠、按偶奇 lane 交错发射的乘法顶层。 */
final class SpmvAxPingPongInputMulTop(config: SpmvInputConfig)
  extends SpmvInputMulTop(config, SpmvXPortSchedule.PingPong) {
  override def desiredName: String = "SpmvInputTop"
}
