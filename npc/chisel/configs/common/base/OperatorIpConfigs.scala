package scpu

/** 回退服务所属的架构算术域。该编码也是 FPGA mailbox ABI 的稳定字段。 */
sealed abstract class ArithmeticRouteDomain(val id: Int, val profileName: String)
object ArithmeticRouteDomain {
  case object Integer extends ArithmeticRouteDomain(0, "integer")
  case object Floating extends ArithmeticRouteDomain(1, "floating")
}

/** 主机回退的可诊断原因。不能把厂商能力缺失伪装成 RISC-V 异常。 */
sealed abstract class OperatorFallbackReason(val id: Int, val profileName: String)
object OperatorFallbackReason {
  case object None extends OperatorFallbackReason(0, "none")
  case object FpoRiscvIncompatible extends OperatorFallbackReason(1, "fpo-riscv-incompatible")
  case object VendorIpUnavailable extends OperatorFallbackReason(2, "vendor-ip-unavailable")
  case object Unselected extends OperatorFallbackReason(3, "unselected")
}

/** 每个 RISC-V M/F 指令在构造期使用的稳定路由标识。 */
sealed abstract class ArithmeticRouteOperation(
  val domain: ArithmeticRouteDomain,
  val profileName: String,
  val isMultiply: Boolean = false,
  val isDivide: Boolean = false,
  val isDirectFloating: Boolean = false
)

object ArithmeticRouteOperation {
  case object Mul extends ArithmeticRouteOperation(ArithmeticRouteDomain.Integer, "m_mul", isMultiply = true)
  case object Mulh extends ArithmeticRouteOperation(ArithmeticRouteDomain.Integer, "m_mulh", isMultiply = true)
  case object Mulhsu extends ArithmeticRouteOperation(ArithmeticRouteDomain.Integer, "m_mulhsu", isMultiply = true)
  case object Mulhu extends ArithmeticRouteOperation(ArithmeticRouteDomain.Integer, "m_mulhu", isMultiply = true)
  case object Mulw extends ArithmeticRouteOperation(ArithmeticRouteDomain.Integer, "m_mulw", isMultiply = true)
  case object Div extends ArithmeticRouteOperation(ArithmeticRouteDomain.Integer, "m_div", isDivide = true)
  case object Divu extends ArithmeticRouteOperation(ArithmeticRouteDomain.Integer, "m_divu", isDivide = true)
  case object Rem extends ArithmeticRouteOperation(ArithmeticRouteDomain.Integer, "m_rem", isDivide = true)
  case object Remu extends ArithmeticRouteOperation(ArithmeticRouteDomain.Integer, "m_remu", isDivide = true)
  case object Divw extends ArithmeticRouteOperation(ArithmeticRouteDomain.Integer, "m_divw", isDivide = true)
  case object Divuw extends ArithmeticRouteOperation(ArithmeticRouteDomain.Integer, "m_divuw", isDivide = true)
  case object Remw extends ArithmeticRouteOperation(ArithmeticRouteDomain.Integer, "m_remw", isDivide = true)
  case object Remuw extends ArithmeticRouteOperation(ArithmeticRouteDomain.Integer, "m_remuw", isDivide = true)

