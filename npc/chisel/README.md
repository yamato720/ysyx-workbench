# NPC Chisel 目录结构

| 目录 | 编译边界 | 职责 |
| --- | --- | --- |
| `rv-core/` | SBT `root` | NPC 流水线、公共协议、DPI 组件和单元测试 |
| `fpga-harness/` | SBT `fpga` 或 Mill `ysyxsoc` | 裸 NPC/ysyxSoC 的 FPGA 系统与板卡 shell |
| `ysyxSoC/` | Mill `ysyxsoc` | Rocket/CDE/Diplomacy SoC 与教学外设 |
| `configs/` | 按使用范围编入上述目标 | L1-L4 命名 Config、片段和参数数据 |

依赖保持单向：纯 NPC 只产生 `NpcConfig`；FPGA 公共层把它放入 `NpcCoreConfigKey`；
`YsyxSocConfig` 默认选择 `NpcExternalAxiCdeConfig`；终端板卡 SoC Config 再从左侧覆盖核心和板卡。
裸 NPC 不会因为支持 FPGA 而引入 Rocket 依赖。

## 构造矩阵

| 系统 | 非板级 | ZCU102 | U55C |
| --- | --- | --- | --- |
| 裸 NPC | `NpcStandaloneConfig`、`NpcDpiConfig`、`NpcPipelineDpiConfig` | `Zcu102NpcFpgaConfig` | `U55cNpcFpgaConfig` |
| ysyxSoC | `YsyxStandaloneConfig`、`YsyxSimulationConfig` | `Zcu102YsyxSocFpgaConfig` | `U55cYsyxSocFpgaConfig` |

完整 Config 都是无参类。`NpcBuildSettings` 只提供 Scala 内命名的固定 ABI 预设，不读取 Make、JVM
property 或环境变量。CDE 与 L1 `++` 均为左侧优先，例如：

```scala
class U55cYsyxSocFpgaConfig extends CDEConfig(
  new U55cNpcFpgaConfig ++
    new YsyxSocFpgaConfig
) with MakeConstructionConfig {
  override val capability: String = "fpga-soc"
}
```

若要替换 SoC 内 NPC，应先定义完成的 L1 Config，再定义一个 CDE 层把其 `config` 写入
`NpcCoreConfigKey`，并将该层放在已有 SoC Config 左侧。板卡、频率、地址、器件和工具策略由
`U55cBoardConfig` 或 `Zcu102BoardConfig` 固定。

所有可复制特性、完整成品和 Make 发现规则见 [configs/README.md](configs/README.md)。
