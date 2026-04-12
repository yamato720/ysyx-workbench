# 实现 SYS_yield 与 SYS_exit 系统调用

## 目标

让 dummy 程序能够通过系统调用与 Nanos-lite 通信，最终输出 `HIT GOOD TRAP`。

---

## 问题所在：GPR 宏指向了错误的寄存器

系统调用的参数和返回值通过寄存器传递，AM 用宏抽象这些寄存器。  
修改前，`abstract-machine/am/include/arch/riscv.h` 中：

```c
// 修改前（错误）
#define GPR2 gpr[0]   // x0 = zero，永远是 0！
#define GPR3 gpr[0]
#define GPR4 gpr[0]
#define GPRx gpr[0]   // 返回值也写到 x0？x0 是硬连线 0，写了也没用
```

这意味着 nanos-lite 的 `do_syscall()` 读不到参数，写不了返回值。

---

## RISC-V 系统调用寄存器约定

来自 `navy-apps/libs/libos/src/syscall.c`：

```c
// RISC-V (non-E): ("ecall", "a7", "a0", "a1", "a2", "a0")
//                           GPR1   GPR2  GPR3  GPR4   GPRx(返回值)
```

| 宏 | 寄存器 | ABI 名 | 编号 | 用途 |
|---|---|---|---|---|
| `GPR1` | x17 | a7 | `gpr[17]` | 系统调用号 |
| `GPR2` | x10 | a0 | `gpr[10]` | 第 1 个参数 |
| `GPR3` | x11 | a1 | `gpr[11]` | 第 2 个参数 |
| `GPR4` | x12 | a2 | `gpr[12]` | 第 3 个参数 |
| `GPRx` | x10 | a0 | `gpr[10]` | 返回值（覆盖写回 a0） |

---

## 修改 1：修正 GPR 宏

**文件**：`abstract-machine/am/include/arch/riscv.h`

```c
// 修改后（正确）
#ifdef __riscv_e
#define GPR1 gpr[15] // a5: syscall number
#else
#define GPR1 gpr[17] // a7: syscall number
#endif

#define GPR2 gpr[10] // a0: arg0
#define GPR3 gpr[11] // a1: arg1
#define GPR4 gpr[12] // a2: arg2
#define GPRx gpr[10] // a0: return value
```

`GPR2` 与 `GPRx` 指向同一个寄存器 `a0`：读时是参数，写时是返回值。这是 RISC-V syscall ABI 的标准做法，与 Linux 完全相同。

---

## 修改 2：实现 SYS_yield 和 SYS_exit

**文件**：`nanos-lite/src/syscall.c`

```c
void do_syscall(Context *c) {
  uintptr_t a[4];
  a[0] = c->GPR1; // 系统调用号（a7）
  a[1] = c->GPR2; // 参数 0（a0）
  a[2] = c->GPR3; // 参数 1（a1）
  a[3] = c->GPR4; // 参数 2（a2）

  switch (a[0]) {
    case SYS_yield:
      yield();        // 调用 CTE 的 yield()，让出 CPU
      c->GPRx = 0;    // 返回值 0 写回 a0
      break;
    case SYS_exit:
      halt(a[1]);     // 用退出码调用 halt()，结束仿真
      break;
    default: panic("Unhandled syscall ID = %lu", a[0]);
  }
}
```

---

## 完整调用链

### SYS_yield（系统调用号 = 1）

```
dummy.c: _syscall_(SYS_yield=1, 0, 0, 0)
  │ a7=1, a0=0, a1=0, a2=0
  ▼ ecall → mcause=11
__am_asm_trap → __am_irq_handle
  │ GPR1(a7)=1 ≠ -1 → EVENT_SYSCALL，mepc+=4
  ▼
nanos-lite do_event → do_syscall(c)
  │ a[0]=1=SYS_yield
  ▼
yield()（AM 的 yield，再次触发 ecall，a7=-1 → EVENT_YIELD，OS 调度）
c->GPRx = 0  （返回值 0 写入 a0）
  │
  ▼ mret → 回到 dummy 的 _syscall_() 之后
_syscall_() 从 a0 读出返回值 0，返回给 dummy main()
```

### SYS_exit（系统调用号 = 0）

```
dummy main() 返回 0
  │
call_main: exit(main(...)) → _exit(0) → _syscall_(SYS_exit=0, 0, 0, 0)
  │ a7=0, a0=0
  ▼ ecall → mcause=11
__am_irq_handle: GPR1(a7)=0 ≠ -1 → EVENT_SYSCALL
  ▼
do_syscall: a[0]=0=SYS_exit → halt(a[1]=0)
  ▼
NEMU: HIT GOOD TRAP（退出码 0）
```

---

## 运行结果验证

```
Raising interrupt NO = 11, epc = 0x83000148   ← dummy 的 SYS_yield ecall
Raising interrupt NO = 11, epc = 0x80000948   ← OS 内部 yield() 的 ecall（a7=-1）
Raising interrupt NO = 11, epc = 0x8300015c   ← dummy 的 SYS_exit ecall
nemu: HIT GOOD TRAP at pc = 0x800004d4
```

三条 `Raising interrupt NO = 11` 对应三次 M-mode ecall（mcause=11）：

| 序号 | epc | 来源 | a7 | 事件 |
|---|---|---|---|---|
| 1 | `0x83000148` | dummy `_syscall_(SYS_yield)` | 1 | EVENT_SYSCALL |
| 2 | `0x80000948` | nanos-lite `yield()` | -1 | EVENT_YIELD |
| 3 | `0x8300015c` | dummy `_syscall_(SYS_exit)` | 0 | EVENT_SYSCALL |

---

## SYS_yield 到底调用了什么

`SYS_yield` 系统调用处理里调用的是 **AM 的 `yield()`**（而非 Linux 的 `sched_yield`）。

```c
// abstract-machine/am/src/riscv/nemu/cte.c
void yield() {
  asm volatile("li a7, -1; ecall");  // a7=-1，专门标记为"OS 主动让权"
}
```

这再次产生一个 ecall，但 `a7=-1` 使 `__am_irq_handle` 走 `EVENT_YIELD` 分支（而非 `EVENT_SYSCALL`），从而触发调度器（`do_event` → `EVENT_YIELD` 分支）。

所以 dummy 的 `SYS_yield` 经历了两层 ecall：
1. 用户程序 → OS（`SYS_yield`，a7=1）
2. OS 内部 → CTE（`yield()`，a7=-1）

---

## 为什么 SYS_exit 用 halt() 而不是真正退出进程

目前 Nanos-lite 还没有实现完整的进程管理（fork/exec/wait）。  
`halt(exit_code)` 是 AM 提供的最底层终止原语，直接让 NEMU 停止仿真并报告退出码。  
之后实现了完整进程管理后，`SYS_exit` 应改为终止当前进程、调度下一个进程。

---

## 参考文件

- `abstract-machine/am/include/arch/riscv.h` — GPR? 宏定义（已修正）
- `nanos-lite/src/syscall.c` — `do_syscall()` 实现
- `nanos-lite/src/syscall.h` — 系统调用号枚举（SYS_exit=0, SYS_yield=1, ...）
- `navy-apps/libs/libos/src/syscall.c` — 用户侧 `_syscall_()` 实现，定义了寄存器约定
- `abstract-machine/am/src/riscv/nemu/cte.c` — `yield()`、`__am_irq_handle()`