  case object Fadd extends ArithmeticRouteOperation(ArithmeticRouteDomain.Floating, "f_fadd")
  case object Fsub extends ArithmeticRouteOperation(ArithmeticRouteDomain.Floating, "f_fsub")
  case object Fmul extends ArithmeticRouteOperation(ArithmeticRouteDomain.Floating, "f_fmul")
  case object Fdiv extends ArithmeticRouteOperation(ArithmeticRouteDomain.Floating, "f_fdiv")
  case object Fsqrt extends ArithmeticRouteOperation(ArithmeticRouteDomain.Floating, "f_fsqrt")
  case object Fmadd extends ArithmeticRouteOperation(ArithmeticRouteDomain.Floating, "f_fmadd")
  case object Fmsub extends ArithmeticRouteOperation(ArithmeticRouteDomain.Floating, "f_fmsub")
  case object Fnmsub extends ArithmeticRouteOperation(ArithmeticRouteDomain.Floating, "f_fnmsub")
  case object Fnmadd extends ArithmeticRouteOperation(ArithmeticRouteDomain.Floating, "f_fnmadd")
  case object Fsgnj extends ArithmeticRouteOperation(ArithmeticRouteDomain.Floating, "f_fsgnj", isDirectFloating = true)
  case object Fsgnjn extends ArithmeticRouteOperation(ArithmeticRouteDomain.Floating, "f_fsgnjn", isDirectFloating = true)
  case object Fsgnjx extends ArithmeticRouteOperation(ArithmeticRouteDomain.Floating, "f_fsgnjx", isDirectFloating = true)
  case object Fmin extends ArithmeticRouteOperation(ArithmeticRouteDomain.Floating, "f_fmin", isDirectFloating = true)
  case object Fmax extends ArithmeticRouteOperation(ArithmeticRouteDomain.Floating, "f_fmax", isDirectFloating = true)
  case object Feq extends ArithmeticRouteOperation(ArithmeticRouteDomain.Floating, "f_feq", isDirectFloating = true)
  case object Flt extends ArithmeticRouteOperation(ArithmeticRouteDomain.Floating, "f_flt", isDirectFloating = true)
  case object Fle extends ArithmeticRouteOperation(ArithmeticRouteDomain.Floating, "f_fle", isDirectFloating = true)
  case object FcvtW extends ArithmeticRouteOperation(ArithmeticRouteDomain.Floating, "f_fcvt_w")
  case object FcvtWu extends ArithmeticRouteOperation(ArithmeticRouteDomain.Floating, "f_fcvt_wu")
  case object FcvtL extends ArithmeticRouteOperation(ArithmeticRouteDomain.Floating, "f_fcvt_l")
  case object FcvtLu extends ArithmeticRouteOperation(ArithmeticRouteDomain.Floating, "f_fcvt_lu")
  case object FcvtSW extends ArithmeticRouteOperation(ArithmeticRouteDomain.Floating, "f_fcvt_s_w")
  case object FcvtSWu extends ArithmeticRouteOperation(ArithmeticRouteDomain.Floating, "f_fcvt_s_wu")
  case object FcvtSL extends ArithmeticRouteOperation(ArithmeticRouteDomain.Floating, "f_fcvt_s_l")
  case object FcvtSLu extends ArithmeticRouteOperation(ArithmeticRouteDomain.Floating, "f_fcvt_s_lu")
  case object FmvXW extends ArithmeticRouteOperation(ArithmeticRouteDomain.Floating, "f_fmv_x_w", isDirectFloating = true)
  case object Fclass extends ArithmeticRouteOperation(ArithmeticRouteDomain.Floating, "f_fclass", isDirectFloating = true)
  case object FmvWX extends ArithmeticRouteOperation(ArithmeticRouteDomain.Floating, "f_fmv_w_x", isDirectFloating = true)

  val mOperations: Vector[ArithmeticRouteOperation] = Vector(
    Mul, Mulh, Mulhsu, Mulhu, Mulw, Div, Divu, Rem, Remu, Divw, Divuw, Remw, Remuw)
  val fOperations: Vector[ArithmeticRouteOperation] = Vector(
    Fadd, Fsub, Fmul, Fdiv, Fsqrt, Fmadd, Fmsub, Fnmsub, Fnmadd,
    Fsgnj, Fsgnjn, Fsgnjx, Fmin, Fmax, Feq, Flt, Fle,
    FcvtW, FcvtWu, FcvtL, FcvtLu, FcvtSW, FcvtSWu, FcvtSL, FcvtSLu,
    FmvXW, Fclass, FmvWX)
  val all: Vector[ArithmeticRouteOperation] = mOperations ++ fOperations
}

/** 每条算术指令的实现目标。`Model` 仅用于周期精确本地构造。 */
sealed abstract class OperatorRouteTarget(val profileName: String)
object OperatorRouteTarget {
  case object Model extends OperatorRouteTarget("model")
  case object VendorIp extends OperatorRouteTarget("vendor-ip")
  case object DirectLogic extends OperatorRouteTarget("direct-logic")
  case object HostFallback extends OperatorRouteTarget("host-fallback")
  case object Unselected extends OperatorRouteTarget("unselected")
}

/** 一条固定的算子实现合同。模块名、位宽、延迟和 II 必须由 Scala Config 固定。 */
final case class OperatorRoute(
  target: OperatorRouteTarget,
  moduleName: String,
  operandWidth: Int,
  latency: Int,
  initiationInterval: Int,
  fallbackReason: OperatorFallbackReason = OperatorFallbackReason.None
) {
  require(operandWidth == 32 || operandWidth == 64,
    s"Operator route operand width must be RV32 or RV64, got $operandWidth")
  require(latency >= 1, s"Operator route latency must be positive, got $latency")
  require(initiationInterval >= 1, s"Operator route II must be positive, got $initiationInterval")
  require(moduleName.nonEmpty, "Operator route module name must not be empty")
  require(
    (target == OperatorRouteTarget.HostFallback) == (fallbackReason != OperatorFallbackReason.None),
    s"Operator route $moduleName must declare a fallback reason exactly for host fallback"
  )

  def profileValue: String =
    s"${target.profileName}:$moduleName:$operandWidth:$latency:$initiationInterval:${fallbackReason.profileName}"
}

