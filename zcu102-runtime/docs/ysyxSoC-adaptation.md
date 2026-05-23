# `ysyxSoC` 到 ZCU102 的适配点

## 1. 当前 `ysyxSoC` 结构

关键文件：

```text
ysyxSoC/src/CPU.scala       CPU BlackBox + AXI4 master wrapper
ysyxSoC/src/SoC.scala       SoC 地址图、AXI/APB 互连、外设
ysyxSoC/src/Top.scala       当前生成顶层
ysyxSoC/Makefile            Verilog 生成流程
ysyxSoC/spec/cpu-interface.md
```

当前 `ysyxSoCTop` 是生成/仿真友好的顶层，不是板级顶层：

```scala
val dut = LazyModule(new ysyxSoCFull)
val mdut = Module(dut.module)
mdut.externalPins := DontCare
```

ZCU102 需要新增板级顶层，明确连接 clock/reset、AXI、UART 和状态寄存器。

## 2. 推荐新增层次

```text
ZCU102BoardTop
  - board clock/reset
  - PS AXI control slave
  - optional PS DDR AXI master path
  - UART pins
  - ILA debug ports

ZCU102RuntimeTop
  - CPU reset/run control
  - boot_pc register
  - guest memory mux
  - simple MMIO
  - trace/putch FIFO

ysyx CPU or ysyxSoC subsystem
  - AXI4 master
  - interrupt
  - optional slave/debug AXI
```

## 3. CPU 接入策略

### 3.1 轻量 CPU-only 接入

第一版建议从 `ysyxSoC/src/CPU.scala` 里的 CPU AXI4 wrapper 出发，只接 CPU master：

```text
CPU master AXI
  -> runtime AXI decode
     -> guest BRAM
     -> simple MMIO
```

优点：

- 变量少；
- 更容易调时序；
- 不会被完整 SoC 外设影响 bring-up。

### 3.2 完整 `ysyxSoC` 接入

后续再把 `ysyxSoCASIC` 或 `ysyxSoCFull` 纳入：

```text
ysyxSoCASIC
  -> replace PSRAM/SDRAM/MROM devices
  -> board adapter
  -> ZCU102 pins / PS DDR
```

这一步需要逐个外设确认是否可综合、是否匹配 ZCU102。

## 4. 必须替换或屏蔽的模块

| 模块 | 当前问题 | ZCU102 处理 |
| --- | --- | --- |
| `MROMHelper` | DPI-C，不可综合 | 改 BRAM/ROM 或移除 |
| `psram` | 教学板/仿真设备假设 | MVP 不接，后续替换 |
| `sdram` | 离散 SDRAM 假设 | 用 PS DDR 替代 |
| `flash` | blackbox 仿真模型 | MVP 不接 |
| `vga` | pinout/时钟未适配 | 后续单独适配 |
| `ps2` | ZCU102 不一定需要 | 屏蔽 |
| `ysyx_00000000` | CPU BlackBox | Vivado 必须加入真实 Verilog |

## 5. AXI 注意事项

当前 CPU wrapper 使用：

```text
addrBits = 32
dataBits = 32
idBits   = ChipLinkParam.idBits
```

接 ZynqMP PS DDR 时通常需要：

- AXI data width converter；
- AXI clock converter；
- AXI protocol converter，若实际端口能力不完全一致；
- 限制或验证 burst；
- 明确 reset domain。

MVP BRAM 版本可以先用 32-bit AXI RAM，避免这些复杂度。

## 6. 中断策略

MVP：

```text
cpu.interrupt := false
```

后续：

- simple timer interrupt；
- PS 侧 doorbell interrupt；
- CLINT/PLIC 子集。

## 7. Trace 策略

不要第一版就做实时 DiffTest。建议 CPU 或 SoC 输出：

```text
valid
pc
inst
rd
rd_wen
rd_wdata
trap
```

runtime 写入 BRAM/DDR ring buffer，PS 侧导出后离线比对。

## 8. 适配原则

- 不在 `ysyxSoC` 内部硬编码 ZCU102 pinout。
- 新增 board adapter，而不是污染通用 SoC。
- 先 CPU-only，再 full SoC。
- 先 BRAM，再 PS DDR。
- 先 polling，再 interrupt。
