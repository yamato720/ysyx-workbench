# NPC + ZCU102 调试器第一版

本文定义第一版 `npc/chisel` CPU 接入 ZCU102 的调试器方案。目标是保持职责分离：

```text
npc/chisel
  - 继续维护 CPU pipeline / CSR / AXI 访问逻辑
  - 提供 FPGA 可综合 export top
  - 提供 commit/debug 信号

zcu102-runtime
  - 提供 ZCU102 板级整合
  - 提供 PS ARM 控制寄存器
  - 提供 guest memory / MMIO / trace buffer
  - 提供 PS 侧调试软件协议
  - 优先使用 Vivado IP 连接 PS、AXI、BRAM、DDR、ILA
```

第一版先做“能上板调试”的最小闭环，不追求完整 `ysyxSoC` 外设。

---

## 1. 当前 NPC 状态

`npc/chisel/src/main/scala/top.scala` 当前 `CPU` 顶层内部已经实例化：

```text
IFetchAXIAdapter
LSUAXIAdapter
AxiLiteDpiRamSlave
AxiLiteCrossbar
AxiLiteDpiMmioSlave
```

这说明 CPU 内部已经有 AXI-Lite 化的取指和访存边界，但这些 AXI master/slave 连接被封在 `CPU` 模块内部。对 ZCU102 来说，DPI RAM/MMIO 不可综合，且外部无法接 BRAM/DDR/PS 调试器。

因此 NPC 侧需要新增一个 FPGA export top，而不是直接使用现有仿真 `CPU` 顶层。

---

## 2. NPC 侧需要提供的 export top

建议在 `npc/chisel` 后续新增：

```text
NPCFPGACore
  - 不实例化 DPI RAM
  - 不实例化 DPI MMIO
  - 暴露 instruction AXI-Lite master
  - 暴露 data AXI-Lite master
  - 暴露 commit/debug signals
  - 接收 run/halt/single_step 控制
```

建议接口：

```text
clock
reset

debug_ctrl:
  run
  reset_cpu
  halt_req
  single_step
  trace_enable

debug_status:
  running
  halted
  busy
  trap_valid
  trap_cause

i_axi: AXI4-Lite master, read-only
d_axi: AXI4-Lite master, read/write

commit:
  valid
  pc
  inst
  rd
  rd_wen
  rd_wdata
  mem_valid
  mem_addr
  mem_wdata
  mem_rdata
  mem_wstrb
```

### 2.1 现有 debug 信号可复用

当前 `CPU(Debug = true)` 已经有：

```text
regs_debug
tick_*_debug
pc
busy
instruction
opcode_out
alu_result_out
mem_result_out
```

这些可以作为第一版 trace/debug 的来源。但更理想的是 NPC 侧显式新增 commit bundle，避免 ZCU102 runtime 猜测流水线阶段语义。

---

## 3. ZCU102 调试器组成

```text
ZCU102NPCDebugger
  + control/status AXI-Lite slave
  + NPC reset/run/single-step controller
  + i_axi/d_axi memory-MMIO fabric
  + guest BRAM or DDR window
  + simple MMIO devices
  + putch FIFO
  + commit trace ring buffer
  + optional PS interrupt
```

当前仓库已放入第一版 RTL 控制块：

```text
zcu102-runtime/rtl/ZCU102NPCDebugger.sv
```

它实现 PS AXI-Lite control/status registers、NPC run/reset/single-step/trace 控制、last commit 寄存器、putch/exit/halt 状态锁存和 `irq_to_ps`。它暂不实现 guest memory、simple MMIO AXI slave 或 trace RAM。

Vivado 集成时，通用基础设施优先使用 IP：

```text
Zynq UltraScale+ MPSoC
AXI SmartConnect
AXI BRAM Controller
Block Memory Generator
Processor System Reset
AXI Data Width Converter
AXI Clock Converter
DDR4 MIG
ILA / VIO
```

更完整的 IP 规划见 `vivado-ip-plan.md`。

### 3.1 PS ARM 访问路径

PS 通过 AXI-Lite 访问 debugger registers：

```text
PS ARM
  -> ZynqMP M_AXI_HPM / GP style port
  -> AXI SmartConnect
  -> ZCU102NPCDebugger control regs
```

PS 负责：

- 写 guest image；
- 写 `BOOT_PC`；
- 拉 reset；
- 设置 run；
- 轮询 halt/exit_code；
- 读取 putch FIFO；
- dump trace；
- 可选运行 NEMU 离线比对。

### 3.2 NPC CPU 访问路径

NPC 通过 i_axi/d_axi 访问 runtime fabric：

