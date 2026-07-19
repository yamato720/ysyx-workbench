package scpu

import chisel3._
import chisel3.util._

/** 为算子私有控制码预留的位宽。其取值由计算模块拥有，不属于任何前端/后端 ISA 译码器。
  */
object ArithmeticOperation {
  val width = 5
}

/** 与 IP 兼容的固定时延算术配置。
  *
  * `latency` 是从请求 fire 到响应 fire 的时延。`initiationInterval=1` 表示
  * 填充后每个时钟均可接受请求并产出结果。这是仿真模型与 Vivado 适配器共用的约定。
  */
case class ArithmeticIpTiming(
  latency: Int,
  initiationInterval: Int = 1,
  responseFifoDepth: Int = 4,
) {
  require(latency >= 1, s"Arithmetic IP latency must be positive, got $latency")
  require(initiationInterval >= 1, s"Arithmetic IP initiation interval must be positive, got $initiationInterval")
  require(responseFifoDepth >= 1, s"Arithmetic IP response FIFO depth must be positive, got $responseFifoDepth")
}

/** 由 IP 适配器传递的稳定、厂商无关请求载荷。 */
class ArithmeticRequest(width: Int, tagWidth: Int) extends Bundle {
  val operandA = UInt(width.W)
  val operandB = UInt(width.W)
  // 整数请求在此驱动零。共享数据包保留 rs3，使 FMA 与其他算术操作使用相同的
  // 时延、tag 和背压约定。
  val operandC = UInt(width.W)
  // `operation` 由被选中的可复用算子解释；ISA ALU 在执行边界把译码 aluOp
  // 映射到该字段。
  val operation = UInt(ArithmeticOperation.width.W)
  // 即使整数单元忽略它，也保留在共享接口中；它直接映射到浮点算子 IP 的舍入模式输入。
  val roundingMode = UInt(3.W)
  val pc = UInt(width.W)
  val instruction = UInt(32.W)
  val fcsr = UInt(8.W)
  val tag = UInt(tagWidth.W)
}

/** 结果及由外壳元数据流水线保留的发射 tag。 */
class ArithmeticResponse(width: Int, tagWidth: Int) extends Bundle {
  val result = UInt(width.W)
  val exceptionFlags = UInt(5.W)
  val illegal = Bool()
  val tag = UInt(tagWidth.W)
}

/** 共享的 AXI-stream 形态标量算子端口。 */
class ArithmeticOperatorIO(width: Int, tagWidth: Int) extends Bundle {
  val req = Flipped(Decoupled(new ArithmeticRequest(width, tagWidth)))
  val resp = Decoupled(new ArithmeticResponse(width, tagWidth))
}

/** 固定时延、II 可配置 IP 的行为实现。
  *
  * 它刻意模拟传输时序、输出背压和请求 tag，而非向 CPU 暴露组合数据通路。实际 FPGA
  * 实现通过 [[ComputeBackend.IP]] 选择。
  */
abstract class ArithmeticIpModel(
  width: Int,
  tagWidth: Int,
  timing: ArithmeticIpTiming
) extends Module {
  val io = IO(new ArithmeticOperatorIO(width, tagWidth))

  private val counterWidth = math.max(1, log2Ceil(timing.initiationInterval))
  private val issueCounter = RegInit(0.U(counterWidth.W))
  private val slotValid = RegInit(VecInit(Seq.fill(timing.latency)(false.B)))
  private val slotData = Reg(Vec(timing.latency, new ArithmeticResponse(width, tagWidth)))
  private val responseQueue = Module(new Queue(
    new ArithmeticResponse(width, tagWidth), timing.responseFifoDepth, flow = false, pipe = true))

  responseQueue.io.enq.valid := slotValid(timing.latency - 1)
  responseQueue.io.enq.bits := slotData(timing.latency - 1)
  io.resp <> responseQueue.io.deq

  val advance = !slotValid(timing.latency - 1) || responseQueue.io.enq.ready
  io.req.ready := issueCounter === 0.U && advance
  when(io.req.fire) {
    if (timing.initiationInterval > 1) {
      issueCounter := (timing.initiationInterval - 1).U
    }
  }.elsewhen(issueCounter =/= 0.U) {
    issueCounter := issueCounter - 1.U
  }

  /** 子类建立操作专属数据通路后调用，以连接计算结果。
    *
    * 从子类体调用可避免 HardFloat 模块的 Scala 父类构造顺序问题，同时让所有算术
    * 端点共享传输流水线。
    */
  protected final def driveComputedResult(
    result: UInt,
    exceptionFlags: UInt = 0.U(5.W),
    illegal: Bool = false.B
  ): Unit = {
    // 最后一级是弹性的；响应停顿会保持整个标量模型状态，等价于带下游背压的 AXIS 输出。
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

/** 平台组件提供的外部 AXIS 算术适配器声明。厂商 RTL 位于对应的平台目录。 */
class ExternalArithmeticAdapter(
  moduleName: String,
  width: Int,
  tagWidth: Int,
  latency: Int
) extends BlackBox(Map("WIDTH" -> width, "TAG_WIDTH" -> tagWidth, "LATENCY" -> latency)) {
  require(latency >= 1, s"External arithmetic adapter latency must be positive, got $latency")
  override def desiredName: String = moduleName
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val arithmetic = new ArithmeticOperatorIO(width, tagWidth)
  })
}
