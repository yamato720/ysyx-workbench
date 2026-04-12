# RISC-V 中断/异常处理：标准约定 vs NEMU 实现对比

---

## 0. 术语约定

| 术语 | 含义 |
|------|------|
| **异常（exception）** | 由指令同步触发（ecall、非法指令、缺页等）|
| **中断（interrupt）** | 外部异步触发（计时器、外设、PLIC 等）|
| **trap** | 两者的统称，进入 mtvec 的统一入口 |
| **mcause 最高位** | 1 = interrupt，0 = exception |
| **NEMU** | 本项目的软件模拟器，PA3 教学版 |
| **标准/Linux** | 真实 RISC-V 硬件 + Linux 内核约定 |

---

## 1. 触发机制

### 1.1 ecall / 系统调用

| | 标准 RISC-V（Linux 内核） | NEMU（PA3） |
|-|--------------------------|------------|
| 触发指令 | `ecall`（一条指令）| 同，`ecall` |
| 类型码 | **a7**（gpr[17]）由调用者设置，约定系统调用号 | 目前只有 `a7 = -1` 表示 yield；硬件固定 `mcause = 11` |
| mcause | 8（U-mode）/ 9（S-mode）/ 11（M-mode）| **仅 M-mode**，固定 11 |
| 如何区分 yield vs syscall | 读 a7（gpr[17]）| 目前用 mcause=11 统一当 yield；未来读 `c->gpr[17]` 区分 |

### 1.2 外部中断（计时器、外设）

**标准硬件路径：**

```
CLINT（计时器）/ PLIC（外设）
  → 硬件置位 mip.MTIP / mip.MEIP
  → CPU 检查 mstatus.MIE && mie.MTIE/MEIE
  → 满足条件：下一条指令前硬件跳转 mtvec
  → mcause = 0x8000000000000007（timer）/ 0x800000000000000B（external）
                 ↑ 最高位 = 1，表示 interrupt
```

**NEMU 路径（宿主机信号驱动）：**

```
宿主机 SIGVTALRM（每隔 1/TIMER_HZ 触发一次）
  → alarm_sig_handler()              [nemu/src/device/alarm.c]
    → timer_intr()                   [nemu/src/device/timer.c]
      → dev_raise_intr()             [nemu/src/device/intr.c]
        （当前为空函数，可扩展）

cpu-exec.c::execute() 每条指令执行后：
  → device_update()                  [nemu/src/device/device.c]
    （轮询时间差，处理 SDL 事件）
    （目前 timer/iodev 中断未真正注入 mcause；
     mcause=12/13 为 cte.c 预留，需配合 device 层扩展）
```

> **关键差异**：
> - 标准硬件：CPU 核心每条指令结束后检查 `mip & mie`，满足才跳转，完全硬件自动；
> - NEMU：用宿主机 `SIGVTALRM` + `device_update()` 轮询模拟异步事件，`dev_raise_intr()` 目前是空函数，中断真正注入需要调用 `isa_raise_intr(12/13, pc)`。

---

## 2. 上下文保存（进入 trap 时）

### 2.1 标准 RISC-V 硬件自动完成

```
硬件（每条指令结束后）：
  mepc    ← 被打断的指令地址（同步异常）或下一条指令地址（异步中断）
  mcause  ← 原因码（最高位区分中断/异常）
  mstatus.MPIE ← mstatus.MIE（保存旧中断使能位）
  mstatus.MPP  ← 当前特权级
  mstatus.MIE  ← 0（**关闭全局中断**，防止嵌套）
  PC      ← mtvec（直接模式）或 mtvec + cause*4（向量模式）
```

**然后软件（内核 trap 入口汇编）保存剩余寄存器**：

```asm
# Linux arch/riscv/kernel/entry.S（简化版）
_trap:
  csrrw  s0, sscratch, sp        # 交换 sp 与 sscratch（找到内核栈）
  sd     ra,  0(sp)
  sd     sp,  8(sp)              # 保存用户 sp（从 sscratch 读）
  sd     gp, 16(sp)
  # ... 保存全部 32 个通用寄存器 ...
  csrr   t0, sepc
  sd     t0, 256(sp)             # 保存 sepc → pt_regs.epc
  # ... 保存 sstatus, stval, scause ...
  call   do_trap                 # 跳入 C 分发函数
```

### 2.2 NEMU 的实现

NEMU 是软件模拟器，没有真正的"硬件自动"，ecall 的 CSR 赋值**由 `isa_raise_intr()` 模拟**：

```c
// nemu/src/isa/riscv64/system/intr.c
word_t isa_raise_intr(word_t NO, vaddr_t epc) {
  cpu.mcause = NO;     // 模拟硬件写 mcause
  cpu.mepc   = epc;    // 模拟硬件写 mepc
  return cpu.mtvec;    // 返回值被赋给 s->dnpc（模拟 PC ← mtvec）
}

// nemu/src/isa/riscv64/inst.c
INSTPAT("... ecall ...", ecall, N,
  s->dnpc = isa_raise_intr(11, s->pc));  // ecall 指令的"执行"
```

