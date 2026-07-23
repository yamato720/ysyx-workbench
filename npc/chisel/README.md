# NPC Chisel 目录结构

| 目录 | 编译边界 | 职责 |
| --- | --- | --- |
| `rv-core/` | SBT `root` | NPC 流水线、公共协议、DPI 组件和单元测试 |
| `fpga-harness/` | SBT `fpga` 或 Mill `ysyxsoc` | 裸 NPC/ysyxSoC 的 FPGA 系统与板卡 shell |
| `ysyxSoC/` | Mill `ysyxsoc` | Rocket/CDE/Diplomacy SoC 与教学外设 |
| `configs/` | 按使用范围编入上述目标 | L1-L4 命名 Config、片段和参数数据 |

依赖保持单向：L1 NPC 只额外依赖轻量 CDE 参数库，并直接提供 `NpcCoreConfigKey`；
`YsyxSocConfig` 默认选择 `ExternalAxiConfig`；终端板卡 SoC Config 再从左侧直接叠加完整 NPC
与板卡策略。裸 NPC 不会因为支持 FPGA 而引入 Rocket、Diplomacy 或 ysyxSoC 依赖。

## 构造矩阵

| 系统 | 非板级 | ZCU102 | U55C |
| --- | --- | --- | --- |
| 裸 NPC | `StandaloneConfig`、`SimulationConfig`、`PipelineSimulationConfig` | `Zcu102NpcFpgaConfig` | `U55cNpcFpgaConfig` |
| ysyxSoC | `YsyxSimulationConfig` | `Zcu102YsyxSocFpgaConfig` | `U55cYsyxSocFpgaConfig` |

完整 Config 都是无参类。架构、性能、存储、计算和接口片段位于 `configs/common/`，完整 Config
通过 `++` 显式组合它们，不读取 Make、JVM property 或环境变量。CDE 与 L1 `++` 均为左侧优先，例如：

```scala
class U55cYsyxSocFpgaConfig extends CDEConfig(
  new U55cBoardConfig ++
    new FpgaConfig ++
    new YsyxElaborateConfig
) with _root_.scpu.U55cSocTerminal
```

若要替换 SoC 内 NPC，只需将完成的 L1 Config 置于已有 SoC Config 左侧。板卡、频率、地址与算子
路由由 `U55cBoardConfig` 或 `Zcu102BoardConfig` 的 CDE 图固定；器件、工具策略和 NEMU host 由
根部的完整终端预设固定。最终 `Configs.scala` 只选择一个预设，不现场重载 case class，避免 Make
根据 scope 或 defconfig 名猜测运行宿主。

所有可复制特性、完整成品和 Make 发现规则见 [configs/README.md](configs/README.md)。
