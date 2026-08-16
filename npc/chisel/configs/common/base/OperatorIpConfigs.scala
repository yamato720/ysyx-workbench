package npc

import npc.ip.arithmetic.ArithmeticIpTiming

/** 每个 RISC-V M 指令在构造期使用的稳定路由标识。 */
sealed abstract class ArithmeticRouteOperation(
  val profileName: String,
  val isMultiply: Boolean = false,
  val isDivide: Boolean = false
)

object ArithmeticRouteOperation {
  case object Mul extends ArithmeticRouteOperation("m_mul", isMultiply = true)
  case object Mulh extends ArithmeticRouteOperation("m_mulh", isMultiply = true)
  case object Mulhsu extends ArithmeticRouteOperation("m_mulhsu", isMultiply = true)
  case object Mulhu extends ArithmeticRouteOperation("m_mulhu", isMultiply = true)
  case object Mulw extends ArithmeticRouteOperation("m_mulw", isMultiply = true)
  case object Div extends ArithmeticRouteOperation("m_div", isDivide = true)
  case object Divu extends ArithmeticRouteOperation("m_divu", isDivide = true)
  case object Rem extends ArithmeticRouteOperation("m_rem", isDivide = true)
  case object Remu extends ArithmeticRouteOperation("m_remu", isDivide = true)
  case object Divw extends ArithmeticRouteOperation("m_divw", isDivide = true)
  case object Divuw extends ArithmeticRouteOperation("m_divuw", isDivide = true)
  case object Remw extends ArithmeticRouteOperation("m_remw", isDivide = true)
  case object Remuw extends ArithmeticRouteOperation("m_remuw", isDivide = true)

  val mOperations: Vector[ArithmeticRouteOperation] = Vector(
    Mul, Mulh, Mulhsu, Mulhu, Mulw, Div, Divu, Rem, Remu, Divw, Divuw, Remw, Remuw)
  val all: Vector[ArithmeticRouteOperation] = mOperations
}

/** 每条算术指令的实现目标。`Model` 仅用于周期精确本地构造。 */
sealed abstract class OperatorRouteTarget(val profileName: String)
object OperatorRouteTarget {
  case object Model extends OperatorRouteTarget("model")
  case object VendorIp extends OperatorRouteTarget("vendor-ip")
  case object DirectLogic extends OperatorRouteTarget("direct-logic")
  case object Unselected extends OperatorRouteTarget("unselected")
}

/** 一条固定的算子实现合同。模块名、位宽、延迟和 II 必须由 Scala Config 固定。 */
final case class OperatorRoute(
  target: OperatorRouteTarget,
  moduleName: String,
  operandWidth: Int,
  latency: Int,
  initiationInterval: Int
) {
  require(operandWidth == 32 || operandWidth == 64,
    s"Operator route operand width must be RV32 or RV64, got $operandWidth")
  require(latency >= 1, s"Operator route latency must be positive, got $latency")
  require(initiationInterval >= 1, s"Operator route II must be positive, got $initiationInterval")
  require(moduleName.nonEmpty, "Operator route module name must not be empty")
  // 末尾的 none 保持已发布的 M vendor-IP 文本合同；它不再表示 host fallback 原因。
  def profileValue: String =
    s"${target.profileName}:$moduleName:$operandWidth:$latency:$initiationInterval:none"
}

/** M 指令到实现合同的完整路由表。 */
final case class OperatorRouteConfig(routes: Map[ArithmeticRouteOperation, OperatorRoute] = Map.empty) {
  import ArithmeticRouteOperation._

  def route(operation: ArithmeticRouteOperation): OperatorRoute = routes.getOrElse(operation,
    OperatorRoute(OperatorRouteTarget.Unselected, "unselected", 32, 1, 1))

  def overlay(overrides: OperatorRouteConfig): OperatorRouteConfig =
    OperatorRouteConfig(routes ++ overrides.routes)

  def fillMissing(defaults: Map[ArithmeticRouteOperation, OperatorRoute]): OperatorRouteConfig =
    OperatorRouteConfig(defaults ++ routes)

  def without(operations: Iterable[ArithmeticRouteOperation]): OperatorRouteConfig =
    OperatorRouteConfig(routes -- operations)

  def validate(isa: ISAConfig): Unit = {
    val enabled = if (isa.M) mOperations else Vector.empty
    enabled.foreach { operation =>
      val selected = routes.getOrElse(operation,
        throw new IllegalArgumentException(s"启用的算子 ${operation.profileName} 没有路由"))
      require(selected.operandWidth == isa.xlen,
        s"算子 ${operation.profileName} 的路由宽度 ${selected.operandWidth} 与 RV${isa.xlen} 不一致")
      require(selected.target != OperatorRouteTarget.Unselected,
        s"启用的算子 ${operation.profileName} 未选择实现")
    }
  }

  def profileValues(isa: ISAConfig): Seq[(String, String)] = {
    validate(isa)
    val enabled = if (isa.M) mOperations else Vector.empty
    enabled.map(operation => s"OPERATOR_ROUTE_${operation.profileName.toUpperCase}" -> route(operation).profileValue)
  }
}

object OperatorRouteConfig {
  import ArithmeticRouteOperation._

  private def model(width: Int, timing: ArithmeticIpTiming): OperatorRoute =
    OperatorRoute(OperatorRouteTarget.Model, "cycle-model", width, timing.latency, timing.initiationInterval)

  def modelM(width: Int, mulDiv: MulDivAlu.Config): Map[ArithmeticRouteOperation, OperatorRoute] =
    mOperations.map { operation =>
      val timing = if (operation.isMultiply) mulDiv.multiplyTiming else mulDiv.divideTiming
      operation -> model(width, timing)
    }.toMap

}

/** 单个算子 IP 的接口时序。
  *
  * `latency` 是请求握手到响应可见的拍数，`initiationInterval` 是连续独立请求
  * 之间的最小间隔。两者分开建模，避免把“流水级数”和“吞吐率”混为同一个参数。
  */
final case class OperatorIpTiming(
  latency: Int,
  initiationInterval: Int = 1
) {
  require(latency >= 1, s"Operator IP latency must be positive, got $latency")
  require(initiationInterval >= 1, s"Operator IP II must be positive, got $initiationInterval")
}

/** 可由 CPU、SoC 外设或专用加速器复用的算子 IP 时序。
  *
  * 此类只描述算子接口的延迟、启动间隔和响应 FIFO 深度；不包含 NPC 的 ISA、
  * 流水线、寄存器文件或总线语义。具体硬件通过各自目录中的 Config 片段消费它。
  */
final case class OperatorIpTimingConfig(
  outputFifoDepth: Int = 4,
  multiply: OperatorIpTiming = OperatorIpTiming(latency = 3),
  divide: OperatorIpTiming = OperatorIpTiming(latency = 37)
) {
  require(outputFifoDepth >= 1, s"Operator IP output FIFO depth must be positive, got $outputFifoDepth")

  private[npc] def timing(schedule: OperatorIpTiming): ArithmeticIpTiming =
    ArithmeticIpTiming(schedule.latency, schedule.initiationInterval, outputFifoDepth)
}

object OperatorIpTimingConfig {
  val Default: OperatorIpTimingConfig = OperatorIpTimingConfig()
}
