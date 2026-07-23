# Chisel Config 层级与组合

完整 Scala Config 是硬件 ABI、运行形态和 FPGA 实现策略的唯一来源。Make 每次顶层启动会调用
Scala 检查分层并只扫描各终端领域根部的 `Configs.scala`；组合片段和 `check-only` 检查构造均不进入
公开目录。

`config=` 不提供 NEMU、DPI 或 Verilator 的可选模式。所有可发现终端都是 `run`，并绑定保存的 NEMU
运行宿主；本地仿真的 DPI 只是该宿主连接 Verilator 的内部实现。未传 `config=` 的 AM 命令仍保持原有
`ARCH` 驱动的普通 NEMU 路径。

## 层级

| 层级 | 目录 | 依赖方向 | 是否可选 | 完整成品文件 |
| --- | --- | --- | --- | --- |
| 公共底层 | `common/base/` | 通用 IP 数据、FPGA 工具链字段模型和不可由终端直挂的构造接口 | 始终编译，不独立生成 | `OperatorIpConfigs.scala`、`FpgaToolchainConfigModels.scala`、`ConstructionTraits.scala` |
| 构造配方 | `common/core/`、`nemu/core/` | 检查 trait、NEMU host 与 FPGA 工具链 case class；不定义硬件 ABI | 所有 Make 终端必需 | `CheckTraits.scala`、`NemuHostConfig.scala`、`FpgaToolchainConfig.scala` |
| 终端 trait | `common/TerminalTraits.scala` | 提供完整 NEMU/FPGA 默认配方、底层运行行为、目录身份、scope 和 target，不承载硬件组合 | 所有 Make 终端必需 | 根部直挂文件 |
| L1 NPC | `npc/` | 仅依赖 CDE 参数库，不依赖 Rocket/板卡 | 必需 | `core/` 成品与终端 `Configs.scala` |
| L2 SoC | `ysyx/` | 依赖 L1 与 Rocket CDE | SoC 才需要 | `core/YsyxCore.scala` 与终端 `Configs.scala` |
| L3 FPGA | `fpga/common/` | 把 L1/L2 接入 FPGA CDE | FPGA 才需要 | `base/` 与 `core/` |
| L4 Board | `fpga/u55c/`、`fpga/zcu102/` | 依赖 L3，固定物理板卡 | FPGA 必需且二选一 | `core/*BoardConfig.scala`、终端 `Configs.scala` |

## 统一文件协议

所有配置都遵守 `base -> core -> 根部终端文件` 的分层：

- `base/` 只定义参数键、普通数据模型、组合协议和 `With...Config` 原子片段。它不提供终端目录身份，
  不引用 `core/`，也不直接表达某个可运行目标。
- `core/` 调用 `base/`，把 ISA、流水线、接口、内存、SoC 或板卡策略组合成名称直观、含义完整的
  成品。终端必须直接引用这些成品，不能在终端文件里重新展开底层片段。
- 终端级文件与 `base/`、`core/` 文件夹分离，直接位于领域根部。当前六种共享终端预设 trait 统一位于
  `common/TerminalTraits.scala`；它不放参数模型、硬件组合或终端 Config。
- 领域根部的 `Configs.scala` 是唯一终端 Config 文件，只包含公共无参终端类。每个类只挂载一个 terminal 层
  预设，由它完整提供运行构造、作用域、目标和 NEMU/FPGA 默认配方。内置 Config 与普通示例应保持
  一步挂载；显式自定义终端可重载配方，但不得直接混入 base trait。检查构造和可复用成品也不得放入此文件。
- `common/`、`nemu/` 没有硬件终端 Config，因此不创建空的 `Configs.scala`；公共终端 trait 直接放在
  `common/TerminalTraits.scala`。FPGA 板卡共享 `fpga/common/base/`，各自在板卡目录的 `core/` 形成物理
  策略，并由同目录根部 `Configs.scala` 生成最终终端。

