# NEMU Host 配方

本目录定义普通 Scala `NemuHostConfig` 数据，不是独立硬件 Config，不能传给 `make config=`。
每个可运行终端通过唯一挂载的 `LocalNpcTerminal`、`LocalSocTerminal`、`U55cNpcTerminal`、
`U55cSocTerminal`、`Zcu102NpcTerminal` 或 `Zcu102SocTerminal` 取得闭合配方，并将结果冻结到
`profile.env`、`abi/nemu/host.defconfig` 和 `abi/nemu/host.env`。

底层后端枚举位于 `base/NemuBackend.scala`；终端只直接使用
`core/NemuHostConfig.scala` 中完整、具名的 host 配方。`NemuConfigCatalog.scala` 只是
`host-config-list` 的输出入口，不是终端 Config。本目录不定义硬件终端，因此没有 `Configs.scala`。

| Base | 后端 | 用途 |
| --- | --- | --- |
| `NemuHostConfig.LocalBase` | local | 本地 Verilator 基础 host |
| `NemuHostConfig.LocalPerformance` | local | 增加性能主页与逐指令明细 |
| `NemuHostConfig.LocalPipelineTrace` | local | 增加流水线 HTML 与逐提交软件自查 |
| `NemuHostConfig.U55cBase` | u55c | U55C XRT host |
| `NemuHostConfig.Zcu102Base` | zcu102 | ZCU102 PS Linux host |

所有 Base 都显式填写 backend、trace、watchpoint、VCD、performance、pipeline、difftest、devices、
optimization、debug、LTO 和 ASAN 字段。最终 `Configs.scala` 不得用 case class `copy(...)` 或
`configuredNemu` 现场定制。确需新 host 行为时，先在 `core/NemuHostConfig.scala` 用 `copy(...)`
定义并登记具名完整预设，再由根部 `common/TerminalTraits.scala` 的新闭合 trait 绑定。

XLEN、F、NPC/SoC、板卡地址、mailbox ABI 与 FPGA 平台始终从硬件 Config 派生，不能被 NEMU 配方
改写。本地仿真只接受 local backend；VCD 依赖 trace；`pipelineHtml` 依赖 `performanceHtml`，且
pipeline、VCD 和软件 difftest 只支持本地 Verilator。

`performanceHtml` 生成 `performance.html` 主页和 `instructions.html`；`pipelineHtml` 复用同一份
提交记录生成 `pipeline.html`，不会隐式开启 VCD 或 ITRACE。

```bash
make -C npc host-config-list
```

该命令只展示 `NemuHostConfig.registeredPresets` 中显式登记的 Base，不扫描源码或反射配置类。
profile 使用稳定的 `NEMU_PRESET` 名称；低层未登记的 case class 值会显示为 `Custom`，但公开终端
不得挂载这类临时配方，实际行为始终由完整 `NEMU_*` 字段固定。
