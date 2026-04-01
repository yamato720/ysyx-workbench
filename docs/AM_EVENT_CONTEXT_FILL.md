# Event 和 Context 是怎么被填充的

> 以 riscv64-nemu 平台为例

---

## 1. 两个结构体的定义

### Event（平台无关，定义在 am.h）

```c
// abstract-machine/am/include/am.h
typedef struct {
  enum {
    EVENT_NULL = 0,
    EVENT_YIELD, EVENT_SYSCALL, EVENT_PAGEFAULT, EVENT_ERROR,
    EVENT_IRQ_TIMER, EVENT_IRQ_IODEV,
  } event;
  uintptr_t cause, ref;
  const char *msg;
} Event;
```

`Event` 是 AM 对"发生了什么事"的**抽象描述**，与具体 ISA 无关。  
它由 C 代码填充（见第 3 节），填充完后传给用户注册的回调函数。

### Context（平台相关，定义在 arch/riscv.h）

```c
// abstract-machine/am/include/arch/riscv.h
struct Context {
  uintptr_t gpr[NR_REGS];          // 32 个通用寄存器 x0-x31
  uintptr_t mcause, mstatus, mepc; // 3 个 CSR 寄存器快照
  void *pdir;                       // 页目录（VME 用，可忽略）
};
```

`Context` 是"被中断那一刻 CPU 的完整快照"，由**汇编代码**直接压栈填充（见第 2 节）。

---

## 2. Context 是怎么被填充的（汇编层）

文件：`abstract-machine/am/src/riscv/nemu/trap.S`

当任何中断/异常发生时，CPU 硬件自动跳转到 `mtvec` 寄存器指向的地址，  
即 `__am_asm_trap`（在 `cte_init` 中用 `csrw mtvec, %0` 设置）。

```asm
__am_asm_trap:
  addi sp, sp, -CONTEXT_SIZE    ; ① 在当前栈上腾出一块空间（35 * 8 = 280 字节）

  MAP(REGS, PUSH)               ; ② 把 x1~x31 的值依次存入栈上对应位置
                                ;    （注意跳过了 x0，因为 x0 恒为 0）

  csrr t0, mcause               ; ③ 读 CSR：中断/异常原因
  csrr t1, mstatus              ;    读 CSR：机器状态（含 MIE/MPIE 等）
  csrr t2, mepc                 ;    读 CSR：被中断的 PC 值

  STORE t0, OFFSET_CAUSE(sp)    ; ④ 存入 Context.mcause
  STORE t1, OFFSET_STATUS(sp)   ;    存入 Context.mstatus
  STORE t2, OFFSET_EPC(sp)      ;    存入 Context.mepc

  mv a0, sp                     ; ⑤ 把栈指针（= Context 结构体的地址）作为参数
  call __am_irq_handle           ;    调用 C 函数
```

**关键理解：** `Context` 不是 `malloc` 出来的，它就是当前的**栈帧**。  
`addi sp, sp, -CONTEXT_SIZE` 让 sp 往下移，之后对 `[sp + offset]` 的写入，  
恰好对应 `struct Context` 各字段的内存布局。  
`mv a0, sp` 把这块栈内存的地址（`= (Context *)` 指针）传给 C 函数。

### 填充后的栈内存布局

```
高地址
┌────────────────────────┐ ← 进入 __am_asm_trap 时的 sp（原栈顶）
│  原调用者的栈帧...      │
├────────────────────────┤
│  Context.mepc          │  offset = (32+2)*8 = 272
├────────────────────────┤
│  Context.mstatus       │  offset = (32+1)*8 = 264
├────────────────────────┤
│  Context.mcause        │  offset = (32+0)*8 = 256
├────────────────────────┤
│  Context.gpr[31] (x31) │  offset = 31*8 = 248
│  ...                   │
│  Context.gpr[2]  (x2/sp)│ offset = 2*8  = 16
│  ...                   │
│  Context.gpr[1]  (x1)  │  offset = 1*8  = 8
│  Context.gpr[0]  (x0)  │  offset = 0    （x0 恒为 0，不存也一样）
├────────────────────────┤ ← 执行完 addi sp,-CONTEXT_SIZE 后的新 sp
│                        │   这个地址就是 (Context *) 指针
低地址
```