目录生成器会拒绝缺少根部 `Configs.scala` 的终端领域、出现在其他文件中的终端 trait、
`Configs.scala` 中未挂载 terminal 层 trait 的 Config 类，以及终端对 base 构造 trait 的直接混入。
移动文件时保持原 package，避免无意改变公开 FQCN。

## 可直接选择的 Config

下表所有 Config 都可以传给 `make ... config=<名称>`，能力均为 `run`。短名和 FQCN 均可用；每个类
必须有公共无参构造器，并分别满足 `ConstructionConfig` 或 CDE `Config` 类型约束。

| 名称 | 作用域 | 用途 |
| --- | --- | --- |
| `StandaloneConfig` | NPC | 最小 RV64I_Zicsr 本地仿真 |
| `SimulationConfig` | NPC | 默认 RV64IM_Zicsr 本地仿真 |
| `PipelineSimulationConfig` | NPC | 启用 ID/EX 前递的流水 NPC 本地仿真 |
| `FullIsa64NoPipelineSimulationConfig` | NPC | RV64IMF_Zicsr 无流水线性能基线 |
| `FullIsa64PipelineNoForwardingSimulationConfig` | NPC | RV64IMF_Zicsr 流水线无 ID/EX 前递 |
| `FullIsa64PipelineDualForwardingSimulationConfig` | NPC | RV64IMF_Zicsr 流水线双路径前递 |
| `Zcu102Rv32OperatorSimulationConfig` | NPC | ZCU102 RV32 M/F IP 时序的本地周期模型 |
| `U55cRv32OperatorSimulationConfig` | NPC | U55C RV32 M/F IP 时序的本地周期模型 |
| `U55cRv64OperatorSimulationConfig` | NPC | U55C RV64 M/F IP 时序的本地周期模型，覆盖 W 指令 |
| `YsyxSimulationConfig` | SoC | 默认 ysyxSoC 本地仿真 |
| `U55cNpcFpgaConfig` | FPGA（`TARGET=NPC`） | U55C 裸 NPC 上板运行 |
| `U55cFullIsa64NpcFpgaConfig` | FPGA（`TARGET=NPC`） | U55C RV64IMF_Zicsr 裸 NPC 上板运行 |
| `U55cFullIsa64Npc250MHzFpgaConfig` | FPGA（`TARGET=NPC`） | U55C RV64IMF_Zicsr 250 MHz 单实现时序实验 |
| `U55cYsyxSocFpgaConfig` | FPGA（`TARGET=SOC`） | U55C ysyxSoC 上板运行 |
| `Zcu102NpcFpgaConfig` | FPGA（`TARGET=NPC`） | ZCU102 裸 NPC 上板运行 |
| `Zcu102YsyxSocFpgaConfig` | FPGA（`TARGET=SOC`） | ZCU102 ysyxSoC 上板运行 |

## 可复用的组合与检查

这些类只能在 Scala `++` 链中使用，不能传给 `make config=`。它们不会独立产生没有运行宿主的
“生成型构造”。

| 名称 | 层级 | 用途 |
| --- | --- | --- |
| `FpgaConfig` | L1 | 默认 FPGA 裸 NPC 核心成品 |
| `FullIsa64PipelineDualForwardingFpgaConfig` | L1 | RV64IMF_Zicsr FPGA 核心成品 |
| `ExternalAxiConfig` | L1 | 供 SoC/外部系统集成的 AXI NPC 成品 |
| `YsyxSocConfig` | L2 | ysyxSoC 的默认组合图 |
| `YsyxElaborateConfig` | L2 | 供板卡或直接 Scala elaboration 叠加的 ysyxSoC 图 |
| `PipelineCheckConfig`、`FloatingCheckConfig`、`MulDivCheckConfig` | L1 | 仅供 Scala/RTL 检查的 `check-only` 构造 |

