# ZCU102 板卡层

- `config.mk`：器件、时钟、PS DDR 映射、算术时序，以及 Vivado 版本、WNS 门槛、
  综合/实现并发数和实现策略。
- `rtl/`：连接 `NpcFpgaTop` 与 ZynqMP PS AXI 接口的 PL wrapper。
- `tcl/`：裸核综合以及 PS/PL block design 链接流程。
- `constraints/`：独立时钟检查使用的板卡约束。
- `scripts/`：生成 reserved-memory 设备树片段和 NEMU 运行环境。
