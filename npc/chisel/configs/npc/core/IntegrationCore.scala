package npc

/** NPC 集成属性的终端配方。
  *
  * 这些配方只选择接口、调试 IO 和主存窗口，不携带 ISA、流水线、缓存或 [[BaseConfig]]。
  * 裸 NPC、SoC 和 FPGA 终端在各自的 `Configs.scala` 中显式叠加一个集成配方。
  */

/** 本地裸 NPC 集成：顶层调试 IO 和 256 MiB 本地主存窗口。 */
class BareNpcIntegrationConfig extends ConfigBundle(
  new WithTopDebugConfig ++
    new WithBareMainMemoryConfig
)

/** 本地宽内存仿真集成：顶层调试 IO 和 128 MiB 主存窗口。
  *
  * 本地 HBM/L2 对照构造通过另一个片段选择 512-bit cache-memory port；本配方只负责
  * 主存地址窗口和顶层可观测 IO。
  */
class LocalWideMemoryIntegrationConfig extends ConfigBundle(
  new WithTopDebugConfig ++
    new WithFpgaMainMemoryConfig
)

/** 本地宽 HBM 抖动仿真集成：宽 cache-memory port 与 73--81 cycle DPI 时序。 */
class LocalWideHbmJitterIntegrationConfig extends ConfigBundle(
  new WithDpiMemoryTimingConfig(DpiMemoryTimingConfig.HbmJitter73To81) ++
    new WithLocalDpiCacheMemoryWidthConfig(512) ++
    new LocalWideMemoryIntegrationConfig
)

/** ysyxSoC 集成：CPU-side 外部 AXI、顶层调试 IO 和 SoC 主存窗口。 */
class ExternalAxiSocIntegrationConfig extends ConfigBundle(
  new WithExternalAxiConfig ++
    new WithTopDebugConfig ++
    new WithSoCMainMemoryConfig
)

/** FPGA NPC 集成：外部 AXI、派发控制、顶层调试 IO 和 FPGA 主存窗口。 */
class FpgaNpcIntegrationConfig extends ConfigBundle(
  new WithExternalAxiConfig ++
    new WithDispatchControlConfig ++
    new WithTopDebugConfig ++
    new WithFpgaMainMemoryConfig
)