运行终端同样继承 CDE `Config`，因此类型上也能放入更高层 `++` 链；但这不是默认复用方式。它会完整
带入自己的 AXI、主存和算子 ABI，只有这些接口与目标系统兼容时才可覆盖上层默认核。常规 SoC/FPGA
组合应优先使用上表的 L1 核或 SoC 图，而不是复用本地仿真终端。

## 组合规则

CDE 和本项目 L1 Config 的 `++` 都是左侧优先：右侧先提供基础值，左侧覆盖。推荐每层一行：

```scala
class U55cYsyxSocFpgaConfig extends CDEConfig(
  new U55cBoardConfig ++
    new FpgaConfig ++
    new YsyxElaborateConfig
) with _root_.scpu.U55cSocTerminal
```

这里 `YsyxElaborateConfig` 提供 Rocket/SoC 和默认外部 AXI NPC；完整 L1 `FpgaConfig` 自身就是
`CDEConfig`，在其左侧直接覆盖核心。`U55cBoardConfig` 的 `FpgaBoardKey` 同时选择物理板卡和 FPGA
硬件分支。

所有本地 NPC/SoC 仿真终端都通过 `LocalNpcTerminal` 或 `LocalSocTerminal` 取得本地构造行为与默认的
`NemuHostConfig.LocalPipelineTrace`，启用已提交指令的
性能主页、逐指令明细和 IF/ID/EX/MEM/WB HTML。流水线构造会显示阶段重叠和停顿；标量构造使用同一组提交级驻留计数，显示
顺序执行时间线；同一 host preset 还会逐提交比较 NPC 与 NEMU 的 GPR、FPR、FCSR、下一 PC 和主存
store 总线副作用。
FPGA 上板和 check-only Config 保持关闭。

`HostConstruction`、`NemuSimulationConstruction`、`FpgaConstruction` 和 `MakeTerminal` 是
`common/base/ConstructionTraits.scala` 中的底层接口，只供 terminal 层组合，终端不能直接混入。
`LocalNpcTerminal`、`LocalSocTerminal`、`U55cNpcTerminal`、`U55cSocTerminal`、
`Zcu102NpcTerminal`、`Zcu102SocTerminal` 是 `common/TerminalTraits.scala` 中仅有的六种 Make 终端
预设；每个终端只挂载其中一个。工具链按 `device`、`flow`、`reports`、`runtime` 分组。显式自定义终端
可通过嵌套 `copy(...)` 局部重载 NEMU/FPGA 配方；重复使用或需要进入普通示例的配方应提升为 `core/`
中的具名完整 preset，必要时再增加根部 terminal trait。`CheckOnlyConstruction` 是检查构造直接挂载的
core trait，不进入 Make 目录。公共构造 trait 名称不使用 `Trait` 后缀，承载这些 trait 的文件统一使用
`*Traits.scala`。自动目录不再从类名后缀猜测目标；所有硬件参数应在可追踪的 Scala 组合中固定。

各目录 README 提供可直接复制到 `++` 链的完整特性表。

## Make 与 profile

```bash
make -C npc config-list
make -C npc build config=SimulationConfig
make -C npc build config=U55cYsyxSocFpgaConfig
```

自动 TSV 只在 Make 启动 JVM 前提供短名、FQCN、作用域、板卡和目标。选中后由 Scala 反射实例化，
生成包含 XLEN、ISA、流水线、算术时序、内存、板卡、工具策略、运行宿主 ABI 和协议 ABI 的
`profile.env`。NEMU 配方以稳定的 `NEMU_PRESET` 和完整 `NEMU_*` 字段记录；FPGA 工具链继续渲染为
现有 `FPGA_*` 字段。其中 `NEMU_PERFORMANCE_HTML` 与 `NEMU_PIPELINE_HTML` 是 NEMU 层行为；后者只能在前者之上
启用，两者都不修改 `PerformConfig` 或 FPGA 顶层调试端口。
Make/Tcl 只做映射与一致性检查，不能覆盖这些值。
