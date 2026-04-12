# CSR 与异常处理实现指南（PA3 阶段）

本文档记录 PA3 阶段「yield/自陷」特性在软件（NEMU）层面的完整实现，以及后续在 Chisel 硬件中对应实现的方法和建议。

> **文档状态（2026-04-07）**：  
> - NEMU 软件层面（第一节）已全部实现，内容与代码一致。  
> - Chisel 硬件层面（第二节）：`CSRs` 模块已实现（Step 1 完成），`Control.scala` / `top.scala` / `npc/cte.c` 尚未接入（Step 2–5 待完成）。

---

## 一、软件（NEMU）实现总结

### 1.1 涉及文件

| 文件 | 改动内容 |
|------|---------|
| `nemu/src/isa/riscv64/include/isa-def.h` | CPU_state 结构体加入 4 个 CSR 字段 |
| `nemu/src/isa/riscv64/system/intr.c` | 实现 `isa_raise_intr()` |
| `nemu/src/isa/riscv64/inst.c` | ecall 改为触发异常，新增 mret，完整实现 6 条 CSR 指令 |
| `abstract-machine/am/include/arch/riscv.h` | 修正 Context 结构体字段排列顺序 |
| `abstract-machine/am/src/riscv/nemu/cte.c` | `__am_irq_handle` 完整处理 yield/syscall/timer/iodev |

### 1.2 CPU 状态扩展（isa-def.h）

```c
typedef struct {
  word_t  gpr[32];    // 通用寄存器 x0~x31
  vaddr_t pc;
  // 新增 CSR 寄存器 ──────────────────────────────
  word_t  mstatus;   // 机器模式状态寄存器
  word_t  mcause;    // 异常原因码
  vaddr_t mepc;      // 异常返回地址（发生异常时的 PC）
  vaddr_t mtvec;     // 异常入口地址（trap vector）
} riscv64_CPU_state;
```

### 1.3 异常响应机制（intr.c）

```c
word_t isa_raise_intr(word_t NO, vaddr_t epc) {
  cpu.mcause = NO;     // 记录异常原因
  cpu.mepc   = epc;    // 记录触发异常的 PC（即 ecall 指令地址）
  return cpu.mtvec;    // 返回异常入口地址，NEMU 将 PC 设为此值
}
```

RISC-V M-mode 异常原因码（mcause）对照（常用）：

| 值 | 含义 |
|----|------|
| 11 | M-mode environment call（`ecall`，即 yield/syscall） |
| 3  | 断点（ebreak） |
| 2  | 非法指令 |

### 1.4 指令实现（inst.c）

**ecall**（自陷）：
```c
// 调用 isa_raise_intr，写入 mcause/mepc，跳转到 mtvec
INSTPAT("0000000 00000 00000 000 00000 11100 11", ecall, N,
    s->dnpc = isa_raise_intr(11, s->pc));
```

**mret**（异常返回）：
```c
INSTPAT("0011000 00010 00000 000 00000 11100 11", mret, N,
    s->dnpc = cpu.mepc);
```

> **注意**：`__am_irq_handle` 在 case 11 中会执行 `c->mepc += 4`（见 §1.6），因此 mret 返回到 ecall 之后的下一条指令，而不是 ecall 本身。

**CSR 指令**（csrrw / csrrs / csrrc / csrrwi / csrrsi / csrrci）：

使用 I-type 解码，`imm` 字段（bits[31:20]）即为 CSR 地址：

| CSR 地址 | 寄存器 |
|---------|--------|
| 0x300 | mstatus |
| 0x305 | mtvec |
| 0x341 | mepc |
| 0x342 | mcause |

```c
// 示例：csrrw rd, csr, rs1  → rd = CSR[csr]; CSR[csr] = rs1
INSTPAT("??????? ????? ????? 001 ????? 11100 11", csrrw, I,
    { word_t old = csr_read(imm); csr_write(imm, src1); R(rd) = old; });
```

### 1.5 Context 结构体修正（riscv.h）

`trap.S` 中寄存器保存顺序（从低地址到高地址）：

```
[sp+0]     gpr[0](x0)
[sp+8]     gpr[1](x1/ra)
...
[sp+248]   gpr[31]
[sp+256]   mcause  ← OFFSET_CAUSE = (32+0)*8
[sp+264]   mstatus ← OFFSET_STATUS = (32+1)*8
[sp+272]   mepc    ← OFFSET_EPC   = (32+2)*8
```

