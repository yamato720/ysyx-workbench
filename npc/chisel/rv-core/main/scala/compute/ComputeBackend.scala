package scpu

// 算子实现策略与算子本身放在一起，而非根配置层；模型和外部稳定适配器路径
// 共用同一请求/响应约定。厂商 RTL 实现由对应平台组件提供。
sealed trait ComputeBackend {
  def name: String
}

object ComputeBackend {
  /** Chisel/Verilator 构建使用的周期精确协议模型。 */
  case object Builtin extends ComputeBackend { val name = "model" }
  case object DPI extends ComputeBackend { val name = "dpi" }
  case object IP extends ComputeBackend { val name = "ip" }
  /** 可综合整数数据通路加严格 RISC-V 浮点邮箱回退。 */
  case object FPGA extends ComputeBackend { val name = "fpga" }
}

/** 为未来 DPI 算子外壳预留的时序约定。 */
case class DpiOperatorPipelineConfig(
  enablePipeline: Boolean = false,
  pipelineStages: Int = 1,
  completionCycles: Int = 1,
) {
  require(pipelineStages >= 1, s"DPI pipelineStages must be positive, got $pipelineStages")
  require(completionCycles >= 1, s"DPI completionCycles must be positive, got $completionCycles")
  require(
    !enablePipeline || pipelineStages <= completionCycles,
    s"DPI pipelineStages ($pipelineStages) cannot exceed completionCycles ($completionCycles)"
  )
}

case class DpiComputeConfig(
  enable: Boolean = false,
  pipeline: DpiOperatorPipelineConfig = DpiOperatorPipelineConfig(),
)

/** 可综合厂商 IP 适配器模块的连接约定。 */
case class IpComputeConfig(
  moduleName: String = "",
  pipelineStages: Int = 1,
  completionCycles: Int = 1,
  useAxisStream: Boolean = true,
  outputFifoDepth: Int = 4,
) {
  require(pipelineStages >= 1, s"IP pipelineStages must be positive, got $pipelineStages")
  require(completionCycles >= 1, s"IP completionCycles must be positive, got $completionCycles")
  require(outputFifoDepth >= 1, s"IP outputFifoDepth must be positive, got $outputFifoDepth")
}

case class ComputeUnitConfig(
  backend: ComputeBackend = ComputeBackend.Builtin,
  dpi: DpiComputeConfig = DpiComputeConfig(),
  ip: IpComputeConfig = IpComputeConfig(),
) {
  require(
    backend != ComputeBackend.DPI || dpi.enable,
    "ComputeBackend.DPI requires DpiComputeConfig(enable = true)"
  )
}
