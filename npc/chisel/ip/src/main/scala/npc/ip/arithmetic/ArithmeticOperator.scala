package npc.ip.arithmetic

import chisel3._
import chisel3.util._

/** 算子私有控制码的稳定宽度。 */
object ArithmeticOperation {
  val width = 5
}

/** 固定时延算术端点的传输时序。 */
final case class ArithmeticIpTiming(
  latency: Int,
  initiationInterval: Int = 1,
  responseFifoDepth: Int = 4,
) {
  require(latency >= 1, s"算术 IP latency 必须为正数，实际为 $latency")
  require(initiationInterval >= 1, s"算术 IP initiation interval 必须为正数，实际为 $initiationInterval")
  require(responseFifoDepth >= 1, s"算术 IP 响应 FIFO 深度必须为正数，实际为 $responseFifoDepth")
}

/** 厂商无关的算术请求载荷。 */
final class ArithmeticRequest(width: Int, tagWidth: Int) extends Bundle {
  val operandA = UInt(width.W)
  val operandB = UInt(width.W)
  val operandC = UInt(width.W)
  val operation = UInt(ArithmeticOperation.width.W)
  val roundingMode = UInt(3.W)
  val pc = UInt(width.W)
  val instruction = UInt(32.W)
  val fcsr = UInt(8.W)
  val tag = UInt(tagWidth.W)
}

/** 算术结果及原样返回的发射 tag。 */
final class ArithmeticResponse(width: Int, tagWidth: Int) extends Bundle {
  val result = UInt(width.W)
  val exceptionFlags = UInt(5.W)
  val illegal = Bool()
  val tag = UInt(tagWidth.W)
}

/** 带背压的稳定标量算术端口。 */
final class ArithmeticOperatorIO(width: Int, tagWidth: Int) extends Bundle {
  val req = Flipped(Decoupled(new ArithmeticRequest(width, tagWidth)))
  val resp = Decoupled(new ArithmeticResponse(width, tagWidth))
}

/** 所有可由 provider 返回的算术端点基类。 */
abstract class ArithmeticOperatorEndpoint(width: Int, tagWidth: Int) extends Module {
  val io = IO(new ArithmeticOperatorIO(width, tagWidth))
}

/** 固定时延、II、响应背压和 tag 保持的公共行为模型。 */
abstract class ArithmeticIpModel(
  width: Int,
  tagWidth: Int,
  timing: ArithmeticIpTiming
) extends ArithmeticOperatorEndpoint(width, tagWidth) {
  private val counterWidth = math.max(1, log2Ceil(timing.initiationInterval))
  private val issueCounter = RegInit(0.U(counterWidth.W))
  private val slotValid = RegInit(VecInit(Seq.fill(timing.latency)(false.B)))
  private val slotData = Reg(Vec(timing.latency, new ArithmeticResponse(width, tagWidth)))
  private val responseQueue = Module(new Queue(
    new ArithmeticResponse(width, tagWidth), timing.responseFifoDepth, flow = false, pipe = true))

  responseQueue.io.enq.valid := slotValid(timing.latency - 1)
  responseQueue.io.enq.bits := slotData(timing.latency - 1)
  io.resp <> responseQueue.io.deq

  private val advance = !slotValid(timing.latency - 1) || responseQueue.io.enq.ready
  io.req.ready := issueCounter === 0.U && advance
  when(io.req.fire) {
    if (timing.initiationInterval > 1) issueCounter := (timing.initiationInterval - 1).U
  }.elsewhen(issueCounter =/= 0.U) {
    issueCounter := issueCounter - 1.U
  }

  protected final def driveComputedResult(
    result: UInt,
    exceptionFlags: UInt = 0.U(5.W),
    illegal: Bool = false.B
  ): Unit = {
    when(advance) {
      for (index <- timing.latency - 1 to 1 by -1) {
        slotValid(index) := slotValid(index - 1)
        when(slotValid(index - 1)) { slotData(index) := slotData(index - 1) }
      }
      slotValid(0) := io.req.fire
      when(io.req.fire) {
        slotData(0).tag := io.req.bits.tag
        slotData(0).result := result
        slotData(0).exceptionFlags := exceptionFlags
        slotData(0).illegal := illegal
      }
    }
  }
}

/** 外部实现、参考模型和直接逻辑之间的中立选择。 */
sealed trait ArithmeticEndpointImplementation
object ArithmeticEndpointImplementation {
  case object IntegerReference extends ArithmeticEndpointImplementation
  case object SoftFloatDpi extends ArithmeticEndpointImplementation
  case object FloatingDirect extends ArithmeticEndpointImplementation
  case object External extends ArithmeticEndpointImplementation
}

final case class ArithmeticEndpointSpec(
  implementation: ArithmeticEndpointImplementation,
  moduleName: String = "",
  endpointName: String = ""
) {
  require(implementation != ArithmeticEndpointImplementation.External || moduleName.nonEmpty,
    "外部算术端点必须提供模块名")
}

/** 平台只提供端点，不拥有 ISA 译码或操作码映射。 */
trait ArithmeticIpProvider {
  def name: String

