# AM CTE RISC-V：Context 结构体与异常处理流程

## 1. 数据结构

### 1.1 Context 结构体（arch/riscv.h）

```c
struct Context {
  uintptr_t gpr[NR_REGS];   // 32 个通用寄存器（x0~x31），逐字存放
  uintptr_t mcause;          // 异常原因 CSR（trap 进入时由硬件写入）
  uintptr_t mstatus;         // 机器状态 CSR
  uintptr_t mepc;            // 异常返回地址 CSR（触发 ecall 的那条指令地址）
  void     *pdir;            // 页目录指针（VME 扩展用，CTE 不使用）
};
```

字段顺序**必须**与 `trap.S` 中定义的偏移量一致：

| 字段       | 相对 sp 偏移                  | trap.S 宏名        |
|-----------|------------------------------|--------------------|
| `gpr[0]`  | `0 * XLEN`                   | —                  |
| `gpr[1]`  | `1 * XLEN`                   | —                  |
| …         | …                            | —                  |
| `gpr[31]` | `31 * XLEN`                  | —                  |
| `mcause`  | `32 * XLEN`（`NR_REGS + 0`） | `OFFSET_CAUSE`     |
| `mstatus` | `33 * XLEN`（`NR_REGS + 1`） | `OFFSET_STATUS`    |
| `mepc`    | `34 * XLEN`（`NR_REGS + 2`） | `OFFSET_EPC`       |

> `XLEN` = 8（rv64）或 4（rv32）；`NR_REGS` = 32 或 16（rv32e）。

---

### 1.2 Event 结构体（am.h）

```c
typedef struct {
  enum {
    EVENT_NULL = 0,
    EVENT_YIELD,       // yield() / ecall（cause==11 M-mode）
    EVENT_SYSCALL,     // 用户态 ecall
    EVENT_PAGEFAULT,   // 缺页
    EVENT_ERROR,       // 未知异常（兜底）
    EVENT_IRQ_TIMER,   // 计时器中断
    EVENT_IRQ_IODEV,   // 外设中断
  } event;
  uintptr_t cause, ref;
  const char *msg;
} Event;
```

---

## 2. 初始化流程：cte_init()

```c
// cte.c
bool cte_init(Context *(*handler)(Event, Context *)) {
  asm volatile("csrw mtvec, %0" : : "r"(__am_asm_trap));  // ①
  user_handler = handler;                                   // ②
  return true;
}
```

| 步骤 | 动作 | 说明 |
|------|------|------|
| ① | `csrw mtvec, __am_asm_trap` | 把汇编入口地址写入 mtvec CSR，CPU 发生 trap 时跳转到该地址 |
| ② | `user_handler = handler` | 保存用户注册的 C 回调，后续在 `__am_irq_handle()` 中调用 |

---

## 3. Trap 完整数据通路

### 3.1 概览（文字流程）

