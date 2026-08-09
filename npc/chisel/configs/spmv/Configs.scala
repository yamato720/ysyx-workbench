package npc.spmv

import org.chipsalliance.cde.config.{Config => CDEConfig}
import _root_.npc.{
  LocalSpmvPerformanceMonitorTerminal,
  LocalSpmvSimulationTerminal,
  SpmvCsr5MulConfig,
  WithSpmvCsr5MulConfig
}

/** 单 HBM、八路 FP32 乘法的 CSR5 本地 Verilator 仿真。 */
class SpmvOneHbmCsr5MulSimulationConfig extends CDEConfig(
  new WithSpmvCsr5MulConfig(SpmvCsr5MulConfig.OneHbmFp32X8192Paired)
) with LocalSpmvSimulationTerminal

/** 单 HBM、四份宽 X cache 与八路 FP32 乘法的 CSR5 对照仿真。 */
class SpmvOneHbmCsr5MulCachedXSimulationConfig extends CDEConfig(
  new WithSpmvCsr5MulConfig(SpmvCsr5MulConfig.OneHbmFp32X8192Cached)
) with LocalSpmvSimulationTerminal

/** paired-X CSR5 仿真的乘加流水线性能监测 Config。 */
class SpmvOneHbmCsr5MulPerformanceMonitorSimulationConfig extends CDEConfig(
  new WithSpmvCsr5MulConfig(SpmvCsr5MulConfig.OneHbmFp32X8192Paired)
) with LocalSpmvPerformanceMonitorTerminal