```text
NPC i_axi -> AXI SmartConnect -> AXI BRAM Controller / DDR
NPC d_axi -> AXI SmartConnect -> AXI BRAM Controller / DDR or simple MMIO glue
```

普通取指/load/store 不经过 PS 软件。

---

## 4. 地址映射

第一版建议：

```text
NPC 视角：
  0x8000_0000 ~ 0x800f_ffff  guest BRAM
  0xa000_0000 ~ 0xa000_0fff  simple MMIO

PS 视角：
  DBG_BASE + 0x0000 control/status regs
  DBG_BASE + 0x1000 putch FIFO window
  DBG_BASE + 0x2000 trace control
  DBG_BASE + 0x4000 guest BRAM aperture
```

如果 guest memory 放 PS DDR 或 PL DDR，`guest BRAM aperture` 可以替换为 DDR 初始化路径。

---

## 5. 调试寄存器第一版

```text
0x000 CONTROL
  bit 0  run
  bit 1  reset_cpu
  bit 2  halt_req
  bit 3  single_step
  bit 4  trace_enable
  bit 5  clear_trace
  bit 6  clear_putch

0x004 STATUS
  bit 0  running
  bit 1  halted
  bit 2  busy
  bit 3  trap_valid
  bit 4  trace_full
  bit 5  putch_valid

0x008 BOOT_PC
0x00c EXIT_CODE
0x010 CYCLE_LOW
0x014 CYCLE_HIGH
0x018 INSTRET_LOW
0x01c INSTRET_HIGH

0x020 LAST_COMMIT_PC
0x024 LAST_COMMIT_INST
0x028 LAST_COMMIT_RD
0x02c LAST_COMMIT_RD_WDATA_LOW
0x030 LAST_COMMIT_RD_WDATA_HIGH

0x040 TRACE_HEAD
0x044 TRACE_TAIL
0x048 TRACE_COUNT
0x04c TRACE_BASE
0x050 TRACE_SIZE

0x060 PUTCH_DATA
0x064 PUTCH_STATUS
```

---

## 6. Simple MMIO 第一版

NPC 程序可访问：

```text
0xa000_0000 PUTCH
  write byte -> putch FIFO

0xa000_0004 HALT
  write 1 -> halt

0xa000_0008 EXIT_CODE
  write value -> exit_code register

0xa000_0010 TIMER_LOW
0xa000_0014 TIMER_HIGH
```

这组地址保留 NEMU/AM 风格，方便现有测试迁移。

---

## 7. Trace entry

第一版固定 64 bytes，便于 PS 侧按 cache line 或 burst 读取：

```text
offset  size  field
0x00    8     pc
0x08    4     inst
0x0c    4     flags
0x10    8     rd_wdata
0x18    4     rd
0x1c    4     trap_cause
0x20    8     mem_addr
0x28    8     mem_wdata
0x30    8     mem_rdata
0x38    4     mem_wstrb
0x3c    4     reserved
```

`flags` 建议：

```text
bit 0 commit_valid
bit 1 rd_wen
bit 2 mem_valid
bit 3 mem_write
bit 4 trap
bit 5 halt
```

---

## 8. Bring-up 流程

```text
1. PS assert reset_cpu
2. PS writes image to guest memory
3. PS writes BOOT_PC = 0x80000000
4. PS clears trace/putch/status
5. PS deassert reset_cpu and sets run
6. NPC fetches from guest memory
7. NPC writes PUTCH/HALT/EXIT_CODE
8. PS polls STATUS.halted
9. PS dumps trace and optionally runs NEMU compare
```

---

## 9. 第一版不做什么

- 不把 NEMU 搬进 PL；
- 不让 PS 软件处理普通取指/load/store；
- 不一次接完整 `ysyxSoC` 外设；
- 不做实时高性能 DiffTest；
- 不先做 Linux boot；
- 不先做 cache coherency。

---

## 10. 后续落地顺序

1. NPC 侧拆出 `NPCFPGACore`，去掉 DPI RAM/MMIO。
2. Vivado block design 接 ZynqMP PS、SmartConnect、Processor System Reset。
3. ZCU102 侧接入 `ZCU102NPCDebugger.sv` control/status regs。
4. 用 AXI BRAM Controller + Block Memory Generator 实现 guest BRAM。
5. 用 simple MMIO glue 接 PUTCH/HALT/EXIT_CODE。
6. 接 commit trace ring。
7. PS bare-metal 程序完成 load/run/poll/dump。
8. 再把 guest memory 换成 PS DDR 或 PL DDR4 MIG。
