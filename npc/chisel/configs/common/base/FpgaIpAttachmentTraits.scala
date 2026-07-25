package npc

import org.chipsalliance.cde.config.{Config => CDEConfig, Field}
import npc.ip.arithmetic.ArithmeticIpProvider

/** FPGA 构造挂接给 NPC 或 ysyxSoC 的算术 IP 合同。
  *
  * attachment 是不可变的构造输入：它同时选择 Chisel 外部端点、M 指令路由和
  * 端到端时序。NPC 与 SoC 只消费同一份 attachment，不得按板卡名称再做第二次
  * provider 选择。
  */
trait FpgaIpAttachment extends FpgaIpComputeSelection {
  def name: String
  def arithmeticIp: ArithmeticIpProvider

  /** 把 IP 合同写入已完成的核心配置。 */
  def attachTo(core: NpcConfig): NpcConfig

  /** 写入 FPGA construction profile 的 IP 生成字段。 */
  def manifestValues: Seq[(String, String)]
}

/** Xilinx 整数乘除 IP 的可挂接合同。
  *
  * 乘法器 adapter 的延迟与 `mult_gen` 的 `PipeStages` 完全一致；除法器 adapter
  * 有固定后处理拍数，因此只把除法器内部拍数和 adapter 拍数单独记录并在构造期校验。
  */
final case class XilinxIntegerIpAttachment(
  name: String,
  arithmeticIp: ArithmeticIpProvider,
  timing: OperatorIpTimingConfig = OperatorIpTimingConfig.Default,
  dividerIpCycles: Int = 34,
  dividerAdapterCycles: Int = 3
) extends FpgaIpAttachment {
  require(name.nonEmpty, "FPGA IP attachment name must not be empty")
  require(dividerIpCycles >= 1, s"Xilinx divider IP latency must be positive, got $dividerIpCycles")
  require(dividerAdapterCycles >= 0,
    s"Xilinx divider adapter latency must be nonnegative, got $dividerAdapterCycles")
  require(timing.multiply.initiationInterval == 1,
    s"Xilinx multiplier requires II=1, got ${timing.multiply.initiationInterval}")
  require(timing.divide.initiationInterval == 1,
    s"Xilinx divider requires II=1, got ${timing.divide.initiationInterval}")
  require(timing.divide.latency == dividerIpCycles + dividerAdapterCycles,
    s"Xilinx divider latency ${timing.divide.latency} must equal IP $dividerIpCycles + " +
      s"adapter $dividerAdapterCycles")

  private def routesFor(width: Int): OperatorRouteConfig = {
    val multiply = timing.timing(timing.multiply)
    val divide = timing.timing(timing.divide)
    val integer = ArithmeticRouteOperation.mOperations.map { operation =>
      val selectedTiming = if (operation.isMultiply) multiply else divide
      val module = if (operation.isMultiply) "npc_int_multiplier_adapter" else "npc_int_divider_adapter"
      operation -> OperatorRoute(
        OperatorRouteTarget.VendorIp,
        module,
        width,
        selectedTiming.latency,
        selectedTiming.initiationInterval
      )
    }
    OperatorRouteConfig(integer.toMap)
  }

  override def operatorTiming: OperatorIpTimingConfig = timing

  override def attachTo(core: NpcConfig): NpcConfig = {
    require(core.isa.M, s"FPGA IP attachment $name requires the RISC-V M extension")
    val configured = computeUnitConfig.applyTo(core)
    val mulDiv = configured.operators.mulDiv
    val implementation = mulDiv.implementation.copy(
      ip = mulDiv.implementation.ip.copy(outputFifoDepth = timing.outputFifoDepth)
    )
    configured.copy(operators = configured.operators.copy(
      mulDiv = mulDiv.copy(
        implementation = implementation,
        completionCycles = timing.divide.latency,
        multiplyTiming = timing.timing(timing.multiply),
        dividerInitiationInterval = timing.divide.initiationInterval
      ),
      routes = configured.operators.routes.overlay(routesFor(configured.isa.xlen))
    ))
  }

  override def manifestValues: Seq[(String, String)] = Seq(
    "FPGA_IP_ATTACHMENT" -> name,
    "FPGA_DIV_IP_CYCLES" -> dividerIpCycles.toString,
    "FPGA_DIV_ADAPTER_CYCLES" -> dividerAdapterCycles.toString
  )
}

/** CDE 图选择的 IP attachment；仅 FPGA 图提供该键。 */
case object FpgaIpAttachmentKey extends Field[Option[FpgaIpAttachment]](None)

/** 把同一份 FPGA IP attachment 写入 CDE 图和完成的 NPC 配置。 */
class WithFpgaIpAttachmentConfig(attachment: FpgaIpAttachment) extends CDEConfig((_, _, up) => {
  case FpgaIpAttachmentKey => Some(attachment)
  case NpcCoreConfigKey => attachment.attachTo(up(NpcCoreConfigKey))
})