  def makeIntegerMultiplier(
    width: Int,
    tagWidth: Int,
    timing: ArithmeticIpTiming,
    spec: ArithmeticEndpointSpec
  ): ArithmeticOperatorEndpoint

  def makeIntegerDivider(
    width: Int,
    tagWidth: Int,
    timing: ArithmeticIpTiming,
    spec: ArithmeticEndpointSpec
  ): ArithmeticOperatorEndpoint

  def makeFloating(
    width: Int,
    tagWidth: Int,
    timing: ArithmeticIpTiming,
    spec: ArithmeticEndpointSpec
  ): ArithmeticOperatorEndpoint
}

private final class ExternalArithmeticAdapter(
  moduleName: String,
  width: Int,
  tagWidth: Int,
  latency: Int
) extends BlackBox(Map("WIDTH" -> width, "TAG_WIDTH" -> tagWidth, "LATENCY" -> latency)) {
  require(latency >= 1, s"外部算术 adapter latency 必须为正数，实际为 $latency")
  override def desiredName: String = moduleName
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val arithmetic = new ArithmeticOperatorIO(width, tagWidth)
  })
}

trait ExternalEndpointWiring { self: ArithmeticOperatorEndpoint =>
  protected final def wireExternal(width: Int, tagWidth: Int, timing: ArithmeticIpTiming, moduleName: String): Unit = {
    val adapter = Module(new ExternalArithmeticAdapter(moduleName, width, tagWidth, timing.latency))
    adapter.io.clock := clock
    adapter.io.reset := reset.asBool
    io <> adapter.io.arithmetic
  }
}

/** 保留旧生成模块名的整数乘法 wrapper。 */
final class IntegerMultiplierOperator(
  width: Int,
  tagWidth: Int,
  timing: ArithmeticIpTiming,
  spec: ArithmeticEndpointSpec
) extends ArithmeticOperatorEndpoint(width, tagWidth) with ExternalEndpointWiring {
  spec.implementation match {
    case ArithmeticEndpointImplementation.IntegerReference => io <> Module(new IntegerMultiplierModel(width, tagWidth, timing)).io
    case ArithmeticEndpointImplementation.External => wireExternal(width, tagWidth, timing, spec.moduleName)
    case other => throw new IllegalArgumentException(s"乘法 wrapper 不支持 $other")
  }
}

/** 保留旧生成模块名的整数除法 wrapper。 */
final class IntegerDividerOperator(
  width: Int,
  tagWidth: Int,
  timing: ArithmeticIpTiming,
  spec: ArithmeticEndpointSpec
) extends ArithmeticOperatorEndpoint(width, tagWidth) with ExternalEndpointWiring {
  spec.implementation match {
    case ArithmeticEndpointImplementation.IntegerReference => io <> Module(new IntegerDividerModel(width, tagWidth, timing)).io
    case ArithmeticEndpointImplementation.External => wireExternal(width, tagWidth, timing, spec.moduleName)
    case other => throw new IllegalArgumentException(s"除法 wrapper 不支持 $other")
  }
}

/** 浮点 wrapper 的公共实现，按 endpointName 保持旧的生成模块名。 */
abstract class FloatingOperatorEndpoint(
  width: Int,
  tagWidth: Int,
  timing: ArithmeticIpTiming,
  spec: ArithmeticEndpointSpec
) extends ArithmeticOperatorEndpoint(width, tagWidth) with ExternalEndpointWiring {
  spec.implementation match {
    case ArithmeticEndpointImplementation.SoftFloatDpi => io <> Module(new FloatingDpiOperator(width, tagWidth, timing)).io
    case ArithmeticEndpointImplementation.FloatingDirect =>
      io <> Module(new FpgaFloatingDirectOperator(width, tagWidth, timing)).io
    case ArithmeticEndpointImplementation.External => wireExternal(width, tagWidth, timing, spec.moduleName)
    case other => throw new IllegalArgumentException(s"浮点 wrapper 不支持 $other")
  }
}

final class FloatingAddSubOperator(w: Int, t: Int, timing: ArithmeticIpTiming, spec: ArithmeticEndpointSpec)
    extends FloatingOperatorEndpoint(w, t, timing, spec)
final class FloatingMultiplierOperator(w: Int, t: Int, timing: ArithmeticIpTiming, spec: ArithmeticEndpointSpec)
    extends FloatingOperatorEndpoint(w, t, timing, spec)
final class FloatingDividerOperator(w: Int, t: Int, timing: ArithmeticIpTiming, spec: ArithmeticEndpointSpec)
    extends FloatingOperatorEndpoint(w, t, timing, spec)
final class FloatingFmaOperator(w: Int, t: Int, timing: ArithmeticIpTiming, spec: ArithmeticEndpointSpec)
    extends FloatingOperatorEndpoint(w, t, timing, spec)
final class FloatingSqrtOperator(w: Int, t: Int, timing: ArithmeticIpTiming, spec: ArithmeticEndpointSpec)
    extends FloatingOperatorEndpoint(w, t, timing, spec)