对应 `struct Context`：
```c
struct Context {
  uintptr_t gpr[NR_REGS];   // offset 0~248
  uintptr_t mcause;          // offset 256
  uintptr_t mstatus;         // offset 264
  uintptr_t mepc;            // offset 272
  void *pdir;
};
```

### 1.6 执行流程

`abstract-machine/am/src/riscv/nemu/cte.c` 中 `__am_irq_handle` 的完整实现（已落地）：

```c
Context* __am_irq_handle(Context *c) {
  if (user_handler) {
    Event ev = {0};
    switch (c->mcause) {
      case 11:
        if (c->GPR1 == (uintptr_t)-1) {
          ev.event = EVENT_YIELD;
        } else {
          ev.event = EVENT_SYSCALL;
        }
        c->mepc += 4;   // 跳过 ecall 指令，mret 返回到 ecall+4
        break;
      case 12: ev.event = EVENT_IRQ_TIMER; break;  // M-mode timer interrupt
      case 13: ev.event = EVENT_IRQ_IODEV; break;  // M-mode external interrupt
      default: ev.event = EVENT_ERROR; break;
    }
    c = user_handler(ev, c);
    assert(c != NULL);
  }
  return c;
}
```

完整调用链：

```
yield()           → li a7, -1; ecall
ecall（NEMU）     → isa_raise_intr(11, pc_of_ecall)
                     mcause=11, mepc=pc_of_ecall
                     dnpc = mtvec = __am_asm_trap 的地址
__am_asm_trap     → 保存所有 gpr + mcause/mstatus/mepc 到栈
                     csrw mstatus, mstatus|MPRV（仅 NEMU difftest 用）
                     调用 __am_irq_handle(ctx)
__am_irq_handle   → switch(mcause=11)
                     GPR1==-1 → EVENT_YIELD；否则 EVENT_SYSCALL
                     c->mepc += 4
                     → 调用 user_handler（如 schedule/syscall handler）
__am_asm_trap 恢复 → csrw mepc, mepc（已 +4）; mret
mret（NEMU）      → dnpc = cpu.mepc = pc_of_ecall + 4
                     返回 ecall 之后继续执行
```

---

## 二、Chisel 硬件实现方案

### 2.1 需要新增的硬件结构

#### A. CSR 寄存器文件（`CSRs` 类，已实现于 `DataManage.scala`）✅

`CSRs` 模块已在 `npc/chisel/src/main/scala/DataManage.scala` 中实现，接口如下：

```scala
class CSRs(Width: Int = 64) extends Module {
  val io = IO(new Bundle {
    // CSR 读/写接口（csrr* 指令）
    val addr    = Input(UInt(12.W))    // CSR 地址（imm[11:0]）
    val wdata   = Input(UInt(Width.W)) // 待写入值（外部运算好后传入）
    val we      = Input(Bool())        // 写使能

    val rdata   = Output(UInt(Width.W)) // 读出旧值（写回 rd 用）

    // Trap 接口（ecall）
    val trap_en    = Input(Bool())
    val trap_cause = Input(UInt(Width.W))  // → mcause
    val trap_epc   = Input(UInt(Width.W))  // → mepc
    val mtvec_out  = Output(UInt(Width.W)) // 异常入口地址

    // mret 接口
    val mret_en   = Input(Bool())
    val mepc_out  = Output(UInt(Width.W))  // 返回地址
  })
  // trap_en 优先级高于 we；4 个 CSR：mstatus/mtvec/mepc/mcause
}
```

实际实现中 `trap_en` 优先级高于普通 CSR 写（`we`），与规范一致。

#### B. OpcodeCtrl 扩展（`Control.scala`，**待实现**）

在 `Control.scala` 的 `OpcodeCtrlTop` / `OpcodeCtrl_I` 里，针对 opcode `1110011`（SYSTEM）新增处理：

| 指令 | opcode | funct3 | funct12（全0） | 动作 |
|------|--------|--------|----------------|------|
| ecall | 1110011 | 000 | 000000000000 | trapEn=true |
| mret | 1110011 | 000 | 001100000010 | mretEn=true |
| csrrw | 1110011 | 001 | — | csrEn=true, csrOp=00 |
| csrrs | 1110011 | 010 | — | csrEn=true, csrOp=01 |
| csrrc | 1110011 | 011 | — | csrEn=true, csrOp=10 |

