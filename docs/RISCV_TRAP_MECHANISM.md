# RISC-V Trap 全景：从一条 ecall 到多种事件类型

## 一、核心概念：Trap、Exception、Interrupt 的区分

RISC-V 规范用 **trap** 作为总称，分两类：

| 类型 | 英文 | mcause 最高位 | 触发方式 |
|---|---|---|---|
| **异常** | Exception | `0` | 同步，由当前指令执行直接引发 |
| **中断** | Interrupt | `1` | 异步，来自外部事件（定时器、外设等） |

```
mcause (64-bit):
┌─────────────────────────────────────────────────────────────────┐
│ 63: interrupt bit │ 62..0: exception/interrupt code             │
└─────────────────────────────────────────────────────────────────┘

异常示例：mcause = 11  (0x000...000B) → M-mode ecall
中断示例：mcause = 0x800...0007     → M-mode timer interrupt
```

---

## 二、硬件发生 Trap 时，CPU 自动做的事

任何 trap（无论是 ecall、缺页、ebreak 还是外设中断）触发时，硬件原子地完成：

```
1. mcause  ← trap 原因编号（见下表）
2. mepc    ← 引发 trap 的指令 PC（中断时是被打断的 PC）
3. mstatus.MPIE ← mstatus.MIE（保存当前中断使能位）
4. mstatus.MIE  ← 0（关闭中断，防止嵌套）
5. mstatus.MPP  ← 当前特权级（M/S/U）
6. PC       ← mtvec（trap 处理入口，由软件提前写入）
```

返回时，`mret` 指令还原：PC ← mepc，特权级还原，MIE 还原。

---

## 三、完整的 RISC-V mcause 表

### 同步异常（最高位 = 0）

| mcause | 名称 | 触发指令/场景 |
|---|---|---|
| 0 | Instruction address misaligned | 跳转到非对齐地址 |
| 1 | Instruction access fault | 取指访问无效物理地址 |
| 2 | Illegal instruction | 未定义指令、CSR 权限不够 |
| **3** | **Breakpoint** | **`ebreak` 指令** |
| 4 | Load address misaligned | 非对齐 load |
| 5 | Load access fault | Load 访问无效物理地址 |
| 6 | Store/AMO address misaligned | 非对齐 store |
| 7 | Store/AMO access fault | Store 访问无效物理地址 |
| 8 | Environment call from U-mode | U 态 `ecall` |
| 9 | Environment call from S-mode | S 态 `ecall` |
| **11** | **Environment call from M-mode** | **M 态 `ecall`（本项目用此值）** |
| 12 | Instruction page fault | 取指缺页（Sv39/Sv48 虚存） |
| 13 | Load page fault | Load 缺页 |
| 15 | Store/AMO page fault | Store 缺页 |

### 异步中断（最高位 = 1）

| mcause | 名称 | 来源 |
|---|---|---|
| 0x8...3 | M-mode software interrupt | MSIP（核间中断） |
| 0x8...7 | M-mode timer interrupt | MTIME 定时器 |
| 0x8...B | M-mode external interrupt | PLIC（外设，如 UART、键盘） |
| 0x8...1 | S-mode software interrupt | SSIP |
| 0x8...5 | S-mode timer interrupt | STIP |
| 0x8...9 | S-mode external interrupt | SEIP |

---

## 四、ecall 是如何被解释成多种语义的

`ecall` 本身只是"请求上一级特权模式提供服务"，**mcause 固定为 11**（M 态）。  
区分"要做什么"完全靠软件约定——通常用寄存器传参。

### 4.1 本项目的两个层次

```
层次 1：AM 的 yield()          → OS/bare-metal 内部使用
层次 2：libos 的 _syscall_()   → 用户程序调用 OS 服务
```

两者都发出 `ecall`，触发同一个 trap handler（`__am_asm_trap` → `__am_irq_handle`），
但靠 **`a7` 寄存器的值**区分语义：

### 4.2 分发逻辑（`abstract-machine/am/src/riscv/nemu/cte.c`）