final class FloatingConvertOperator(w: Int, t: Int, timing: ArithmeticIpTiming, spec: ArithmeticEndpointSpec)
    extends FloatingOperatorEndpoint(w, t, timing, spec)
final class FloatingCompareOperator(w: Int, t: Int, timing: ArithmeticIpTiming, spec: ArithmeticEndpointSpec)
    extends FloatingOperatorEndpoint(w, t, timing, spec)

/** 本地仿真与通用 generated-ip 专家路径使用的公共 provider。 */
object SimulationIpComponents extends ArithmeticIpProvider {
  override val name: String = "simulation-ip"

  private def external(
    width: Int,
    tagWidth: Int,
    timing: ArithmeticIpTiming,
    spec: ArithmeticEndpointSpec
  ): ArithmeticOperatorEndpoint =
    spec.endpointName match {
      case "IntegerMultiplierOperator" => Module(new IntegerMultiplierOperator(width, tagWidth, timing, spec))
      case "IntegerDividerOperator" => Module(new IntegerDividerOperator(width, tagWidth, timing, spec))
      case "FloatingAddSubOperator" => Module(new FloatingAddSubOperator(width, tagWidth, timing, spec))
      case "FloatingMultiplierOperator" => Module(new FloatingMultiplierOperator(width, tagWidth, timing, spec))
      case "FloatingDividerOperator" => Module(new FloatingDividerOperator(width, tagWidth, timing, spec))
      case "FloatingFmaOperator" => Module(new FloatingFmaOperator(width, tagWidth, timing, spec))
      case "FloatingSqrtOperator" => Module(new FloatingSqrtOperator(width, tagWidth, timing, spec))
      case "FloatingConvertOperator" => Module(new FloatingConvertOperator(width, tagWidth, timing, spec))
      case "FloatingCompareOperator" => Module(new FloatingCompareOperator(width, tagWidth, timing, spec))
      case other => throw new IllegalArgumentException(s"未知算术 wrapper 名称：$other")
    }

  private def floating(
    width: Int,
    tagWidth: Int,
    timing: ArithmeticIpTiming,
    spec: ArithmeticEndpointSpec
  ): ArithmeticOperatorEndpoint = spec.endpointName match {
    case "FloatingAddSubOperator" => Module(new FloatingAddSubOperator(width, tagWidth, timing, spec))
    case "FloatingMultiplierOperator" => Module(new FloatingMultiplierOperator(width, tagWidth, timing, spec))
    case "FloatingDividerOperator" => Module(new FloatingDividerOperator(width, tagWidth, timing, spec))
    case "FloatingFmaOperator" => Module(new FloatingFmaOperator(width, tagWidth, timing, spec))
    case "FloatingSqrtOperator" => Module(new FloatingSqrtOperator(width, tagWidth, timing, spec))
    case "FloatingConvertOperator" => Module(new FloatingConvertOperator(width, tagWidth, timing, spec))
    case "FloatingCompareOperator" => Module(new FloatingCompareOperator(width, tagWidth, timing, spec))
    case other => throw new IllegalArgumentException(s"未知浮点 wrapper 名称：$other")
  }

  override def makeIntegerMultiplier(
    width: Int,
    tagWidth: Int,
    timing: ArithmeticIpTiming,
    spec: ArithmeticEndpointSpec
  ): ArithmeticOperatorEndpoint = spec.implementation match {
    case ArithmeticEndpointImplementation.IntegerReference => Module(new IntegerMultiplierOperator(width, tagWidth, timing, spec))
    case ArithmeticEndpointImplementation.External => external(width, tagWidth, timing, spec.copy(endpointName = "IntegerMultiplierOperator"))
    case other => throw new IllegalArgumentException(s"乘法端点不支持 $other")
  }

  override def makeIntegerDivider(
    width: Int,
    tagWidth: Int,
    timing: ArithmeticIpTiming,
    spec: ArithmeticEndpointSpec
  ): ArithmeticOperatorEndpoint = spec.implementation match {
    case ArithmeticEndpointImplementation.IntegerReference => Module(new IntegerDividerOperator(width, tagWidth, timing, spec))
    case ArithmeticEndpointImplementation.External => external(width, tagWidth, timing, spec.copy(endpointName = "IntegerDividerOperator"))
    case other => throw new IllegalArgumentException(s"除法端点不支持 $other")
  }

  override def makeFloating(
    width: Int,
    tagWidth: Int,
    timing: ArithmeticIpTiming,
    spec: ArithmeticEndpointSpec
  ): ArithmeticOperatorEndpoint = spec.implementation match {
    case ArithmeticEndpointImplementation.SoftFloatDpi => floating(width, tagWidth, timing, spec)
    case ArithmeticEndpointImplementation.FloatingDirect => floating(width, tagWidth, timing, spec)
    case ArithmeticEndpointImplementation.External => external(width, tagWidth, timing, spec)
    case other => throw new IllegalArgumentException(s"浮点端点不支持 $other")
  }
}