需要新增的控制信号：
```scala
// 在 OpcodeCtrlTop 的 io Bundle 中追加：
val trapEn  = Output(Bool())   // ecall：触发异常
val mretEn  = Output(Bool())   // mret：从异常返回
val csrEn   = Output(Bool())   // CSR 指令：需要读写 CSR
val csrOp   = Output(UInt(2.W)) // 00=写 01=置位 10=清位
val csrRegWrite = Output(Bool()) // CSR 结果写回 rd
```

#### C. PC 控制扩展（`top.scala`，**待实现**）

`mux8_3` 已有 8 个输入槽，in3/in4 目前接 `0.U`，直接改为接 CSRs 的输出即可：
| sel 值 | PC 来源 |
|--------|--------|
| 0 | PC+4 |
| 1 | 分支目标（B-type/JAL） |
| 2 | JALR 目标 |
| **3（新增）** | `mtvec_out`（ecall 跳异常入口）|
| **4（新增）** | `mepc_out`（mret 返回）|

```scala
// top.scala 中 mux8_3 的 sel 逻辑（原来只有 branch 分支，需替换）：
mux8_3_inst.io.in3 := CSRs_inst.io.mtvec_out  // 原来接 0.U
mux8_3_inst.io.in4 := CSRs_inst.io.mepc_out   // 原来接 0.U
mux8_3_inst.io.sel := MuxCase(0.U, Seq(
  OpcodeCtrlTop_inst.io.trapEn -> 3.U,
  OpcodeCtrlTop_inst.io.mretEn -> 4.U,
  (OpcodeCtrlTop_inst.io.branch && ALU_Top_inst.io.branch_taken) -> 1.U,
  // JALR 的跳转逻辑保持不变（原 sel=2）
))
```

**注意**：原来的 sel 逻辑为 `Mux(branch, branch_taken, 0.U)`，需要改成 `MuxCase` 并加入 trapEn/mretEn 的优先匹配（trap 优先级最高）。

#### D. 写回路径扩展（`top.scala`，**待实现**）

CSR 指令（csrrw 等）将 CSR 旧值写入 rd，需要在 `mux2_1_WriteBack` 上增加一路（目前只有 ALU 结果和内存读结果两路）：

```scala
// 改为 Mux4_2（4 选 1）或串联 Mux2_1
// in0: ALU 结果
// in1: 内存读结果
// in2: CSR 旧值（CSRs_inst.io.rdata）
// sel: OpcodeCtrlTop_inst.io.memtoReg（扩展为 2-bit）
```

#### E. `npc/cte.c` 补全（**待实现**）

`abstract-machine/am/src/riscv/npc/cte.c` 的 `__am_irq_handle` 目前是空壳（只有 `default: EVENT_ERROR`），需要与 nemu 版对齐：

```c
Context* __am_irq_handle(Context *c) {
  if (user_handler) {
    Event ev = {0};
    switch (c->mcause) {
      case 11:
        if (c->GPR1 == (uintptr_t)-1) {
          ev.event = EVENT_YIELD;
        } else {
          ev.event = EVENT_SYSCALL;
        }
        c->mepc += 4;
        break;
      case 12: ev.event = EVENT_IRQ_TIMER; break;
      case 13: ev.event = EVENT_IRQ_IODEV; break;
      default: ev.event = EVENT_ERROR; break;
    }
    c = user_handler(ev, c);
    assert(c != NULL);
  }
  return c;
}
```

npc 的 `trap.S` 不设置 `mstatus.MPRV`（这是 NEMU 仿真器专有逻辑），其余与 nemu 版相同，不需修改。

### 2.2 实现步骤（当前进度）

| 步骤 | 内容 | 状态 |
|------|------|------|
| Step 1 | 新建 `CSRs` 模块（CSR 寄存器文件） | ✅ 已完成（`DataManage.scala`）|
| Step 2 | 扩展 `Control.scala` 的 `OpcodeCtrl_I`，增加 `trapEn/mretEn/csrEn/csrOp` 信号 | ⬜ 待完成 |
| Step 3 | 在 `top.scala` 中实例化 `CSRs` 并连线（PC mux + 写回 mux） | ⬜ 待完成 |
| Step 4 | 处理流水线 flush（ecall/mret 触发时清空 InsBuffer） | ⬜ 待完成 |
| Step 5 | DPI-C 接口暴露 CSR 状态给 difftest，补全 `npc/cte.c` | ⬜ 待完成 |