/** M/F 指令到实现合同的完整路由表。 */
final case class OperatorRouteConfig(routes: Map[ArithmeticRouteOperation, OperatorRoute] = Map.empty) {
  import ArithmeticRouteOperation._

  def route(operation: ArithmeticRouteOperation): OperatorRoute = routes.getOrElse(operation,
    OperatorRoute(OperatorRouteTarget.Unselected, "unselected", 32, 1, 1,
      OperatorFallbackReason.None))

  def overlay(overrides: OperatorRouteConfig): OperatorRouteConfig =
    OperatorRouteConfig(routes ++ overrides.routes)

  def fillMissing(defaults: Map[ArithmeticRouteOperation, OperatorRoute]): OperatorRouteConfig =
    OperatorRouteConfig(defaults ++ routes)

  def requiresHostFallback: Boolean = routes.values.exists(_.target == OperatorRouteTarget.HostFallback)

  def validate(isa: ISAConfig): Unit = {
    val enabled = (if (isa.M) mOperations else Vector.empty) ++ (if (isa.F) fOperations else Vector.empty)
    enabled.foreach { operation =>
      val selected = routes.getOrElse(operation,
        throw new IllegalArgumentException(s"启用的算子 ${operation.profileName} 没有路由"))
      require(selected.operandWidth == isa.xlen,
        s"算子 ${operation.profileName} 的路由宽度 ${selected.operandWidth} 与 RV${isa.xlen} 不一致")
      require(selected.target != OperatorRouteTarget.Unselected,
        s"启用的算子 ${operation.profileName} 未选择实现")
      require(operation.domain != ArithmeticRouteDomain.Floating ||
        selected.target != OperatorRouteTarget.DirectLogic || operation.isDirectFloating,
        s"浮点数值算子 ${operation.profileName} 不能走 DirectLogic")
    }
  }

  def profileValues(isa: ISAConfig): Seq[(String, String)] = {
    validate(isa)
    val enabled = (if (isa.M) mOperations else Vector.empty) ++ (if (isa.F) fOperations else Vector.empty)
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

  def modelF(width: Int, floating: FloatingAlu.Config): Map[ArithmeticRouteOperation, OperatorRoute] = {
    def timing(operation: ArithmeticRouteOperation): ArithmeticIpTiming = operation match {
      case Fadd | Fsub => floating.addSubTiming
      case Fmul => floating.multiplyTiming
      case Fdiv => floating.divideTiming
      case Fsqrt => floating.sqrtTiming
      case Fmadd | Fmsub | Fnmsub | Fnmadd => floating.fmaTiming
      case FcvtW | FcvtWu | FcvtL | FcvtLu | FcvtSW | FcvtSWu | FcvtSL | FcvtSLu => floating.convertTiming
      case _ => floating.compareTiming
    }
    fOperations.map(operation => operation -> model(width, timing(operation))).toMap
  }
}

/** 可由 CPU、SoC 外设或专用加速器复用的算子 IP 时序。
  *
  * 此类只描述算子接口的延迟、启动间隔和响应 FIFO 深度；不包含 NPC 的 ISA、
  * 流水线、寄存器文件或总线语义。具体硬件通过各自目录中的 Config 片段消费它。
  */
final case class OperatorIpTimingConfig(
  outputFifoDepth: Int = 4,
  multiplyCycles: Int = 3,
  multiplyInitiationInterval: Int = 1,
  divCycles: Int = 37,
  divInitiationInterval: Int = 1,
  floatingAddSubCycles: Int = 3,
  floatingAddSubInitiationInterval: Int = 1,
  floatingMultiplyCycles: Int = 4,
  floatingMultiplyInitiationInterval: Int = 1,
  floatingDivideCycles: Int = 29,
  floatingDivideInitiationInterval: Int = 1,
  floatingFmaCycles: Int = 4,
  floatingFmaInitiationInterval: Int = 1,
  floatingSqrtCycles: Int = 29,
  floatingSqrtInitiationInterval: Int = 1,
  floatingConvertCycles: Int = 7,
  floatingConvertInitiationInterval: Int = 1,
  floatingCompareCycles: Int = 3,
  floatingCompareInitiationInterval: Int = 1
) {
  require(outputFifoDepth >= 1, s"Operator IP output FIFO depth must be positive, got $outputFifoDepth")
  require(multiplyCycles >= 1, s"Operator IP multiply latency must be positive, got $multiplyCycles")
  require(divCycles >= 1, s"Operator IP divide latency must be positive, got $divCycles")

  private[scpu] def timing(cycles: Int, initiationInterval: Int): ArithmeticIpTiming =
    ArithmeticIpTiming(cycles, initiationInterval, outputFifoDepth)
}

object OperatorIpTimingConfig {
  val Default: OperatorIpTimingConfig = OperatorIpTimingConfig()
}
