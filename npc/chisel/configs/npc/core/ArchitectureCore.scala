package npc

/** 已完成的 NPC ISA/XLEN 架构成品。
  *
  * 每个 RV32 成品都从 I 基线直接叠加完整扩展，避免同一扩展在文件中层层递归。
  * RV64 成品只复用同名 RV32 成品，并在左侧覆盖 XLEN；`WithXlenConfig` 同时
  * 更新 AXI 数据位宽。架构配方同时声明性能计数与指令提交信号；引出由
  * `++ TraceConfig` / `++ FinalLogConfig` 决定。
  */
abstract class ArchBundle(layers: ConfigFragment) extends ConfigBundle(layers)

/** 架构的逐指令详情硬件依赖。 */
class InstructionLogConfig extends ConfigBundle(
  new WithInstructionLogConfig
)

/** RV32I 基础架构。 */
class Rv32IConfig extends ArchBundle(
  new InstructionLogConfig ++
    new WithXlenConfig(32) ++
    new BaseIsaConfig
)

/** RV32I_Zicsr 架构。 */
class Rv32IZicsrConfig extends ArchBundle(
  new InstructionLogConfig ++
    new WithZicsrConfig ++
    new WithXlenConfig(32) ++
    new BaseIsaConfig
)

/** RV32IM_Zicsr 架构。 */
class Rv32IMZicsrConfig extends ArchBundle(
  new InstructionLogConfig ++
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
