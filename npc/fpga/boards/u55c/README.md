# U55C 板卡层

- `config.mk`：器件、XRT 平台、HBM 映射、算术时序，以及 Vivado/Vitis 版本、
  300 MHz 时钟目标、WNS 门槛、综合/实现并发数和实现策略。
- `rtl/`：将 `NpcFpgaTop` 封装为 Vitis RTL kernel。
- `tcl/`：打包 XO 的 Vivado IP packager 流程。
- `constraints/`：独立内核检查使用的时钟约束。
- `link.cfg`：仅保存 kernel 到 HBM bank 的连接关系。时钟和 `[vivado]` 设置由
  构建流程从 `config.mk` 生成，避免同一频率在两个文件中漂移。

`FPGA_VIVADO_SYNTH_JOBS := 4` 与 `FPGA_VIVADO_IMPL_JOBS := 8` 是宿主 Vivado/Vitis 的 worker
jobs 上限，不是 FPGA 内 CPU 核数。`FPGA_VIVADO_IMPL_STRATEGY_SEARCH := 0` 保持单实现 run；设为
`1` 时，Vitis 会保留默认 run，并为 `FPGA_VIVADO_IMPL_STRATEGY` 额外启动策略 run，显著增加内存占用。
三项与 `U55cBoardConfig.scala` 的 `FpgaBuildSettings(...)` 必须保持一致。
