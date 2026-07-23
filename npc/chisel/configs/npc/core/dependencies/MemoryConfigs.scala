package scpu

/** 固定主存窗口和复位向量。 */
class WithMainMemoryConfig(base: Long, size: Long) extends ConfigFragment {
  require(size > 0, s"NPC main-memory size must be positive, got $size")

  override private[scpu] def applyTo(config: NpcConfig): NpcConfig = config.copy(
    memory = config.memory.copy(
      resetVector = BigInt(base & 0xffffffffL),
      mainMemoryBase = base,
      mainMemorySize = size
    )
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
