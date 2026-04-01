# user_handler：AM CTE 的事件回调机制

## 1. 它是什么

`user_handler` 是 CTE（Context/Trap Extension）模块内部的一个**函数指针变量**，保存着上层代码（操作系统或测试程序）注册的异常/中断处理函数。

```c
// abstract-machine/am/src/riscv/nemu/cte.c
static Context* (*user_handler)(Event, Context*) = NULL;
```

类型拆解：

```
static  Context*  (*user_handler)  (Event, Context*)  = NULL;
  │        │            │               │                │
  │        │            │               └─ 参数：事件描述 + 当前上下文
  │        │            └─ 变量名：user_handler，是个指针（*）
  │        └─ 返回值类型：Context*（处理后返回的上下文，用于切换任务）
  └─ 文件内私有，外部不可见
```

这和普通变量声明完全一致，只是类型写法特殊：

| 普通变量 | 函数指针变量 |
|---------|------------|
| `int i = 0;` | `Context* (*user_handler)(Event, Context*) = NULL;` |
| 类型 `int`，实例 `i`，初值 `0` | 类型 `Context*(*)(Event,Context*)`，实例 `user_handler`，初值 `NULL` |

---

## 2. user_handler 在哪被赋值？

### 2.1 声明时初始化为 NULL

```c
// cte.c（文件顶部）
static Context* (*user_handler)(Event, Context*) = NULL;
// 启动时没有 handler，NULL 保证不会跳到非法地址
```

### 2.2 cte_init() 中被赋值

```c
bool cte_init(Context*(*handler)(Event, Context*)) {
  asm volatile("csrw mtvec, %0" : : "r"(__am_asm_trap));
  user_handler = handler;   // ← 这里赋值，把传入的函数地址存进去
  return true;
}
```

### 2.3 __am_irq_handle() 中被调用

```c
c = user_handler(ev, c);   // 通过存储的地址间接调用
```

完整赋值 + 调用时序：

```
cte_init(schedule)          → user_handler = &schedule  （赋值）
yield() → ecall → trap.S   → __am_irq_handle()
                             → user_handler(ev, c)       （调用）
```

---

## 3. static schedule 是怎么传进来的？

`schedule` 在 yield-os.c 里被声明为 `static`：

```c
// yield-os.c
static Context *schedule(Event ev, Context *prev) { ... }
```

`static` 函数的含义是**名字不对外暴露**（链接器不导出这个符号），但它的**地址仍然是普通的内存地址**，可以作为值传递。

关键区别：

| 情形 | 能否跨文件访问 |
|------|--------------|
| 另一个 `.c` 文件写 `schedule(ev, ctx)` | ❌ 编译错误，名字不可见 |
| 把 `schedule` 作为**值**（地址）传给函数 | ✅ 完全合法，地址就是数字 |

传递过程：

```
yield-os.c                          cte.c
─────────────────────               ──────────────────────────────
static Context *schedule(...) {    │
  ...                              │
}                                  │
                                   │
int main() {                       │
  cte_init(schedule);  ──────────▶ │ bool cte_init(Context*(*handler)(...)) {
  // "schedule" 在这里是地址值      │   user_handler = handler;  // 存地址
  // 类似 cte_init(0x80001234)     │ }
}                                  │
```

`cte.c` 从始至终不需要知道 `schedule` 这个名字，它只持有一个函数地址（数字），调用时跳转到那个地址执行。这正是函数指针的核心用途。

---

## 4. 函数指针语法速览

### 4.1 声明

```c
返回类型 (*指针名)(参数类型列表);
```

```c
Context* (*user_handler)(Event, Context*);
// 等价于：能被 user_handler 指向的函数，其签名必须是：
//   Context* 某函数名(Event ev, Context *ctx);
```

### 4.2 赋值

函数名本身就是地址，直接赋给同类型的函数指针：

```c
user_handler = schedule;   // schedule 是一个符合签名的函数
```

### 4.3 调用

```c
c = user_handler(ev, c);
// 等价于通过函数地址直接调用，与 schedule(ev, c) 效果相同
```

### 4.4 作为参数传递（cte_init 的参数）

```c
// am.h 中的声明
bool cte_init(Context *(*handler)(Event ev, Context *ctx));
//                     ↑
//             handler 本身也是一个函数指针参数
```

调用时直接传函数名：
```c
cte_init(schedule);   // 把 schedule 的地址传入
```

---

## 3. 生命周期与调用链

```
上层代码（如 yield-os）
  │
  │  cte_init(schedule);          // ① 注册：把 schedule 地址存入 user_handler
  │
  │  yield();                     // ② 触发：执行 ecall 指令
  │
  ▼
硬件自动跳转到 mtvec（= __am_asm_trap）
  │
  ▼
trap.S：__am_asm_trap             // ③ 保存所有寄存器到栈上，构造 Context
  │  mv a0, sp
  │  call __am_irq_handle
  │
  ▼
cte.c：__am_irq_handle(Context *c)
  │  识别 c->mcause，填写 Event 结构体
  │
  │  c = user_handler(ev, c);     // ④ 回调：调用上层注册的函数
  │                                //    返回值是下一个要运行的上下文
  ▼
trap.S 恢复寄存器，mret 返回      // ⑤ 切换到 user_handler 返回的 Context
```

---

## 4. 具体示例：yield-os

