package scpu

/** 已完成的 NPC ISA/XLEN 架构成品。
  *
  * 每个 RV32 成品都从 I 基线直接叠加完整扩展，避免同一扩展在文件中层层递归。
  * RV64 成品只复用同名 RV32 成品，并在左侧覆盖 XLEN；`WithXlenConfig` 同时
  * 更新 AXI 数据位宽。F 放在 Zicsr 的左侧，实际应用时会先启用 Zicsr，再验证并启用 F。
  */
abstract class ArchBundle(layers: ConfigFragment) extends ConfigBundle(layers)

/** RV32I 基础架构。 */
class Rv32IConfig extends ArchBundle(
  new WithXlenConfig(32) ++
    new BaseIsaConfig
)

/** RV32I_Zicsr 架构。 */
class Rv32IZicsrConfig extends ArchBundle(
  new WithZicsrConfig ++
    new WithXlenConfig(32) ++
    new BaseIsaConfig
)

/** RV32IM_Zicsr 架构。 */
class Rv32IMZicsrConfig extends ArchBundle(
  new WithMExtensionConfig ++
    new WithZicsrConfig ++
    new WithXlenConfig(32) ++
    new BaseIsaConfig
)

/** RV32IMF_Zicsr 架构。 */
class Rv32IMFZicsrConfig extends ArchBundle(
  new WithFloatingPointConfig ++
    new WithMExtensionConfig ++
    new WithZicsrConfig ++
    new WithXlenConfig(32) ++
    new BaseIsaConfig
)

/** RV64I 基础架构。 */
class Rv64IConfig extends ArchBundle(
  new WithXlenConfig(64) ++
    new Rv32IConfig
)

/** RV64I_Zicsr 架构。 */
class Rv64IZicsrConfig extends ArchBundle(
  new WithXlenConfig(64) ++
    new Rv32IZicsrConfig
)

/** RV64IM_Zicsr 架构。 */
class Rv64IMZicsrConfig extends ArchBundle(
  new WithXlenConfig(64) ++
    new Rv32IMZicsrConfig
)

/** RV64IMF_Zicsr 架构。 */
class Rv64IMFZicsrConfig extends ArchBundle(
  new WithXlenConfig(64) ++
    new Rv32IMFZicsrConfig
)
