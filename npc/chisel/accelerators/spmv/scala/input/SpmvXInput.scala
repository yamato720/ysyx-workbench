package accelerators.spmv.input

import accelerators.common.IndependentAxiReadPorts
import accelerators.spmv.SpmvInputConfig
import accelerators.spmv.reader.{SpmvReaderBeat, SpmvReaderRequest}
import accelerators.spmv.reader.SpmvXReader

/** X 输入封装；一个实例可以并行承载多路独立 HBM reader。 */
final class SpmvXInput(config: SpmvInputConfig, hbmCount: Int)
  extends IndependentAxiReadPorts[SpmvReaderRequest, SpmvReaderBeat](
    hbmCount,
    () => new SpmvReaderRequest(config.axiAddrWidth),
    () => new SpmvReaderBeat(config.axiDataWidth),
    config.axiAddrWidth,
    config.axiDataWidth,
    config.axiIdWidth,
    () => new SpmvXReader(
      config.axiAddrWidth,
      config.axiDataWidth,
      config.axiIdWidth,
      config.maxOutstandingBursts
    )
  )