接下来 NEMU 以 `s->dnpc = mtvec` 跳转到 `__am_asm_trap`，由汇编完成剩余保存（与硬件行为一致）：

```asm
# abstract-machine/am/src/riscv/nemu/trap.S
__am_asm_trap:
  addi sp, sp, -CONTEXT_SIZE   # 分配 Context 空间（280 字节，rv64）
  MAP(REGS, PUSH)               # 保存 31 个通用寄存器
  csrr t0, mcause               # 读已由 isa_raise_intr 写好的值
  csrr t1, mstatus
  csrr t2, mepc
  sd t0, OFFSET_CAUSE(sp)       # 存入 Context
  sd t1, OFFSET_STATUS(sp)
  sd t2, OFFSET_EPC(sp)
  mv a0, sp
  call __am_irq_handle
```

**两者差异**：

| | 标准硬件 | NEMU |
|-|---------|------|
| CSR 赋值 | 电路自动，每周期末 | `isa_raise_intr()` C 函数模拟 |
| `mstatus.MIE` 清零 | 硬件自动（进入 trap 关中断）| **未实现**（NEMU 不模拟中断嵌套控制）|
| `mstatus.MPP/MPIE` | 硬件自动 | mstatus 字段存在但由软件显式 csrw |
| 特权级切换 | 硬件自动（U→M）| NEMU 仅 M-mode，无特权级切换 |
| mepc 含义 | 异步中断=被打断指令的**下一条**；同步异常=触发指令本身 | 同步 ecall：mepc = ecall 的 PC（未 +4，需软件加）|

---

## 3. 事件分发（C 层）

### 3.1 标准 Linux 内核

Linux 使用 S-mode（Supervisor）并通过 `do_trap()` / `handle_exception()` 分发：

```
do_trap(regs, scause, ...)
  → scause 最高位 = 1（interrupt）：
      scause & 0x7f == 5 → 计时器 → scheduler_tick() → schedule()
      scause & 0x7f == 9 → 外设   → handle_irq() → 驱动 ISR
  → 最高位 = 0（exception）：
      scause == 8（U ecall）→ sys_call_table[a7](...) → 系统调用
      scause == 12/13/15   → 缺页处理 → do_page_fault()
      其他 → SIGILL / SIGSEGV → 发信号给进程
```

关键点：**Linux 用 `scause`（S-mode）不用 `mcause`**，通过 PLIC 向量号区分不同外设中断。

### 3.2 NEMU + AM

```c
// abstract-machine/am/src/riscv/nemu/cte.c
Context* __am_irq_handle(Context *c) {
  Event ev = {0};
  switch (c->mcause) {            // 读 trap.S 保存的 mcause
    case 11: ev.event = EVENT_YIELD;     break;  // M ecall → yield
    case 12: ev.event = EVENT_IRQ_TIMER; break;  // 计时器（预留）
    case 13: ev.event = EVENT_IRQ_IODEV; break;  // 外设（预留）
    default: ev.event = EVENT_ERROR;     break;
  }
  c = user_handler(ev, c);        // 回调上层（OS/测试程序）
  return c;
}
```

NEMU 的分发**扁平、简单**：只有一个 `switch(mcause)`，没有向量中断控制器，没有优先级，没有嵌套控制。

---

## 4. 执行（handler 内部）与嵌套中断

### 4.1 标准 Linux 的嵌套中断处理

Linux 支持两层嵌套：

```
进入 trap 时 mstatus.MIE=0（硬件自动关全局中断）
  → 软件完成上下文保存后，可主动重新开中断（`csrsi sstatus, SIE`）
  → 此时若新中断到来，触发新一层 trap（递归调用 do_trap）
  → 每层 trap 使用**独立的内核栈帧**（struct pt_regs 压栈）
  → 嵌套深度由内核栈大小限制，通常允许有限层

抢占调度（preemption）：
  计时器中断 → scheduler_tick() → 检查 need_resched 标志
  → 中断返回前 → preempt_schedule_irq() → 任务切换
```

特权级切换（U-mode 应用 → S-mode 内核）：

```
U-mode ecall
  → 硬件：sepc=ecall PC, scause=8, sstatus.SPP=U, PC=stvec
  → 内核用 sscratch 找到内核栈
  → 保存用户 pt_regs → 执行系统调用 → sret 返回 U-mode
```

### 4.2 NEMU 的现状（PA3 阶段）

