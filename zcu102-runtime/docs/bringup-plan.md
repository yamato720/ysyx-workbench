# ZCU102 Bring-up Plan

## 0. 工具和输入确认

- Vivado/Vitis 版本固定。
- ZCU102 board files 已安装。
- 能通过 JTAG 连接板卡。
- 能运行官方 BIST 或 hello world。
- `ysyxSoC/Makefile verilog` 能生成 RTL。
- 真实 CPU Verilog 已加入 Vivado sources。

## 1. RTL 净化检查

检查生成结果中不能出现：

```text
import "DPI-C"
$fopen / $fread / $fwrite
Verilator-only system task
不可综合 initial 大逻辑
```

允许：

- 小 ROM 的 `$readmemh`，但需要确认 Vivado 综合策略；
- ILA/debug mark；
- vendor memory IP。

## 2. BRAM MVP

### 2.1 PL 侧

- `ZCU102RuntimeTop` 实例化 CPU wrapper。
- 加一块 AXI BRAM 作为 guest memory。
- 加 simple MMIO 寄存器：
  - `PUTCH`
  - `HALT`
  - `EXIT_CODE`
  - `CONTROL`
  - `BOOT_PC`
- CPU reset 由 `CONTROL.reset_cpu` 控制。

### 2.2 PS 侧

- 写 BRAM window。
- 写 `BOOT_PC = 0x80000000`。
- 释放 CPU reset。
- 轮询 `HALT`。
- 打印 putch FIFO。

### 2.3 成功标准

```text
PS console prints guest output
HALT == 1
EXIT_CODE == expected value
ILA shows CPU AXI reads from 0x80000000
```

## 3. 板载 DDR 版本

### 3.1 PS DDR

- 在 block design 中启用 PS HP/HPC AXI port。
- CPU AXI master 接 AXI interconnect。
- 加 data width converter / clock converter。
- PS 侧预留 DDR 区间。
- CPU 从 DDR 取指。

### 3.2 PL DDR4 MIG

- 在 Vivado 中配置 ZCU102 PL DDR4 MIG。
- CPU AXI master 接 MIG user AXI。
- PS 侧通过另一路 AXI master 或调试通道初始化 guest memory。
- 加 ILA 观察 MIG calib done、AXI request/response。

成功标准：

```text
same hello binary runs from PS DDR
larger cpu-tests can run beyond BRAM size
no AXI decode errors
```

## 4. Trace 版本

- 增加 trace ring buffer。
- 每条 retired instruction 写入 trace。
- PS 侧 dump trace 到文件。
- Host PC 或 PS Linux 上用 NEMU 离线比对。
- 不让 PS ARM/NEMU 接管普通 fetch/load/store。

成功标准：

```text
trace length == instret
first mismatch can identify pc/inst/rd
```

## 5. Full `ysyxSoC` 版本

- 替换 MROM。
- 替换/屏蔽 PSRAM、SDRAM、flash blackbox。
- 保留 UART 或映射到 runtime putch。
- 按需接 GPIO/VGA/SPI。

成功标准：

```text
full SoC address map can run small AM tests
board-specific peripherals are isolated in adapter layer
```

## 6. 调试建议

- 第一个 bitstream 必须带 ILA：CPU AXI AR/R、AW/W/B、reset、boot_pc、halt。
- CPU reset 释放后，先看第一笔 AR 地址是否为 `BOOT_PC`。
- 如果 AR 有但 R 无，查 memory decode。
- 如果 R 有但后续乱跳，查 reset vector、镜像格式、字节序。
- 如果 store 到 `PUTCH` 不生效，查 MMIO 地址和 AXI write strobe。
- 需要更强调试时，先加 single-step 和 trace snapshot，而不是把普通访存转发给 PS 软件。