```
程序执行 yield()
  │  asm: li a7, -1; ecall
  │
  ▼
RISC-V 硬件自动完成
  ├─ mcause  ← 11（M-mode environment call）
  ├─ mepc    ← ecall 指令地址（PC）
  ├─ mstatus.MPP ← 当前特权级（M）
  └─ PC      ← mtvec（即 __am_asm_trap）
  │
  ▼
__am_asm_trap（trap.S）—— 保存现场
  ├─ sp -= CONTEXT_SIZE              // 在栈上开辟 Context 大小的空间
  ├─ STORE x1~x31 → sp+n*XLEN       // 依次保存 gpr[1..31]（x0 恒为 0 跳过）
  ├─ csrr t0, mcause                 // 读取 CSR
  ├─ csrr t1, mstatus
  ├─ csrr t2, mepc
  ├─ STORE t0 → sp+OFFSET_CAUSE     // 写入 Context.mcause
  ├─ STORE t1 → sp+OFFSET_STATUS    // 写入 Context.mstatus
  ├─ STORE t2 → sp+OFFSET_EPC       // 写入 Context.mepc
  ├─ mstatus.MPRV = 1（通过 or + csrw 设置，用于 DiffTest）
  └─ a0 = sp → call __am_irq_handle // sp 即 Context*，作为参数传入
  │
  ▼
__am_irq_handle（cte.c）—— 事件分发
  ├─ 读取 c->mcause
  ├─ switch(mcause):
  │    case 11 → ev.event = EVENT_YIELD
  │    default → ev.event = EVENT_ERROR
  └─ c = user_handler(ev, c)         // 调用用户注册的处理函数
  │
  ▼
user_handler 返回（可能修改 c->mepc += 4 跳过 ecall）
  │
  ▼
__am_asm_trap（trap.S）—— 恢复现场
  ├─ LOAD t1 ← sp+OFFSET_STATUS → csrw mstatus, t1
  ├─ LOAD t2 ← sp+OFFSET_EPC   → csrw mepc, t2
  ├─ POP x1~x31 ← sp+n*XLEN
  ├─ sp += CONTEXT_SIZE
  └─ mret                            // PC ← mepc，返回用户程序
```

---

### 3.2 对应代码逐步执行流

**第一步：`yield()` 触发 ecall**

```c
// abstract-machine/am/src/riscv/nemu/cte.c
void yield() {
  asm volatile("li a7, -1; ecall");
  // ecall 指令执行时，硬件立即：
  //   mcause  = 11
  //   mepc    = 当前 PC（ecall 这条指令的地址）
  //   PC      = mtvec  ← cte_init() 时写入的 __am_asm_trap 地址
}
```

---

**第二步：`__am_asm_trap` — 保存现场**

```asm
# abstract-machine/am/src/riscv/nemu/trap.S

# 常量（预处理宏，rv64 下展开值）：
# CONTEXT_SIZE = (32 + 3) * 8 = 280 字节
# OFFSET_CAUSE  = 32 * 8 = 256
# OFFSET_STATUS = 33 * 8 = 264
# OFFSET_EPC    = 34 * 8 = 272

__am_asm_trap:
  addi sp, sp, -CONTEXT_SIZE      # 栈上分配 280 字节，sp 现在指向 Context 底部

  MAP(REGS, PUSH)                  # 展开为：
                                   #   sd x1,  8(sp)   → gpr[1]  = ra
                                   #   sd x3, 24(sp)   → gpr[3]  = gp
                                   #   ...（跳过 x0，x2/sp 单独处理）
                                   #   sd x31, 248(sp) → gpr[31] = t6

  csrr t0, mcause                  # t0 = 11
  csrr t1, mstatus                 # t1 = 当前 mstatus
  csrr t2, mepc                    # t2 = ecall 指令的 PC

  sd t0, 256(sp)                   # Context.mcause  = 11
  sd t1, 264(sp)                   # Context.mstatus = ...
  sd t2, 272(sp)                   # Context.mepc    = ecall PC

  li a0, (1 << 17)                 # MPRV 位掩码
  or t1, t1, a0
  csrw mstatus, t1                 # 设置 mstatus.MPRV=1（DiffTest 需要）

  mv a0, sp                        # a0 = sp = Context* （C 调用约定第一个参数）
  call __am_irq_handle             # 跳转到 C 函数
```

---

**第三步：`__am_irq_handle` — 事件分发**

