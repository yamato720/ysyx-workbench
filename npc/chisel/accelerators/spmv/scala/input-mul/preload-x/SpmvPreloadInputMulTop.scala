package accelerators.spmv.inputmul.preload

import accelerators.spmv.{SpmvInputConfig, SpmvXPortSchedule}
import accelerators.spmv.inputmul.common.SpmvInputMulTop

/** X 全量预加载完成后才启动 A 输入的乘法顶层。 */
final class SpmvPreloadInputMulTop(config: SpmvInputConfig)
  extends SpmvInputMulTop(config, SpmvXPortSchedule.Preload) {
  override def desiredName: String = "SpmvInputTop"
}
