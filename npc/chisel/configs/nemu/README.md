# NEMU Host 配方

本目录定义普通 Scala `NemuHostConfig` 数据，不是独立硬件 Config，不能传给 `make config=`。
每个可运行终端通过唯一挂载的 `LocalNpcTerminal`、`LocalSocTerminal`、`U55cNpcTerminal`、
`U55cSocTerminal`、`Zcu102NpcTerminal` 或 `Zcu102SocTerminal` 取得完整默认配方，并将结果冻结到
`profile.env`、`abi/nemu/host.defconfig` 和 `abi/nemu/host.env`。

底层后端枚举位于 `base/NemuBackend.scala`；终端只直接使用
`core/NemuHostConfig.scala` 中完整、具名的 host 配方。`NemuConfigCatalog.scala` 只是
`host-config-list` 的输出入口，不是终端 Config。本目录不定义硬件终端，因此没有 `Configs.scala`。

| Base | 后端 | 用途 |
| --- | --- | --- |
| `NemuHostConfig.LocalBase` | local | 本地 Verilator 基础 host |
| `NemuHostConfig.LocalPerformance` | local | 增加性能主页与逐指令明细 |
| `NemuHostConfig.LocalPipelineTrace` | local | 增加流水线 HTML 与逐提交软件自查 |
| `NemuHostConfig.LocalVcdTrace` | local | 在 `LocalPipelineTrace` 上启用 SDB 交互式 VCD |
| `NemuHostConfig.U55cBase` | u55c | U55C XRT host |
| `NemuHostConfig.U55cPerformanceMonitor` | u55c | v13 U55C 批处理性能报告 host |
| `NemuHostConfig.Zcu102Base` | zcu102 | ZCU102 PS Linux host |

所有 Base 都显式填写 backend、trace、watchpoint、VCD、performance、cache、pipeline、difftest、devices、
optimization、debug、LTO 和 ASAN 字段。内置 Config 和普通示例直接使用终端 trait 的完整默认值。
显式自定义终端可用 `configuredNemu` 与 case class `copy(...)` 局部重载；重复使用的 host 行为应在
`core/NemuHostConfig.scala` 定义并登记为具名完整预设。

XLEN、F、NPC/SoC、板卡地址、mailbox ABI 与 FPGA 平台始终从硬件 Config 派生，不能被 NEMU 配方
改写。本地仿真只接受 local backend；VCD 依赖 trace；`cacheHtml` 与 `pipelineHtml` 都依赖
`performanceHtml`；VCD 和
软件 difftest 只支持本地 Verilator。普通 U55C v11 host 只提供运行和 SDB 调试；v13
`U55cPerformanceMonitor` 只由 batch-only 的硬件终端使用，并从 HBM 回放性能、逐指令和流水报告。

`performanceHtml` 生成 `performance.html` 主页和 `instructions.html`。`cacheHtml` 是独立的可选子报告：
它仅在实际启用 I$ 或 D$ 的运行中生成 `cache.html`，包含几何、策略、instruction buffer 和命中/未命中等
计数，主页会在该文件存在时显示入口。`LocalPipelineTrace` 与 `U55cPerformanceMonitor` 默认开启该功能；
其他自定义终端可用 `NemuHostConfig.LocalPipelineTrace.copy(cacheHtml = false)` 关闭。`pipelineHtml` 复用同一份
提交记录生成 `pipeline.html`，不会隐式开启 VCD 或 ITRACE。

`LocalVcdTrace` 是唯一的内置 VCD 配方：它同时启用 `trace` 与 `vcd`，因此 NEMU 提供 SDB `start`/`stop`
命令；`start` 后的波形写入当前运行目录的 `wave-001.vcd`，`stop` 会关闭并刷新文件。VCD 需要 Verilator
在硬件构造阶段启用 `--trace` 并链接 `verilated_vcd_c.o`，所以从无 VCD 构造切换到该配方必须完整
`build`/`rebuild`，不能仅刷新 NEMU host。

```bash
make -C npc host-config-list
```

该命令只展示 `NemuHostConfig.registeredPresets` 中显式登记的 Base，并列出包括 `cache-html` 在内的冻结策略，
不扫描源码或反射配置类。
profile 使用稳定的 `NEMU_PRESET` 名称；自定义终端使用的未登记 case class 值显示为 `Custom`，实际
行为仍由完整 `NEMU_*` 字段固定。
