# Vivado 工程策略

ZCU102 推荐使用 Vivado block design 管理 ZynqMP PS、AXI interconnect、clock/reset 和 PL RTL wrapper。

## 第一版 block design

```text
Zynq UltraScale+ MPSoC PS
  - FCLK_CLK0 -> PL runtime clock
  - FCLK_RESET0_N -> processor system reset
  - M_AXI_HPM*_FPD or LPD -> runtime control AXI-Lite

PL
  - ZCU102RuntimeTop
  - AXI BRAM Controller
  - Block Memory Generator
  - AXI Interconnect / SmartConnect
  - optional ILA
```

## 第二版 block design

新增：

```text
PS S_AXI_HP/HPC port
  <- PL CPU AXI master via SmartConnect
  <- data width converter
  <- clock converter if needed
```

或者：

```text
PL DDR4 MIG
  <- PL CPU AXI master via SmartConnect
  <- optional PS initialization master
```

## 工程生成原则

- Vivado generated files 不提交。
- Tcl 脚本只保存可复现工程骨架。
- XDC 从官方 ZCU102 master XDC/board files 派生，不手写未经验证的管脚。
- Block design 变更后导出 Tcl。

## 预期输入

```text
ysyxSoC/build/ysyxSoCFull.v       可选 full SoC RTL
npc/chisel 或 ysyx CPU Verilog    CPU BlackBox 实现
zcu102-runtime/rtl/*              runtime wrapper
```