**Step 2**：在 `OpcodeCtrl_I` 里，opcode=`1110011` 时根据 funct3 / funct12 产生控制信号，同时在 `OpcodeCtrlTop` 的 io Bundle 中暴露：

```scala
// 追加到 OpcodeCtrlTop 的 io Bundle：
val trapEn      = Output(Bool())    // ecall：触发异常
val mretEn      = Output(Bool())    // mret：从异常返回
val csrEn       = Output(Bool())    // CSR 指令：需要读写 CSR
val csrOp       = Output(UInt(2.W)) // 00=写 01=置位 10=清位（对应 csrrw/csrrs/csrrc）
val csrRegWrite = Output(Bool())    // CSR 旧值写回 rd
```

**Step 3**：在 `top.scala` 中实例化 `CSRs`，完成如下连线：

```scala
val CSRs_inst = Module(new CSRs(Width = Width))

// CSRs 写入（CSR 指令）
CSRs_inst.io.addr  := ImmGenerator_inst.io.imm_out(11, 0)  // I-type imm[11:0] = CSR addr
CSRs_inst.io.wdata := // csrrw: rs1; csrrs: old|rs1; csrrc: old&~rs1（需在外部计算好）
CSRs_inst.io.we    := OpcodeCtrlTop_inst.io.csrEn && Metronome_inst.io.tick_memwb

// Trap
CSRs_inst.io.trap_en    := OpcodeCtrlTop_inst.io.trapEn
CSRs_inst.io.trap_epc   := PC_Ctrl_inst.io.pc_out
CSRs_inst.io.trap_cause := 11.U

// mret
CSRs_inst.io.mret_en    := OpcodeCtrlTop_inst.io.mretEn

// PC 选择（接入 mux8_3 的 in3/in4）
mux8_3_inst.io.in3 := CSRs_inst.io.mtvec_out
mux8_3_inst.io.in4 := CSRs_inst.io.mepc_out

// 写回（将 CSRs.io.rdata 作为第三路加入写回 mux）
```

**Step 4**：ecall / mret 是控制流变化指令，和 branch/JAL 一样需要 flush InsBuffer，防止已取入的后续指令被错误执行。

**Step 5**：补全 `abstract-machine/am/src/riscv/npc/cte.c`（见 §2.1-E），并在 `npc_core.cpp` 的 DPI-C 接口中暴露 CSR 值以供 difftest 比对。

### 2.3 测试方法

```bash
# 1. 验证 NEMU 软件模拟（已可用）
cd am-kernels/tests/am-tests
make ARCH=riscv64-nemu run mainargs=y
# 期望：打印 'y' 后继续执行（不是无限循环——因为 mepc 已 +4）

# 2. Chisel CSR 接入完成后，用 NPC 运行
cd am-kernels/tests/am-tests
make ARCH=riscv64-npc run mainargs=y

# 3. NPC difftest 模式（需先 make -C npc chisel-dpi 并补全 npc/cte.c）
make ARCH=riscv64-nemu run-npc mainargs=y
```

### 2.4 注意事项

- **`npc/cte.c` 是空壳**：`abstract-machine/am/src/riscv/npc/cte.c` 的 `__am_irq_handle` 目前只有 `default: EVENT_ERROR`，任何 ecall（yield/syscall）打到 NPC 上都会触发 `EVENT_ERROR`。这是当前 NPC 无法运行 am-tests 的直接原因之一（见 §2.1-E 的修复方案）。
- **CSR 时序 hazard**：CSR 写（如 `csrw mtvec, __am_asm_trap`）必须在 ecall 之前提交。若 csrw 在 WB 阶段写而 ecall 在 ID 阶段读，会读到旧值。可通过 CSR forwarding 或在 ecall 前插一个 bubble 解决。
- **mstatus.MPRV**：`nemu/trap.S` 在保存 Context 后设置了 `mstatus.MPRV = 1`（bit 17），用于 difftest 时让 KVM 正确读写内存。`npc/trap.S` 没有这一步，NPC 硬件无需实现此细节。
- **mepc 存的是 ecall 的 PC**：AM 在 `__am_irq_handle` 中统一执行 `mepc += 4`，因此 mret 跳回 ecall+4，调用方（yield/syscall）正常返回。OS trap handler 无需再额外 +4。
