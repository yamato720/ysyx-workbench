package npc

/** 流水线旁路通路的开关。 */
case class ForwardingConfig(
  enableIdForwarding: Boolean = true,
  enableExecuteForwarding: Boolean = true
)

/** 流水线、互锁和旁路的生成时参数。 */
case class PipelineConfig(
  enablePipeline: Boolean = false,
  enableInterlock: Boolean = true,
  forwarding: ForwardingConfig = ForwardingConfig(),
  integerExecuteStages: Int = 1,
  serialExecuteStages: Int = 1,
  registerInitialFetchRequest: Boolean = false,
  separateSerialIntegerAlu: Boolean = false,
  serialExecuteResultForwarding: Boolean = true
) {
  require(integerExecuteStages == 1 || integerExecuteStages == 2,
    s"integerExecuteStages must be 1 or 2, got $integerExecuteStages")
  require(serialExecuteStages >= 1 && serialExecuteStages <= 3,
    s"serialExecuteStages must be 1, 2, or 3, got $serialExecuteStages")
}

/** 主存与 MMIO 地址窗口，以及复位向量。 */
case class MemoryConfig(
  resetVector: BigInt = BigInt("80000000", 16),
  mainMemoryBase: Long = 0x80000000L,
  mainMemorySize: Long = 0x10000000L,
  mmioBase: Long = 0xA0000000L,
  mmioSize: Long = 0x02000000L
)

/** 顶层调试和派发控制接口的开关。 */
case class DebugConfig(
  enableTopDebugIo: Boolean = false,
  enableDispatchControl: Boolean = false
)

/** NPC AXI master 的接口形状和外部连接策略。 */
case class AxiConfig(
  addrWidth: Int = 32,
  dataWidth: Int = 64,
  idWidth: Int = 4,
  transactionId: Int = 0,
  useExternalMaster: Boolean = false
)

/** 硬件模块最终消费的完整、无依赖 NPC 参数值。 */
case class NpcConfig(
  isa: ISAConfig = ISAConfig(),
  pipeline: PipelineConfig = PipelineConfig(),
  operators: OperatorConfig = OperatorConfig(),
  memory: MemoryConfig = MemoryConfig(),
  axi: AxiConfig = AxiConfig(),
  debug: DebugConfig = DebugConfig()
) {
  def validated: NpcConfig = {
    operators.routes.validate(isa)
    this
  }
}
