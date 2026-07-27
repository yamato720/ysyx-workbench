# U55C 板卡层

- `config.mk`：U55C 允许频率、HBM 地址映射和算术 IP 适配时序；器件、XRT 平台与
  Vivado/Vitis 流程来自终端 `FpgaToolchainConfig`。
- `rtl/`：将 `NpcFpgaTop` 封装为 Vitis RTL kernel。
- `tcl/`：打包 XO 的 Vivado IP packager 流程。
- `constraints/`：独立内核检查使用的时钟约束。
- `link.cfg`：仅保存 kernel 到 HBM bank 的连接关系。时钟和 `[vivado]` 设置由
  构建流程从冻结的 Scala profile 生成，避免同一策略在两个文件中漂移。

`FpgaToolchainConfig.U55cBase.flow` 的综合/实现 jobs 是宿主 Vivado/Vitis 的 worker 上限，不是
FPGA 内 CPU 核数。策略搜索设为 `true` 时，Vitis 会保留默认 run，并为所选实现策略额外启动 run，
显著增加内存占用；自定义终端可通过分组 `copy(...)` 重载这些值。

终端 `reports` 还固定时序路径深度和六个 `FPGA_REPORT_*` 开关。Vitis 的每个 implementation run
在 post-route 时先执行自身默认 hook，再在该 run
目录下写入 `npc-implementation-reports/`；多策略并行时报告不会互相覆盖。
