package accelerators.spmv.inputmul.common

import accelerators.common.IndependentAxiReadPorts
import accelerators.spmv.SpmvInputConfig
import accelerators.spmv.reader.{SpmvCtrlReader, SpmvReaderBeat, SpmvReaderRequest}

/** 控制面输入封装。
  *
  * 当前只展开只读 HBM，把 map 等侧带数据广播给全部消费端。接口形状与 A/X
  * 相同，后续 JPCG 可以把同一封装换成读写端口，而不必再占一条专用 HBM。
  */
final class SpmvCtrlInput(config: SpmvInputConfig, hbmCount: Int)
  extends IndependentAxiReadPorts[SpmvReaderRequest, SpmvReaderBeat](
    hbmCount,
    () => new SpmvReaderRequest(config.axiAddrWidth),
    () => new SpmvReaderBeat(config.axiDataWidth),
    config.axiAddrWidth,
    config.axiDataWidth,
    config.axiIdWidth,
    () => new SpmvCtrlReader(
      config.axiAddrWidth,
      config.axiDataWidth,
      config.axiIdWidth,
      config.maxOutstandingBursts
    )
  )