```c
// abstract-machine/am/src/riscv/nemu/cte.c

Context* __am_irq_handle(Context *c) {
  // c == sp（trap.S 传入的 a0），即栈上 Context 结构体的指针

  if (user_handler) {              // 检查是否已通过 cte_init() 注册过 handler
    Event ev = {0};
    switch (c->mcause) {           // 读 Context 里保存的 mcause 值
      case 11: ev.event = EVENT_YIELD;     break; // M-mode ecall（ecall 指令）
      case 12: ev.event = EVENT_IRQ_TIMER; break; // 计时器中断（由 NEMU 设备层触发）
      case 13: ev.event = EVENT_IRQ_IODEV; break; // 外设中断（由 NEMU 设备层触发）
      default: ev.event = EVENT_ERROR;     break;
    }

    c = user_handler(ev, c);       // 回调上层注册的函数，例如：
                                   //   simple_trap(ev, c) → putch('y'/'t'/'d'); return c;
                                   //   schedule(ev, c)    → 切换任务，返回新 Context*
    assert(c != NULL);
  }

  return c;                        // 返回值写入 a0，trap.S 用它做恢复依据
}
```

---

**第四步：`__am_asm_trap` — 恢复现场**

```asm
# __am_irq_handle 返回后，a0 = Context*（可能已是另一个任务的 Context）

  ld t1, 264(sp)                   # 读 Context.mstatus
  ld t2, 272(sp)                   # 读 Context.mepc
  csrw mstatus, t1                 # 恢复 mstatus
  csrw mepc, t2                    # 恢复 mepc（决定 mret 后跳哪里）

  MAP(REGS, POP)                   # 展开为：
                                   #   ld x1,  8(sp)   恢复 ra
                                   #   ld x3, 24(sp)   恢复 gp
                                   #   ...
                                   #   ld x31, 248(sp) 恢复 t6

  addi sp, sp, CONTEXT_SIZE        # 释放 Context 空间（sp 恢复到 trap 前）

  mret                             # PC ← mepc，特权级 ← mstatus.MPP
                                   # 返回到 ecall 指令之后（若 mepc 已 +4）
                                   # 或重新执行 ecall（若 mepc 未 +4，即 yield 悬停）
```

---

### 3.3 栈帧内存布局（rv64，trap 期间）

```
高地址
┌─────────────────────────────────┐ ← trap 前的 sp（旧 sp）
│  ... 调用者的栈帧 ...            │
├─────────────────────────────────┤ ← 新 sp = 旧 sp - 280
│  gpr[0]  (x0，未写入，值无意义) │ sp + 0
│  gpr[1]  (ra)                   │ sp + 8
│  gpr[2]  (sp，保存的是旧 sp)    │ sp + 16   ← OFFSET_SP
│  gpr[3]  (gp)                   │ sp + 24
│  ...                            │
│  gpr[17] (a7，ecall 前由调用者设置，已保存在此）│ sp + 136
│  ...                            │
│  gpr[31] (t6)                   │ sp + 248
│  mcause                         │ sp + 256  ← OFFSET_CAUSE
│  mstatus                        │ sp + 264  ← OFFSET_STATUS
│  mepc                           │ sp + 272  ← OFFSET_EPC
└─────────────────────────────────┘
低地址
    ↑ a0 = sp（传给 __am_irq_handle 的 Context* 就是这里）
```

---

### 3.4 ecall 的参数传递：mcause vs a7

**ecall 指令本身没有编码参数**，硬件对 M-mode ecall 一律写 `mcause = 11`，不管你想表达什么"类型"。那多种 ecall 类型怎么区分？

**不需要多条 ecall 指令——用寄存器传参，约定 `a7`（gpr[17]）为"类型码"**：

```
调用方（汇编/C）               trap handler（__am_irq_handle）
─────────────────               ──────────────────────────────
li  a7, -1                     // yield：a7 = -1
ecall           ─────────────▶  mcause = 11（硬件固定）
                                c->gpr[17] = -1（保存的 a7）
                                → 读 c->gpr[17] 可知这是 yield

li  a7, 0                      // syscall #0
ecall           ─────────────▶  mcause = 11（仍是 11）
                                c->gpr[17] = 0
                                → 读 c->gpr[17] 可知这是 syscall 0
```

AM 的 `yield()` 中 `li a7, -1` 就是这个约定：