```c
// __am_irq_handle 中：
switch (c->mcause) {
  case 11:  // M-mode ecall
    if (c->GPR1 == (uintptr_t)-1) {
      ev.event = EVENT_YIELD;    // a7 = -1
    } else {
      ev.event = EVENT_SYSCALL;  // a7 = 任意系统调用号
    }
    c->mepc += 4;  // 跳过 ecall 指令，继续执行
    break;
  case 12: ev.event = EVENT_IRQ_TIMER;  break;
  case 13: ev.event = EVENT_IRQ_IODEV;  break;
  default: ev.event = EVENT_ERROR;      break;
}
```

> 注意：**mepc += 4** 只对 ecall（同步异常）做，中断不需要（中断 mepc 指向被打断的指令，返回后重新执行）。

---

## 五、各场景完整调用链

### 5.1 AM 的 `yield()`：主动让权

```
yield() 调用者
  │
  ▼
asm volatile("li a7, -1; ecall")      ← a7 = -1
  │  mcause = 11（M-mode ecall）
  ▼
mtvec → __am_asm_trap                 ← 保存所有寄存器到栈上的 Context
  │
  ▼
__am_irq_handle(c)
  │  c->mcause == 11 && c->GPR1 == -1
  ▼
ev.event = EVENT_YIELD
user_handler(ev, c)                   ← 调用 nanos-lite 的 do_event()
  │  调度器换上下文（切换 PCB）
  ▼
mret                                  ← 从新 Context 的 mepc 恢复执行
```

### 5.2 `dummy.c` 的 `_syscall_(SYS_yield=1, ...)`：系统调用

```
dummy.c: _syscall_(1, 0, 0, 0)
  │
  ▼
libos/_syscall_():
  register intptr_t _gpr1 asm("a7") = 1;   ← a7 = 1（SYS_yield）
  asm volatile("ecall")
  │  mcause = 11
  ▼
mtvec → __am_asm_trap → __am_irq_handle
  │  c->mcause == 11 && c->GPR1 == 1（≠ -1）
  ▼
ev.event = EVENT_SYSCALL
nanos-lite do_event() → do_syscall(c)
  │  a[0] = c->GPR1 = 1 = SYS_yield
  ▼
case SYS_yield: yield(); c->GPRx = 0;      ← 调用 AM yield，返回值写 a0
```

### 5.3 `ebreak`：断点（GDB 调试 / NEMU 程序结束）

```c
// nemu/src/isa/riscv64/inst.c:
INSTPAT("0000000 00001 00000 000 00000 11100 11", ebreak, N,
        NEMUTRAP(s->pc, R(10)));  // R(10) = a0 = 退出码

// nemu/include/cpu/cpu.h:
#define NEMUTRAP(thispc, code)  set_nemu_state(NEMU_END, thispc, code)
```

- NEMU 中：`ebreak` 不走 mtvec，直接被 NEMU 模拟器截获，结束仿真（`NEMU_END`）。  
- 真实硬件上：`ebreak` → `mcause = 3`（Breakpoint）→ 进入 trap handler。  
- GDB 调试时：GDB 把目标地址的指令替换为 `ebreak`；程序执行到此 → mcause=3 → 通知 GDB。

### 5.4 外设中断（External Interrupt）

```
外设（UART/键盘/网卡）产生信号
  │
  ▼
PLIC（Platform-Level Interrupt Controller）仲裁
  │  选出优先级最高的中断，通过 meip 信号通知 CPU
  ▼
CPU 在当前指令完成后检测 mstatus.MIE && mip.MEIP
  │  mcause = 0x800...000B（M-mode external interrupt）
  ▼
mtvec → __am_asm_trap → __am_irq_handle
  │  c->mcause 最高位为 1 → 中断分支
  ▼
ev.event = EVENT_IRQ_IODEV
do_event() 处理 I/O 事件（读取设备数据、唤醒等待进程）
  │
  ▼
mret → 回到被打断的指令继续执行
```

本项目 NEMU 中 `isa_query_intr()` 返回 `INTR_EMPTY`，外设中断尚未完整实现。