```
进入 __am_asm_trap：
  mstatus.MIE ← 未清零（NEMU 不模拟中断使能联锁）
  → 只有 M-mode，无 U/S 特权级，无特权级切换
  → 无 sscratch 交换，sp 直接在当前栈上分配 Context
  → __am_irq_handle 执行时，NEMU 不会再触发新中断
    （因为 NEMU 在 isa_raise_intr 后设置 NEMU_STOP，
     并且 ecall 是同步的，handler 执行完才继续）

嵌套中断：当前 NEMU 不支持
  → 计时器/外设中断（mcause=12/13）尚未真正注入
  → 即使注入，也没有嵌套 Context 保存机制
```

---

## 5. 上下文恢复与返回

### 5.1 标准 RISC-V

```asm
# 从内核 trap handler 返回
  ld  ra,  0(sp)
  # ... 恢复所有寄存器 ...
  ld  t0, 256(sp)
  csrw sepc, t0          # 恢复 sepc
  ld  t0, 264(sp)
  csrw sstatus, t0       # 恢复 sstatus（含 MIE/SPP）
  ld  sp,  8(sp)         # 恢复用户 sp（最后恢复，因为之前用它寻址）
  sret                   # PC ← sepc；特权级 ← sstatus.SPP；MIE ← MPIE
```

`sret/mret` 同时完成三件事：
1. `PC ← sepc`
2. 特权级 ← `sstatus.SPP`（返回触发 trap 时的特权级）
3. `mstatus.MIE ← mstatus.MPIE`（恢复旧中断使能状态）

### 5.2 NEMU + AM

```asm
# abstract-machine/am/src/riscv/nemu/trap.S
  ld t1, OFFSET_STATUS(sp)
  ld t2, OFFSET_EPC(sp)
  csrw mstatus, t1        # 恢复 mstatus（由 trap.S 进入时已保存）
  csrw mepc, t2           # 写回 mepc（user_handler 可能修改了 c->mepc +4）
  MAP(REGS, POP)           # 恢复 31 个通用寄存器
  addi sp, sp, CONTEXT_SIZE
  mret                     # PC ← mepc；符合 RISC-V mret 语义
```

**mepc 是否 +4 的问题**：

| 场景 | mepc 处理 |
|------|----------|
| `yield()`（想循环调用）| 不 +4，mret 回到 ecall，再次触发 → 无限打印 `y` |
| `yield()`（正式 OS，想继续执行） | handler 里 `c->mepc += 4` |
| syscall（执行完系统调用后继续）| handler 里 `c->mepc += 4` |
| 异常（缺页，修复后重试）| 不 +4，mret 重新执行触发异常的指令 |

---

## 6. 完整对比总结

| 维度 | 标准 RISC-V / Linux | NEMU（PA3）|
|------|---------------------|-----------|
| **触发 ecall** | `ecall` 指令；硬件写 scause=8/9/11；a7=系统调用号 | `isa_raise_intr(11, pc)` C 函数模拟；a7=-1 表示 yield |
| **触发 timer** | CLINT 置位 mip.MTIP；CPU 自动检测 | 宿主 SIGVTALRM → `timer_intr()` → `dev_raise_intr()`（空函数）|
| **触发 external** | PLIC 仲裁 → mip.MEIP；CPU 检测 | 未实现 |
| **CSR 保存** | 硬件自动（mepc/mcause/mstatus）| `isa_raise_intr()` 写 mcause/mepc；mstatus 由 trap.S csrw |
| **通用寄存器保存** | 软件（内核汇编 entry.S）| `trap.S::MAP(REGS, PUSH)` |
| **栈切换** | U→M：sscratch 交换找内核栈 | 无，直接在当前 sp 分配 |
| **特权级切换** | U/S/M 三级，sret/mret 自动切换 | 仅 M-mode，无切换 |
| **全局中断关闭** | mstatus.MIE=0（硬件自动）| **未模拟** |
| **嵌套中断** | 支持（软件开 MIE 后可嵌套）| 不支持 |
| **事件分发** | `do_trap()`/向量中断控制器；PLIC 区分外设 | `switch(mcause)` 三个 case |
| **系统调用路由** | `sys_call_table[a7]` 查表 | 无（a7=-1 直接当 yield）|
| **mepc +4** | 软件在系统调用路径显式 +4 | 由 user_handler 决定（目前未 +4）|
| **上下文恢复** | 恢复全部寄存器 + sret | `MAP(REGS, POP)` + mret |
| **任务切换** | `schedule()` 切换 `task_struct`；内核栈切换 | user_handler 返回不同 `Context*`；只有寄存器切换 |

---

## 7. NEMU 各简化点的原因

| 简化 | 原因 |
|------|------|
| 无特权级切换 | PA3 只在 M-mode 运行，无用户态 |
| 无 mstatus.MIE 联锁 | 没有真正的异步中断，ecall 是同步的 |
| dev_raise_intr() 为空 | NEMU timer 用宿主机信号模拟，与被模拟 CPU 的中断线解耦 |
| mcause=12/13 预留 | 为后续扩展（加真正的中断注入）留接口 |
| a7=-1 而非标准 syscall 号 | AM `yield()` 是 CTE 测试，不做真正系统调用分发 |
