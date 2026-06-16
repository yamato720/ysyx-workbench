# ZCU102 Runtime 建议地址映射

本文件定义 ZCU102 runtime 的建议物理地址布局。它需要兼顾 `ysyxSoC` 现有地址图和 ZynqMP PS/PL 的可实现性。

## 1. MVP 地址图

第一版建议使用独立 runtime 地址图，不强行复刻完整 `ysyxSoC` 外设。

```text
0x8000_0000 ~ 0x800f_ffff  guest memory window
0xa000_0000 ~ 0xa000_0fff  simple runtime MMIO
0xa000_1000 ~ 0xa000_1fff  trace control
0xa000_2000 ~ 0xa000_2fff  PS request/response queue
```

## 2. Simple MMIO

```text
0xa000_0000  PUTCH
  write[7:0] : append char to putch FIFO
  read       : optional FIFO status

0xa000_0004  HALT
  write[0]   : guest requests halt
  read[0]    : halted

0xa000_0008  EXIT_CODE
  write[31:0]: guest exit code
  read[31:0] : last exit code

0xa000_000c  CONTROL
  bit 0      : run
  bit 1      : reset_cpu
  bit 2      : clear_status

0xa000_0010  BOOT_PC
  write[31:0]: CPU reset vector

0xa000_0018  CYCLE_LOW
0xa000_001c  CYCLE_HIGH
0xa000_0020  INSTRET_LOW
0xa000_0024  INSTRET_HIGH
```

## 3. Guest Memory

### 3.1 BRAM MVP

```text
CPU sees:
  0x8000_0000 -> BRAM offset 0
```

PS side writes the same BRAM through an AXI-Lite/AXI BRAM controller window exposed in the Vivado block design.

优点：

- 简单；
- 可控；
- 便于 ILA 调试；
- 不依赖 DDR/cache coherency。

缺点：

- 容量小；
- 只适合 hello、cpu-tests 中的小程序。

### 3.2 PS DDR 版本

```text
CPU sees:
  0x8000_0000 -> PS DDR reserved physical range
```

PL CPU master 通过 AXI interconnect/data width converter 访问 ZynqMP PS 的 HP/HPC slave port。PS 软件负责保留一段 DDR，不让 Linux 或 bare-metal runtime 覆盖。

需要确认：

- PS DDR 地址偏移；
- AXI data width 转换；
- burst 长度支持；
- cache coherency 策略；
- Linux 下是否需要 reserved-memory。

### 3.3 PL DDR4 MIG 版本

ZCU102 也有连接到 PL 的 DDR4 component。使用它时，PL 侧需要实例化 MIG/DDR4 controller，并把 CPU AXI master 接到 MIG user AXI。

优点：

- CPU 访存不必经过 PS DDR 端口；
- 更接近独立 PL SoC；
- PS 可以通过 AXI interconnect 作为另一个 master 初始化内存。

缺点：

- MIG 配置、约束和时钟复位复杂度更高；
- 需要处理 CPU 32-bit AXI 与 MIG AXI 宽度差异；
- 第一版 bring-up 不建议直接从这里开始。

## 4. 与 `ysyxSoC` 现有地址图关系

`npc/chisel/ysyxSoC/src/SoC.scala` 当前包含：

```text
0x0f00_0000  SRAM
0x1000_0000  UART
0x1000_1000  SPI controller
0x1000_2000  GPIO
0x1001_1000  keyboard
0x2000_0000  MROM
0x2100_0000  VGA
0x3000_0000  XIP flash
0x8000_0000  PSRAM
0xa000_0000  SDRAM
```

上 ZCU102 时建议先不要强依赖这些外设地址。后续如果保留 `ysyxSoC` 原地址图，应做一层 board adapter：

```text
ysyxSoC original bus
  -> board address remap
  -> ZCU102 runtime devices / PS DDR / board UART
```

## 5. Boot 策略

推荐两种：

### 5.1 PS 写内存后启动

```text
1. PS assert cpu reset
2. PS writes image.bin to guest memory
3. PS writes BOOT_PC = 0x80000000
4. PS clears status
5. PS deassert cpu reset and set run
6. CPU starts fetching from guest memory
```

### 5.2 ROM 跳转

```text
1. CPU reset to small ROM
2. ROM jumps to 0x80000000
3. PS preloads image
```

MVP 更推荐 5.1，减少 ROM 综合和初始化不确定性。