```c
// am-kernels/kernels/yield-os/yield-os.c

static Context *schedule(Event ev, Context *prev) {
  current->cp = prev;            // 保存当前任务的上下文
  current = (current == &pcb[0] ? &pcb[1] : &pcb[0]);  // 切换到另一任务
  return current->cp;            // 返回下一任务的上下文
}

int main() {
  cte_init(schedule);            // 把 schedule 注册为 user_handler
  ...
  yield();                       // 触发 ecall → 最终调用 schedule
}
```

数据流：

```
yield() ecall
  → mcause = 11
  → __am_irq_handle: ev.event = EVENT_YIELD
  → user_handler(ev, prev_ctx)  即 schedule(ev, prev_ctx)
      ├─ 保存 prev_ctx 到当前 PCB
      └─ 返回 next_ctx（另一个任务的上下文）
  → trap.S 用 next_ctx 恢复寄存器
  → mret 跳转到 next_ctx.mepc（另一个任务的 PC）
```

---

## 5. 具体示例：am-tests intr（多事件分发）

与 yield-os 不同，这个例子展示了**同一个 handler 处理多种事件类型**。

### 5.1 handler 定义

```c
// am-kernels/tests/am-tests/src/tests/intr.c

Context *simple_trap(Event ev, Context *ctx) {
  switch (ev.event) {
    case EVENT_IRQ_TIMER: putch('t'); break;  // 计时器中断
    case EVENT_IRQ_IODEV: putch('d'); break;  // 外设中断
    case EVENT_YIELD:     putch('y'); break;  // yield() ecall
    default: panic("Unhandled event"); break;
  }
  return ctx;   // 返回原 ctx：不切换任务，继续执行被打断的地方
}
```

### 5.2 注册与触发

```c
// am-kernels/tests/am-tests/src/main.c
// CASE 宏展开 → IOE 初始化 + CTE(simple_trap) 注册 + hello_intr() 运行
CASE('i', hello_intr, IOE, CTE(simple_trap));

// CTE 宏（amtest.h）：前向声明 + 注册一步到位
#define CTE(h) ({ Context *h(Event, Context *); cte_init(h); })
// CTE(simple_trap) 等价于：
//   Context *simple_trap(Event, Context *);   // 前向声明（无需 include）
//   cte_init(simple_trap);                    // 注册为 user_handler
```

```c
void hello_intr() {
  printf("Hello, AM World @ " __ISA__ "\n");
  iset(1);           // 开中断（允许计时器/外设中断进来）
  while (1) {
    for (volatile int i = 0; i < 10000000; i++) ;
    yield();         // 主动触发 EVENT_YIELD → 打印 'y'
  }
  // 计时器到时 → EVENT_IRQ_TIMER → 打印 't'
  // 外设就绪  → EVENT_IRQ_IODEV → 打印 'd'
}
```

### 5.3 数据流对比

| 触发源 | mcause | ev.event | handler 行为 | 返回值 |
|--------|--------|----------|-------------|--------|
| `yield()` | 11 | `EVENT_YIELD` | `putch('y')` | 同一 ctx（不切换）|
| 计时器中断 | — | `EVENT_IRQ_TIMER` | `putch('t')` | 同一 ctx |
| 外设中断 | — | `EVENT_IRQ_IODEV` | `putch('d')` | 同一 ctx |

> 与 yield-os 的区别：`simple_trap` 始终返回传入的 `ctx`（原地返回继续执行），而 `schedule` 会返回**另一个任务**的 ctx 实现任务切换。函数指针机制让两种语义用同一套接口表达。

---

## 6. 为什么要用函数指针（而不是直接调用）

| 方式 | 问题 |
|------|------|
| 直接调用固定函数 | AM 是平台库，不知道上层要做什么（调度？调试？系统调用？）|
| 函数指针回调 | AM 只定义接口，上层自由实现，实现了**平台与 OS 解耦** |

这是**回调（callback）**模式：AM 在发生 trap 时"回调"上层注册的处理函数。`user_handler = NULL` 的初始值保证了在未调用 `cte_init` 时不会跳转到非法地址。

---

## 6. handler 的签名约定

```c
Context *handler(Event ev, Context *ctx);
```

| 参数 | 含义 |
|------|------|
| `ev.event` | 事件类型（EVENT_YIELD / EVENT_SYSCALL / EVENT_ERROR …）|
| `ev.cause` | 额外原因码（缺页地址等）|
| `ctx` | 触发 trap 时的寄存器快照（可读写）|
| 返回值 | **下一个要运行的 Context***；必须非 NULL（assert 保证）；可以返回同一个 ctx 表示不切换 |

修改 `ctx->mepc` 可以改变 trap 返回后的 PC（例如 `ctx->mepc += 4` 跳过 ecall 指令）。

---

## 7. 相关文件

| 文件 | 内容 |
|------|------|
| `am/include/am.h` | `cte_init` 声明、`Event` 类型定义 |
| `am/include/arch/riscv.h` | `Context` 结构体 |
| `am/src/riscv/nemu/cte.c` | `user_handler` 变量、`cte_init`、`__am_irq_handle` |
| `am/src/riscv/nemu/trap.S` | 汇编 trap 入口，调用 `__am_irq_handle` |
| `am-kernels/kernels/yield-os/yield-os.c` | 示例：返回不同 ctx 实现协作式调度 |
| `am-kernels/tests/am-tests/src/tests/intr.c` | 示例：多事件分发，原地返回 ctx |
| `am-kernels/tests/am-tests/include/amtest.h` | `CTE(h)` 宏：前向声明 + 注册一步到位 |
