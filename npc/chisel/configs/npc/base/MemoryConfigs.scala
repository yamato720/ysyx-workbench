package npc

/** 固定主存窗口和复位向量。 */
class WithMainMemoryConfig(base: Long, size: Long) extends ConfigFragment {
  require(size > 0, s"NPC main-memory size must be positive, got $size")

  override private[npc] def applyTo(config: NpcConfig): NpcConfig = config.copy(
    memory = config.memory.copy(
      resetVector = BigInt(base & 0xffffffffL),
      mainMemoryBase = base,
      mainMemorySize = size
    )
  )
}

/**
  * 启用确定性的本地 DPI 主存时序模型。它只作用于本地 `NpcMemoryFabric` 的 RAM slave，
  * MMIO 保持即时响应。
  */
class WithDpiMemoryTimingConfig(timing: DpiMemoryTimingConfig) extends ConfigFragment {
  override private[npc] def applyTo(config: NpcConfig): NpcConfig = config.copy(
    memory = config.memory.copy(dpiTiming = timing)
  )
}

/**
  * 选择更宽的本地 cache-memory port，但不创建外部 AXI master。底层字段也描述外部
  * cache-memory 宽度，因此已有 construction profile 保持稳定的字段名。
  */
class WithLocalDpiCacheMemoryWidthConfig(dataWidth: Int) extends ConfigFragment {
  require(dataWidth >= 32 && (dataWidth & (dataWidth - 1)) == 0,
    s"local DPI cache-memory width must be a power of two and at least 32, got $dataWidth")

  override private[npc] def applyTo(config: NpcConfig): NpcConfig = config.copy(
    axi = config.axi.copy(externalDataWidth = dataWidth)
  )
}

/** 裸 NPC 默认 256 MiB 主存窗口。 */
class WithBareMainMemoryConfig extends ConfigBundle(
  new WithMainMemoryConfig(base = 0x80000000L, size = 0x10000000L)
)

/** ysyxSoC 仿真使用的 128 MiB 主存窗口。 */
class WithSoCMainMemoryConfig extends ConfigBundle(
  new WithMainMemoryConfig(base = 0x80000000L, size = 0x08000000L)
)

/** FPGA 主机/协议 ABI 使用的 128 MiB 主存窗口。 */
class WithFpgaMainMemoryConfig extends ConfigBundle(
  new WithMainMemoryConfig(base = 0x80000000L, size = 0x08000000L)
)
