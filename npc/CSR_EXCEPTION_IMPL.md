# CSR 与异常处理实现指南（PA3 阶段）

本文档记录 PA3 阶段「yield/自陷」特性在软件（NEMU）层面的完整实现，以及后续在 Chisel 硬件中对应实现的方法和建议。

---

## 一、软件（NEMU）实现总结

### 1.1 涉及文件

| 文件 | 改动内容 |
|------|---------|
| `nemu/src/isa/riscv64/include/isa-def.h` | CPU_state 结构体加入 4 个 CSR 字段 |
| `nemu/src/isa/riscv64/system/intr.c` | 实现 `isa_raise_intr()` |
| `nemu/src/isa/riscv64/inst.c` | ecall 改为触发异常，新增 mret，完整实现 6 条 CSR 指令 |
| `abstract-machine/am/include/arch/riscv.h` | 修正 Context 结构体字段排列顺序 |
| `abstract-machine/am/src/riscv/nemu/cte.c` | `__am_irq_handle` 加入 `case 11: EVENT_YIELD` |

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
// 将 NEMUTRAP 改为调用 isa_raise_intr，正确跳转到 mtvec
INSTPAT("0000000 00000 00000 000 00000 11100 11", ecall, N,
    s->dnpc = isa_raise_intr(11, s->pc));
```

**mret**（异常返回）：
```c
INSTPAT("0011000 00010 00000 000 00000 11100 11", mret, N,
    s->dnpc = cpu.mepc);
```

> **注意**：`cte_init()` 设置的 `__am_asm_trap` 中，trap handler 结束前会 `csrw mepc, t2` 恢复保存的 mepc，然后 `mret` 返回。AM 的 `simple_trap` 回调不修改 `ctx->mepc`，因此 mret 返回到 ecall 指令本身，ecall 再次触发，形成无限循环（不断打印 `y`）——这是目前阶段的正确行为。未来 syscall 实现时，handler 中需要 `ctx->mepc += 4`。

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

```
yield()           → li a7, -1; ecall
ecall（NEMU）     → isa_raise_intr(11, pc_of_ecall)
                     mcause=11, mepc=pc_of_ecall
                     dnpc = mtvec = __am_asm_trap 的地址
__am_asm_trap     → 保存所有 gpr + mcause/mstatus/mepc 到栈
                     调用 __am_irq_handle(ctx)
__am_irq_handle   → switch(mcause=11) → EVENT_YIELD → 调用 simple_trap
simple_trap       → putch('y'); return ctx（mepc 未 +4）
__am_asm_trap 恢复 → csrw mepc, mepc; mret
mret（NEMU）      → dnpc = cpu.mepc = pc_of_ecall
                     回到 ecall，再次触发（无限打印 'y'）
```

---

## 二、Chisel 硬件实现方案

### 2.1 需要新增的硬件结构

#### A. CSR 寄存器文件（新模块 `CSRFile.scala`）

```scala
class CSRFile(Width: Int = 64) extends Module {
  val io = IO(new Bundle {
    // 读端口：给 csrrw/csrrs 等指令用
    val addr   = Input(UInt(12.W))   // CSR 地址（I-type imm[11:0]）
    val rdata  = Output(UInt(Width.W))
    // 写端口
    val wen    = Input(Bool())
    val waddr  = Input(UInt(12.W))
    val wdata  = Input(UInt(Width.W))
    // 异常控制端口
    val trap_en   = Input(Bool())         // ecall 触发异常
    val trap_pc   = Input(UInt(Width.W))  // ecall 的 PC → mepc
    val trap_cause = Input(UInt(Width.W)) // mcause 值（11 for ecall）
    val mtvec_out = Output(UInt(Width.W)) // 异常入口地址（→ 新 PC）
    // mret 端口
    val mret_en   = Input(Bool())
    val mepc_out  = Output(UInt(Width.W)) // mret 目标地址
  })

  val mstatus = RegInit(0.U(Width.W))
  val mtvec   = RegInit(0.U(Width.W))
  val mepc    = RegInit(0.U(Width.W))
  val mcause  = RegInit(0.U(Width.W))

  // 读逻辑
  io.rdata := MuxLookup(io.addr, 0.U)(Seq(
    0x300.U -> mstatus,
    0x305.U -> mtvec,
    0x341.U -> mepc,
    0x342.U -> mcause,
  ))

  // 写逻辑（普通 CSR 指令）
  when(io.wen) {
    switch(io.waddr) {
      is(0x300.U) { mstatus := io.wdata }
      is(0x305.U) { mtvec   := io.wdata }
      is(0x341.U) { mepc    := io.wdata }
      is(0x342.U) { mcause  := io.wdata }
    }
  }

  // 异常触发（ecall）优先级高于普通 CSR 写
  when(io.trap_en) {
    mepc   := io.trap_pc
    mcause := io.trap_cause
  }

