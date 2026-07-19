package scpu

import chisel3._
import chisel3.util._

/** Scala 端点及其 SoftFloat DPI 实现共享的 binary32 算子私有控制码。
  * ISA 执行外壳会映射到这些取值。
  */
object FloatingOperation extends ChiselEnum {
  val add, subtract, multiply, divide, sqrt, multiplyAdd, multiplySubtract,
    negateMultiplySubtract, negateMultiplyAdd, signInject, signInjectNegate,
    signInjectXor, minimum, maximum, equal, lessThan, lessOrEqual,
    convertWord, convertWordUnsigned, convertLong, convertLongUnsigned,
    convertSingleWord, convertSingleWordUnsigned, convertSingleLong,
    convertSingleLongUnsigned, moveToInteger, classify, moveFromInteger = Value
}

/** 封装在与厂商端点相同时序模型中的 Berkeley SoftFloat 计算。
  * DPI 外壳本身是组合逻辑，只在请求握手时运行；tag、时延、II 与输出背压均在此处处理。
  */
private class FloatingDpiOperator(width: Int, tagWidth: Int, timing: ArithmeticIpTiming)
    extends ArithmeticIpModel(width, tagWidth, timing) {
  private val dpi = Module(new NpcFloatingPointDpi)
  private def extend(value: UInt): UInt =
    if (width == 64) value else Cat(0.U((64 - width).W), value)

  dpi.io.valid := io.req.fire
  dpi.io.operandA := extend(io.req.bits.operandA)
  dpi.io.operandB := extend(io.req.bits.operandB)
  dpi.io.operandC := extend(io.req.bits.operandC)
  dpi.io.operation := io.req.bits.operation
  dpi.io.roundingMode := io.req.bits.roundingMode
  dpi.io.xlen := width.U(7.W)

  val result = if (width == 64) dpi.io.result else dpi.io.result(31, 0)
  driveComputedResult(result, dpi.io.exceptionFlags)
}

/** 围绕一个标量 binary32 实现的可复用时序端点。
  * 它刻意独立于译码和流水线退休；调用方以自己的 ALU 操作策略选择它。
  */
abstract class FloatingOperatorEndpoint(
  width: Int,
  implementation: ComputeUnitConfig,
  tagWidth: Int,
  timing: ArithmeticIpTiming,
  adapterName: String
) extends Module {
  val io = IO(new ArithmeticOperatorIO(width, tagWidth))

  implementation.backend match {
    case ComputeBackend.IP =>
      val impl = Module(new ExternalArithmeticAdapter(adapterName, width, tagWidth, timing.latency))
      impl.io.clock := clock
      impl.io.reset := reset.asBool
      io <> impl.io.arithmetic
    case _ =>
      val impl = Module(new FloatingDpiOperator(width, tagWidth, timing))
      io <> impl.io
  }
}

class FloatingAddSubOperator(width: Int, implementation: ComputeUnitConfig, tagWidth: Int,
  timing: ArithmeticIpTiming, adapterName: String)
    extends FloatingOperatorEndpoint(width, implementation, tagWidth, timing, adapterName)
class FloatingMultiplierOperator(width: Int, implementation: ComputeUnitConfig, tagWidth: Int,
  timing: ArithmeticIpTiming, adapterName: String)
    extends FloatingOperatorEndpoint(width, implementation, tagWidth, timing, adapterName)
class FloatingDividerOperator(width: Int, implementation: ComputeUnitConfig, tagWidth: Int,
  timing: ArithmeticIpTiming, adapterName: String)
    extends FloatingOperatorEndpoint(width, implementation, tagWidth, timing, adapterName)
class FloatingFmaOperator(width: Int, implementation: ComputeUnitConfig, tagWidth: Int,
  timing: ArithmeticIpTiming, adapterName: String)
    extends FloatingOperatorEndpoint(width, implementation, tagWidth, timing, adapterName)
class FloatingSqrtOperator(width: Int, implementation: ComputeUnitConfig, tagWidth: Int,
  timing: ArithmeticIpTiming, adapterName: String)
    extends FloatingOperatorEndpoint(width, implementation, tagWidth, timing, adapterName)
class FloatingConvertOperator(width: Int, implementation: ComputeUnitConfig, tagWidth: Int,
  timing: ArithmeticIpTiming, adapterName: String)
    extends FloatingOperatorEndpoint(width, implementation, tagWidth, timing, adapterName)
class FloatingCompareOperator(width: Int, implementation: ComputeUnitConfig, tagWidth: Int,
  timing: ArithmeticIpTiming, adapterName: String)
    extends FloatingOperatorEndpoint(width, implementation, tagWidth, timing, adapterName)
