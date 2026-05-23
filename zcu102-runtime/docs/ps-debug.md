# PS ARM 调试与 NEMU 协作模型

ZCU102 的 ZynqMP PS 提供 ARM A53/R5 核。它们很适合承担 host/debug controller 角色，但不应该逐周期推动 PL 里的 RISC-V CPU。

## 1. 与当前 `.so` 仿真模式的区别

当前 `npc/chisel` 可以通过 Verilator/DPI 打成 `.so`，由 NEMU 或 host 程序在软件中推动。这种模式下 CPU 是软件仿真对象：

```text
NEMU / host process
  -> call verilated model
  -> provide memory/MMIO through DPI/C++
  -> collect state
```

上 ZCU102 后，CPU 在 PL 中运行：

```text
PL RISC-V CPU
  -> fetch/load/store through hardware AXI
  -> execute every cycle in FPGA fabric
  -> expose debug/status/trace to PS
```

因此 `.so` 不能直接作为上板运行模型。NEMU 仍然有价值，但应作为参考模型、trace checker 或 host-side debugger。

## 2. 推荐职责划分

### 2.1 PS ARM 负责

- 加载 `image.bin` 到 BRAM、PS DDR 或 PL DDR；
- 配置 `boot_pc`；
- 控制 CPU reset/run/halt；
- 轮询或处理中断形式的 `halt`、`exit_code`、`putch`；
- 读取 trace ring buffer；
- 导出 trace 到 host PC；
- 可选运行 aarch64 版 NEMU 做离线或低速分段 DiffTest；
- 通过 AXI-Lite 写 debug control registers。

### 2.2 PL 负责

- CPU 每周期真实执行；
- 普通取指、load、store 本地访问 BRAM/DDR；
- simple MMIO 本地处理；
- 生成 commit trace；
- 在 halt、trace buffer almost full、复杂 MMIO request 时通知 PS；
- 提供 ILA 观测点。

## 3. 不推荐的做法

不要让 PS ARM/NEMU 处理每一次普通内存访问：

```text
CPU fetch/load/store
  -> trap/request to PS
  -> ARM software handles
  -> response to PL
```

这条路径延迟太高，只适合低频 debug 或复杂外设模拟。如果普通访存走 PS 软件，硬件 CPU 的吞吐会退化到协同仿真级别。

## 4. 推荐调试寄存器

AXI-Lite control window 建议包含：

```text
CONTROL
  bit 0  run
  bit 1  reset_cpu
  bit 2  single_step
  bit 3  trace_enable
  bit 4  clear_trace

STATUS
  bit 0  running
  bit 1  halted
  bit 2  trace_full
  bit 3  mmio_req_pending

BOOT_PC
EXIT_CODE
CYCLE_LOW / CYCLE_HIGH
INSTRET_LOW / INSTRET_HIGH
TRACE_BASE
TRACE_SIZE
TRACE_HEAD
TRACE_TAIL
PUTCH_DATA
PUTCH_STATUS
```

## 5. 推荐硬件调试信号

PL runtime 或 CPU wrapper 建议暴露：

```text
cpu_halt_req
cpu_halted
single_step
commit_valid
commit_pc
commit_inst
commit_rd
commit_rd_wen
commit_rd_wdata
mem_valid
mem_addr
mem_wdata
mem_rdata
mem_wstrb
trap_valid
trap_cause
```

这些信号可以写入 trace buffer，也可以接 ILA。

## 6. NEMU 协作方式

### 6.1 离线 DiffTest

```text
PL CPU runs program
  -> writes commit trace to DDR/BRAM ring
  -> PS dumps trace
  -> NEMU replays same image
  -> compare pc/inst/rd/rd_wdata
```

优点：

- 不影响 PL CPU 高频路径；
- 可以批量定位 first mismatch；
- PS Linux 或 host PC 都能执行。

### 6.2 分段低速检查

```text
run N instructions
  -> pause CPU
  -> PS reads architectural snapshot / trace tail
  -> NEMU advances same N instructions
  -> compare
  -> resume
```

这比每条指令实时 DiffTest 简单，但需要 CPU wrapper 支持 pause/snapshot。

### 6.3 实时逐条检查

只建议作为后期 debug 模式：

```text
single_step CPU
  -> PS reads commit
  -> NEMU step
  -> compare
```

这种模式非常慢，但适合定位上板早期的 first instruction / first branch / first load-store 问题。

## 7. Bring-up 建议

第一版只实现：

```text
PS writes image
PS releases reset
PL CPU runs from BRAM
PL writes putch/halt/exit_code
PS polls and prints result
```

第二版再加：

```text
trace ring buffer
PS dump trace
host or PS NEMU offline compare
```

第三版再考虑：

```text
single_step
breakpoint
watchpoint
snapshot compare
PS interrupt notification
```