```c
void yield() {
  asm volatile("li a7, -1; ecall");
  // a7 = -1 是"这是 yield，不是普通 syscall"的信号
  // trap handler 可用 c->gpr[17] 来区分
}
```

**当前 `__am_irq_handle` 的处理方式**（只看 mcause）：

| mcause | 来源 | 触发方式 |
|--------|------|---------|
| 11 | M-mode ecall（`ecall` 指令）| `yield()` → `li a7,-1; ecall` |
| 12 | NEMU 计时器中断 | NEMU 设备层调用 `isa_raise_intr(12, pc)` |
| 13 | NEMU 外设中断 | NEMU 设备层调用 `isa_raise_intr(13, pc)` |

timer/iodev（12/13）**不是** ecall，是外部异步中断，由 NEMU 设备更新时直接调用 `isa_raise_intr` 注入，与 ecall 走同一个 mtvec 入口，但 mcause 的值不同。

**如果未来要区分 yield 和 syscall（两者 mcause 都是 11），则读 `c->gpr[17]`**：

```c
case 11:
  if ((sword_t)c->gpr[17] == -1)
    ev.event = EVENT_YIELD;
  else
    ev.event = EVENT_SYSCALL;
  break;
```

`GPR1` 宏（arch/riscv.h）就是为此准备的：

```c
#define GPR1 gpr[17]  // a7（rv32/rv64）
// 用 c->GPR1 即可访问保存的 a7 值
```

---

## 4. 关键语法说明

### 4.1 函数指针作为参数

```c
bool cte_init(Context *(*handler)(Event, Context *));
//                     ↑
//            handler 是"接受 (Event, Context*) 返回 Context* 的函数"的指针
```

调用侧示例：
```c
Context *my_handler(Event ev, Context *ctx) { ... }
cte_init(my_handler);   // 传入函数地址
```

### 4.2 内联汇编（GCC extended asm）

```c
asm volatile("csrw mtvec, %0" : : "r"(__am_asm_trap));
//           ───────────────   ─  ───────────────────
//           指令模板            无输出  输入：将 __am_asm_trap 存入寄存器 %0
// volatile：禁止编译器移动或删除此语句
```

### 4.3 trap.S 的 MAP 宏（X-Macro 展开）

```asm
#define PUSH(n)  STORE concat(x, n), (n * XLEN)(sp);
#define REGS(f)  REGS_LO16(f) REGS_HI16(f)
MAP(REGS, PUSH)
// 展开为：
//   sd x1, 8(sp);   sd x3, 24(sp);   sd x4, 32(sp); ...
// x0 不在 REGS 宏里，因为 x0 恒为 0，无需保存/恢复
```

### 4.4 为什么 gpr[0]（x0）偏移为 0 却不保存？

`x0` 硬连线为 0，写入无效。trap.S 的 `REGS` 宏从 `f(1)` 开始，跳过 `f(0)`，但 `Context.gpr[0]` 的槽位仍占用 `sp+0` 的空间，只是值不被初始化（读到的是旧栈内容，不应依赖）。

---

## 5. yield() 的"悬停"现象

当前 `__am_irq_handle` 对 `EVENT_YIELD` 的处理**不会**把 `ctx->mepc += 4`，`mret` 后 PC 仍指向 `ecall`，导致无限循环打印 `y`。

修复方式（等实现 syscall 时加入）：
```c
case 11:
  ev.event = EVENT_YIELD;
  c->mepc += 4;   // 跳过 ecall 指令
  break;
```

---

## 6. 文件速查

| 文件 | 职责 |
|------|------|
| `am/include/arch/riscv.h` | `Context` 结构体定义 |
| `am/include/am.h` | `Event`、`cte_init` 声明 |
| `am/src/riscv/nemu/trap.S` | 汇编 trap 入口，保存/恢复寄存器 |
| `am/src/riscv/nemu/cte.c` | `cte_init`、`__am_irq_handle`、`yield` |