---

## 3. Event 是怎么被填充的（C 层）

文件：`abstract-machine/am/src/riscv/nemu/cte.c`

```c
Context* __am_irq_handle(Context *c) {   // c = 上面传来的栈上 Context 指针
  if (user_handler) {
    Event ev = {0};                      // ① 在栈上创建一个全零的 Event

    switch (c->mcause) {                 // ② 读取 Context 里的 mcause，判断原因
      case 11:                           //    M 模式 ecall（yield/syscall）
        ev.event = EVENT_YIELD;
        c->mepc += 4;                    //    跳过 ecall 指令（否则返回后死循环）
        break;
      case 12:                           //    定时器中断（NEMU 约定）
        ev.event = EVENT_IRQ_TIMER;
        break;
      case 13:                           //    外设中断（NEMU 约定）
        ev.event = EVENT_IRQ_IODEV;
        break;
      default:
        ev.event = EVENT_ERROR;
        break;
    }

    c = user_handler(ev, c);             // ③ 调用用户注册的回调，传入事件和上下文
    assert(c != NULL);
  }
  return c;                              // ④ 返回（可能被调度器替换过的）Context 指针
}
```

填充 `Event` 的信息只有 `event` 字段被设置，`cause`/`ref`/`msg` 均为 0/NULL。  
更丰富的填充（如 `EVENT_PAGEFAULT` 时填 `ref = 缺页地址`）在其他平台或 VME 实现中会用到。

---

## 4. 整体数据流一图

```
CPU 硬件
  │  中断/异常发生
  │  mcause ← 原因编号
  │  mepc   ← 被中断的 PC
  │  mstatus ← 保存 MIE→MPIE
  │  PC ← mtvec
  ↓
__am_asm_trap  (trap.S)
  │  sp -= sizeof(Context)    ← 在栈上开辟 Context 空间
  │  Context.gpr[]    ← 压栈 x1~x31
  │  Context.mcause   ← csrr mcause
  │  Context.mstatus  ← csrr mstatus
  │  Context.mepc     ← csrr mepc
  │  a0 = sp          ← 把 Context* 作为参数
  ↓
__am_irq_handle(Context *c)  (cte.c)
  │  Event ev = {0}           ← 在栈上创建空 Event
  │  switch(c->mcause):
  │    11 → ev.event = EVENT_YIELD; c->mepc += 4
  │    12 → ev.event = EVENT_IRQ_TIMER
  │    13 → ev.event = EVENT_IRQ_IODEV
  │  user_handler(ev, c)      ← 调用用户回调（如 do_event、simple_trap）
  ↓
用户回调  (nanos-lite/src/irq.c 或 am-tests/src/tests/intr.c)
  │  switch(ev.event):
  │    EVENT_YIELD     → 调度 / putch('y')
  │    EVENT_IRQ_TIMER → putch('t')
  │  return ctx        ← 返回要恢复的 Context（调度时可换一个）
  ↓
__am_irq_handle 返回 Context *c
  ↓
__am_asm_trap  (返回路径)
  │  csrw mstatus ← 从 Context.mstatus 恢复
  │  csrw mepc    ← 从 Context.mepc 恢复（可能已被 +=4）
  │  POP x1~x31  ← 从栈上恢复通用寄存器
  │  sp += sizeof(Context)
  └→ mret         ← 跳回 mepc，MIE 从 MPIE 恢复
```

---

## 5. 用户如何注册回调

```c
// nanos-lite/src/irq.c
static Context* do_event(Event e, Context* c) {
  switch (e.event) {
    case EVENT_YIELD: /* ... */ break;
    default: panic("Unhandled event ID = %d", e.event);
  }
  return c;
}

void init_irq(void) {
  cte_init(do_event);   // 把 do_event 的地址存入 user_handler 全局变量
}
```

`cte_init` 做两件事：
1. `csrw mtvec, __am_asm_trap` — 让硬件跳转到我们的汇编入口
2. `user_handler = handler` — 记录 C 回调函数指针

之后每次中断，汇编自动填充 `Context`，C 代码填充 `Event`，  
最终用这两个结构体调用你写的 `do_event`。