### 5.5 缺页异常（Page Fault）

仅在开启虚存（Sv39/Sv48，`satp` CSR 非零）时有效：

```
load/store/取指  访问虚地址 VA
  │
  ▼
MMU 查页表
  │  PTE 不存在 / 无权限 / dirty bit 未置
  ▼
mcause = 12（取指缺页）/ 13（Load 缺页）/ 15（Store 缺页）
  │  mtval = 发生缺页的虚地址
  ▼
OS page fault handler：
  1. 找到对应 VMA（虚拟内存区域）
  2. 分配物理页、建立映射（demand paging）
  3. mret 回到原指令重新执行
```

本项目 NEMU 的 `isa_mmu_translate()` 当前返回 `MEM_RET_FAIL`，虚存功能未启用。

---

## 六、Cache Miss——不是中断

> **Cache miss 对软件完全透明，不产生任何 trap 或中断。**

CPU 访问内存时若 cache 未命中：
1. 硬件自动发起 cache line fill（从下一级 cache 或内存读取数据）
2. CPU stall（流水线暂停等待）
3. 数据就绪后继续执行

软件看到的只是"这条 load/store 慢了一些"，看不到任何 mcause 变化。  
（若发生 cache line fill 时触发了 ECC 错误，部分实现会产生 Machine Check Exception，但这属于硬件故障处理，不是正常的 cache miss。）

---

## 七、本项目的 Trap 处理全景图

```
任何 trap 触发
      │
      ▼
__am_asm_trap（trap.S）
  ① addi sp, -CONTEXT_SIZE    保存 32 个通用寄存器
  ② csrr: mcause/mstatus/mepc  读取 CSR 并存入 Context
  ③ mv a0, sp; call __am_irq_handle  以 Context* 为参数调用 C 处理函数
  ④ 恢复 mstatus/mepc
  ⑤ 恢复 32 个通用寄存器
  ⑥ mret

__am_irq_handle（cte.c）
      │
      ├─ mcause == 11 ─── GPR1(a7) == -1 ──→ EVENT_YIELD
      │                └─ GPR1(a7) != -1 ──→ EVENT_SYSCALL  （mepc+=4）
      │
      ├─ mcause == 12 ───────────────────→ EVENT_IRQ_TIMER
      ├─ mcause == 13 ───────────────────→ EVENT_IRQ_IODEV
      └─ 其他 ────────────────────────→ EVENT_ERROR

user_handler（nanos-lite/src/irq.c 的 do_event）
      │
      ├─ EVENT_YIELD   → 调度器切换上下文
      ├─ EVENT_SYSCALL → do_syscall(c)  按 a7 分发 SYS_xxx
      └─ EVENT_IRQ_IODEV → 处理设备 I/O
```

---

## 八、关键寄存器速查

| CSR | 作用 |
|---|---|
| `mtvec` | trap 入口地址（`cte_init` 时写入 `__am_asm_trap`） |
| `mcause` | trap 原因（最高位区分中断/异常，低位是编号） |
| `mepc` | 触发 trap 的 PC（`mret` 后跳回此处） |
| `mstatus` | 全局中断使能（MIE）、之前的特权级（MPP）等 |
| `mtvec` | trap 向量基址（Direct 模式：所有 trap 跳同一入口） |
| `mtval` | 附加信息（缺页时存虚地址，非法指令时存指令编码） |
| `mip` | 待处理中断位（MEIP/MTIP/MSIP） |
| `mie` | 中断使能掩码（按类型独立开关） |

---

## 参考

- RISC-V Privileged Architecture Specification, Chapter 3 (Machine-Level ISA)
- `abstract-machine/am/src/riscv/nemu/cte.c` — `yield()`, `__am_irq_handle()`
- `abstract-machine/am/src/riscv/nemu/trap.S` — `__am_asm_trap`
- `navy-apps/libs/libos/src/syscall.c` — `_syscall_()`
- `nemu/src/isa/riscv64/system/intr.c` — `isa_raise_intr()`
- `nanos-lite/src/irq.c` — `do_event()`