  io.mtvec_out := mtvec
  io.mepc_out  := mepc
}
```

#### B. OpcodeCtrl 扩展

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

#### C. PC 控制扩展

`PC_Ctrl` / `mux8_3` 模块需要新增两个 PC 来源：

| sel 值 | PC 来源 |
|--------|--------|
| 原有 0 | PC+4 |
| 原有 1 | 分支目标 |
| 原有 2 | JALR 目标 |
| **新增 3** | `mtvec`（ecall 时跳转到异常入口）|
| **新增 4** | `mepc`（mret 时返回） |

`mux8_3` 已经有 8 个输入，直接连：
```scala
mux8_3_inst.io.in3 := CSRFile_inst.io.mtvec_out  // ecall 入口
mux8_3_inst.io.in4 := CSRFile_inst.io.mepc_out   // mret 返回
// sel 逻辑：
mux8_3_inst.io.sel := MuxCase(0.U, Seq(
  OpcodeCtrlTop_inst.io.trapEn -> 3.U,
  OpcodeCtrlTop_inst.io.mretEn -> 4.U,
  OpcodeCtrlTop_inst.io.branch -> ALU_Top_inst.io.branch_taken,
))
```

#### D. 写回路径扩展

CSR 指令（csrrw 等）将 CSR 旧值写入 rd，需要在 mux2_1_WriteBack 上增加一路：

```scala
// 改为 Mux4_2（4 选 1）或串联多个 Mux2_1
// in0: ALU 结果
// in1: 内存读结果
// in2: CSR 旧值（CSRFile_inst.io.rdata）
```

### 2.2 实现步骤建议

**Step 1：新建 `CSRFile.scala`**  
按 2.1-A 的模板实现，先不接入流水线，单独写一个 chiseltest 单元测试验证读写行为和 trap_en 逻辑。

**Step 2：扩展 `Control.scala` 的 `OpcodeCtrl_I`**  
在 opcode=1110011 的分支中，根据 funct3 和 funct12 产生 `trapEn` / `mretEn` / `csrEn` / `csrOp` 信号。同时在 `OpcodeCtrlTop` 的 io Bundle 中暴露这些新信号。

**Step 3：在 `top.scala` 中实例化 CSRFile 并连线**  
- `CSRFile.trap_pc` ← `PC_Ctrl.pc_out`（ecall 指令的 PC）
- `CSRFile.trap_cause` ← 11.U
- `CSRFile.trap_en` ← `OpcodeCtrlTop.trapEn`
- `CSRFile.mret_en` ← `OpcodeCtrlTop.mretEn`
- `mux8_3.in3` ← `CSRFile.mtvec_out`
- `mux8_3.in4` ← `CSRFile.mepc_out`
- CSR 指令的读数据路径接入写回 Mux

**Step 4：处理流水线级间 stall/flush（如有流水线）**  
ecall/mret 是控制流变化指令，需要清空 IF/ID 流水线寄存器（类似 branch flush），防止后续取入的指令被错误执行。当前 CPU 如使用 InsBuffer 级联方式，需在 ecall/mret 时 flush InsBuffer。

**Step 5：DPI-C 配合验证（difftest）**  
在 `npc_core.cpp` 的 DPI-C 接口中，通过 `dut_regs` 机制同时暴露 CSR 值给 NEMU difftest 对比框架。NEMU 和 NPC 的 CSR 状态可以逐条指令对比。

### 2.3 测试方法

```bash
# 1. 编译 AM yield-test，检查 NEMU 软件模拟是否正常（先验证）
cd am-kernels/tests/am-tests
make ARCH=riscv64-nemu run mainargs=y

# 期望输出：不断打印 'y'（每秒若干次，Ctrl-C 停止）

# 2. 实现 Chisel CSR 后，运行 NPC difftest 模式
cd am-kernels/tests/am-tests
make ARCH=riscv64-nemu run-npc mainargs=y
```

### 2.4 注意事项

- **mret 的 mepc += 4**：当前 NEMU 软件层面 mret 返回 ecall 本身的 PC（AM 的 `simple_trap` 回调不做 +4）。硬件同理，对应 `mepc` 中存的是 ecall 的 PC，每次 mret 又回到 ecall，实现无限 yield 循环，这是正确的。未来实现 `syscall` 时，OS 的 trap handler 会把 `ctx->mepc += 4`，这样 mret 才会跳过 ecall 继续执行。
- **mstatus.MPRV**：`nemu/trap.S` 在保存 Context 后设置了 `mstatus.MPRV = 1`（bit 17），这用于 difftest 时让 KVM 正确读写内存。NPC 硬件一般无需实现此细节（NEMU 是仿真器专有逻辑）。
- **流水线中的 CSR 时序**：CSR 写（如 `csrw mtvec, __am_asm_trap`）必须在 ecall 之前完成。标准流水线需确保写-后-读不发生 hazard（csrw 在 WB 阶段写，ecall 在 ID 阶段读到错误值）——可通过 CSR forwarding 或加一个 bubble 解决。
