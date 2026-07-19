# Chisel Config 层级与组合

完整 Scala Config 是硬件 ABI 和运行后端的唯一来源。Make 每次顶层启动会调用 Scala 扫描本目录，
自动发现符合命名和类型约束的无参终端 Config；`With...Config` 片段和 `check-only` 构造不会进入
公开目录。

## 层级

| 层级 | 目录 | 依赖方向 | 是否可选 | 完整成品文件 |
| --- | --- | --- | --- | --- |
| L1 NPC | `npc/` | 无 CDE/Rocket/板卡依赖 | 必需 | `NpcConfigs.scala` |
| L2 SoC | `ysyx/` | 依赖 L1 与 Rocket CDE | SoC 才需要 | `SocConfig.scala` |
| L3 FPGA | `fpga/common/` | 把 L1/L2 接入 FPGA CDE | FPGA 才需要 | `FpgaConfigs.scala` 提供公共成品 |
| L4 Board | `fpga/u55c/`、`fpga/zcu102/` | 依赖 L3，固定物理板卡 | FPGA 必需且二选一 | `*NpcConfig.scala`、`*SocConfig.scala` |

## 可直接选择的 Config

| 名称 | 作用域 | 能力 | 用途 |
| --- | --- | --- | --- |
| `NpcStandaloneConfig` | NPC | `elaborate-only` | 独立 NPC RTL |
| `NpcDpiConfig` | NPC | `verilator-npc` | 默认 NPC/NEMU 仿真 |
| `NpcPipelineDpiConfig` | NPC | `verilator-npc` | 流水 NPC/NEMU 仿真 |
| `NpcFullIsa64NoPipelineDpiConfig` | NPC | `verilator-npc` | RV64IMF_Zicsr，无流水线性能基线 |
| `NpcFullIsa64PipelineNoForwardingDpiConfig` | NPC | `verilator-npc` | RV64IMF_Zicsr，流水线无 ID/EX 前递 |
| `NpcFullIsa64PipelineDualForwardingDpiConfig` | NPC | `verilator-npc` | RV64IMF_Zicsr，流水线双路径（ID/EX）前递 |
| `NpcFullIsa64PipelineDualForwardingFpgaConfig` | NPC | `elaborate-only` | RV64IMF_Zicsr FPGA 核心成品，供 L4 板卡终端复用 |
| `NpcFpgaConfig` | NPC | `elaborate-only` | 供 L3/L4 复用的 FPGA NPC 成品 |
| `NpcExternalAxiConfig` | NPC | `elaborate-only` | 供 SoC 复用的外部 AXI NPC |
| `YsyxStandaloneConfig` | SoC | `elaborate-only` | 独立 ysyxSoC RTL |
| `YsyxSimulationConfig` | SoC | `verilator-soc` | 默认 ysyxSoC/NEMU 仿真 |
| `U55cNpcFpgaConfig` | FPGA-NPC | `fpga-npc` | U55C 裸 NPC |
| `U55cYsyxSocFpgaConfig` | FPGA-SoC | `fpga-soc` | U55C ysyxSoC |
| `Zcu102NpcFpgaConfig` | FPGA-NPC | `fpga-npc` | ZCU102 裸 NPC |
| `Zcu102YsyxSocFpgaConfig` | FPGA-SoC | `fpga-soc` | ZCU102 ysyxSoC |

短名和 FQCN 均可用于 `config=`。每个可选择类必须有公共无参构造器，并分别满足
`NpcConstructionConfig` 或 CDE `Config` 类型约束。

## 组合规则

CDE 和本项目 L1 Config 的 `++` 都是左侧优先：右侧先提供基础值，左侧覆盖。推荐每层一行：

```scala
class U55cYsyxSocFpgaConfig extends CDEConfig(
  new U55cNpcFpgaConfig ++
    new YsyxSocFpgaConfig
) with MakeConstructionConfig {
  override val capability: String = "fpga-soc"
}
```

这里 `YsyxSocFpgaConfig` 提供 Rocket/SoC 和默认外部 AXI NPC，左侧 `U55cNpcFpgaConfig` 同时覆盖
NPC 与板卡策略。终端 Config 必须显式声明能力，所有硬件参数应在其可追踪的 Scala 组合中固定。

各目录 README 提供可直接复制到 `++` 链的完整特性表。

## Make 与 profile

```bash
make -C npc config-list
make -C npc build config=NpcDpiConfig
make -C npc build config=U55cYsyxSocFpgaConfig
```

自动 TSV 只在 Make 启动 JVM 前提供短名、FQCN、作用域、板卡和目标。选中后由 Scala 反射实例化，
生成包含 XLEN、ISA、流水线、算术时序、内存、板卡、工具策略、host ABI 和协议 ABI 的
`profile.env`。Make/Tcl 只做映射与一致性检查，不能覆盖这些值。
