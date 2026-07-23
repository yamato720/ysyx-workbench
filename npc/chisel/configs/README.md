# Chisel Config 层级与组合

完整 Scala Config 是硬件 ABI、运行形态和 FPGA 实现策略的唯一来源。Make 每次顶层启动会调用
Scala 扫描本目录，只发现带显式终端 marker 的无参 Config；组合片段和 `check-only` 检查构造均不进入
公开目录。

`config=` 不提供 NEMU、DPI 或 Verilator 的可选模式。所有可发现终端都是 `run`，并绑定保存的 NEMU
运行宿主；本地仿真的 DPI 只是该宿主连接 Verilator 的内部实现。未传 `config=` 的 AM 命令仍保持原有
`ARCH` 驱动的普通 NEMU 路径。

## 层级

| 层级 | 目录 | 依赖方向 | 是否可选 | 完整成品文件 |
| --- | --- | --- | --- | --- |
| 公共 IP 描述 | `common/base/` | 不绑定 NPC、SoC 或板卡，供 CPU、外设和加速器复用 | 始终编译，不独立生成 | 无；仅通用 IP 参数数据 |
| 构造配方 | `common/core/`、`nemu/core/` | 两类运行 trait、NEMU host 与 FPGA 工具链 case class；不定义硬件 ABI | 所有 Make 终端必需 | `HostConstructionConfigs.scala`、`NemuHostConfig.scala`、`FpgaToolchainConfig.scala` |
| L1 NPC | `npc/` | 仅依赖 CDE 参数库，不依赖 Rocket/板卡 | 必需 | `Configs.scala` |
| L2 SoC | `ysyx/` | 依赖 L1 与 Rocket CDE | SoC 才需要 | `Configs.scala` |
| L3 FPGA | `fpga/common/` | 把 L1/L2 接入 FPGA CDE | FPGA 才需要 | `base/` 与 `core/` |
| L4 Board | `fpga/u55c/`、`fpga/zcu102/` | 依赖 L3，固定物理板卡 | FPGA 必需且二选一 | `*BoardConfig.scala`、`*Configs.scala` |

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
) with _root_.scpu.FpgaConstructionConfig with _root_.scpu.FpgaSocTerminalConfig {
  override protected val configuredNemu = _root_.scpu.NemuHostConfig.U55cBase
  override protected val configuredFpga = _root_.scpu.FpgaToolchainConfig.U55cBase
}
```

这里 `YsyxElaborateConfig` 提供 Rocket/SoC 和默认外部 AXI NPC；完整 L1 `FpgaConfig` 自身就是
`CDEConfig`，在其左侧直接覆盖核心。`U55cBoardConfig` 的 `FpgaBoardKey` 同时选择物理板卡和 FPGA
硬件分支。

所有本地 NPC/SoC 仿真终端都通过 `NemuSimulationConstructionConfig` 直挂
`NemuHostConfig.LocalPipelineTrace`，启用已提交指令的
性能主页、逐指令明细和 IF/ID/EX/MEM/WB HTML。流水线构造会显示阶段重叠和停顿；标量构造使用同一组提交级驻留计数，显示
顺序执行时间线；同一 host preset 还会逐提交比较 NPC 与 NEMU 的 GPR、FPR、FCSR、下一 PC 和主存
store 总线副作用。
FPGA 上板和 check-only Config 保持关闭。

`NemuSimulationConstructionConfig` 和 `FpgaConstructionConfig` 是仅有的两种运行构造 trait：前者
要求 local NEMU 配方，后者同时要求板卡匹配的 NEMU 与 FPGA 工具链配方。工具链按 `device`、`flow`、
`reports`、`runtime` 分组，可用 `U55cBase.copy(flow = U55cBase.flow.copy(...))` 局部覆盖。
`CheckOnlyConstructionConfigBase` 只用于测试。
自动目录只识别 `NpcTerminalConfig`、`SocTerminalConfig`、`FpgaNpcTerminalConfig`、
`FpgaSocTerminalConfig` 四种显式 marker，不再从类名后缀猜测目标。所有硬件参数应在可追踪的 Scala
组合中固定。

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
