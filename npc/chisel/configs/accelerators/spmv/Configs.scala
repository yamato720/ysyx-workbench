package accelerators.spmv

import org.chipsalliance.cde.config.{Config => CDEConfig}

/** 驱动 Cuper A/X 正式输入并生成流水报告的本地 Verilator 构造。 */
class SpmvInputSimulationConfig extends CDEConfig(
  new WithSpmvInputConfig(SpmvInputConfig.Cuper16Hbm)
) with LocalSpmvInputTerminal

/** 端口安全的 X/A pingpong 提前发射构造。 */
class SpmvInputPingPongSimulationConfig extends CDEConfig(
  new WithSpmvInputConfig(SpmvInputConfig.Cuper16HbmPingPong)
) with LocalSpmvInputTerminal

/** Cuperflow 16-PC、每 PC 8-lane 连续 X 装填的本地 Verilator 构造。 */
class SpmvCuperflowSimulationConfig extends CDEConfig(
  new WithSpmvCuperflowConfig(SpmvCuperflowConfig.Simulation)
) with LocalSpmvCuperflowTerminal

/** 一路 HBM 的 Cuperflow 边界构造，验证单 PC 的 Config -> RTL -> host 闭环。 */
class SpmvCuperflow1PcSimulationConfig extends CDEConfig(
  new WithSpmvCuperflowPcCount(1) ++
    new WithSpmvCuperflowConfig(SpmvCuperflowConfig.Simulation)
) with LocalSpmvCuperflowTerminal

/** 八路 HBM 的 Cuperflow 中间几何构造，保留历史 0..7 channel 顺序。 */
class SpmvCuperflow8PcSimulationConfig extends CDEConfig(
  new WithSpmvCuperflowPcCount(8) ++
    new WithSpmvCuperflowConfig(SpmvCuperflowConfig.Simulation)
) with LocalSpmvCuperflowTerminal

/**
  * Cuperflow 1..16 PC 闭环回归专用终端。
  *
  * 该 Config 只用于本地回归：每次 `rebuild` 从
  * `SPMV_CUPERFLOW_TEST_PC_COUNT` 读取一个 PC 数并重新冻结同一个 construction。
  * 未设置时保持 16 PC，避免普通构造行为发生变化；环境变量只影响这个显式测试入口，
  * 不会覆盖已保存 construction 的 profile。
  */
class SpmvCuperflowPcCountRegressionConfig extends CDEConfig(
  new WithSpmvCuperflowPcCount(SpmvCuperflowPcCountRegressionConfig.pcCount) ++
    new WithSpmvCuperflowConfig(SpmvCuperflowConfig.Simulation)
) with LocalSpmvCuperflowTerminal

private[spmv] object SpmvCuperflowPcCountRegressionConfig {
  private val EnvironmentName = "SPMV_CUPERFLOW_TEST_PC_COUNT"

  /** 只接受十进制整数，避免空值、截断或隐式溢出改变硬件形状。 */
  def pcCount: Int = {
    val raw = sys.env.get(EnvironmentName).map(_.trim).filter(_.nonEmpty).getOrElse("16")
    val value = try raw.toInt catch {
      case _: NumberFormatException =>
        throw new IllegalArgumentException(
          s"$EnvironmentName 必须是 1..16 的十进制整数，实际为 '$raw'")
    }
    require(value >= 1 && value <= 16,
      s"$EnvironmentName 必须位于 1..16，实际为 $value")
    value
  }
}

/** 同一 Cuperflow 数据通路，local-X 打开第二套 ping/pong 窗口。 */
class SpmvCuperflowPingPongSimulationConfig extends CDEConfig(
  new WithSpmvCuperflowLocalXPingPongConfig ++
    new WithSpmvCuperflowConfig(SpmvCuperflowConfig.Simulation)
) with LocalSpmvCuperflowTerminal
