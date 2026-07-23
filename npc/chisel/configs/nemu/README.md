# NEMU Host 配方

本目录定义普通 Scala `NemuHostConfig` 数据，不是独立硬件 Config，不能传给 `make config=`。
每个可运行终端通过 `NemuSimulationConstructionConfig` 或 `FpgaConstructionConfig` 显式提供一份
配方，并将结果冻结到 `profile.env`、`abi/nemu/host.defconfig` 和 `abi/nemu/host.env`。

| Base | 后端 | 用途 |
| --- | --- | --- |
| `NemuHostConfig.LocalBase` | local | 本地 Verilator 基础 host |
| `NemuHostConfig.LocalPerformance` | local | 增加性能主页与逐指令明细 |
| `NemuHostConfig.LocalPipelineTrace` | local | 增加流水线 HTML 与逐提交软件自查 |
| `NemuHostConfig.U55cBase` | u55c | U55C XRT host |
| `NemuHostConfig.Zcu102Base` | zcu102 | ZCU102 PS Linux host |

所有 Base 都显式填写 backend、trace、watchpoint、VCD、performance、pipeline、difftest、devices、
optimization、debug、LTO 和 ASAN 字段。自定义终端直接使用 case class `copy(...)`，无需定义继承类：

```scala
override protected val configuredNemu = NemuHostConfig.LocalBase.copy(
  trace = true,
  vcd = true,
  optimization = "O0",
  debug = true
)
```

XLEN、F、NPC/SoC、板卡地址、mailbox ABI 与 FPGA 平台始终从硬件 Config 派生，不能被 NEMU 配方
改写。本地仿真只接受 local backend；VCD 依赖 trace；`pipelineHtml` 依赖 `performanceHtml`，且
pipeline、VCD 和软件 difftest 只支持本地 Verilator。

`performanceHtml` 生成 `performance.html` 主页和 `instructions.html`；`pipelineHtml` 复用同一份
提交记录生成 `pipeline.html`，不会隐式开启 VCD 或 ITRACE。

```bash
make -C npc host-config-list
```

该命令只展示 `NemuHostConfig.registeredPresets` 中显式登记的 Base，不扫描源码或反射配置类。
profile 使用稳定的 `NEMU_PRESET` 名称；局部 `copy(...)` 配方显示为 `Custom`，实际行为仍由完整
`NEMU_*` 字段固定。
