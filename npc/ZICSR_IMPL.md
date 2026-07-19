# Zicsr 扩展实现指南

本文档记录 Zicsr（CSR 指令）扩展在 NPC Chisel 设计中的实现方案，包括架构决策、模块拆分建议、写回路径设计，以及与 IO 分发模块的边界划定。

---

## 一、架构决策：寄存器堆统一顶层

### 1.1 为什么 CSR 应和 RegisterFile 整合成 RegisterFileTop

目前代码里 `RegisterFile`（整数寄存器 x0–x31）和 `CSRs`（CSR 寄存器）是两个独立模块，都在 `DataManage.scala` 中。它们在以下几点上有共同的结构角色：

| 维度 | RegisterFile | CSRs | 未来 FPU 寄存器 | 未来向量寄存器 |
|------|-------------|------|----------------|--------------|
| 写回来源 | ALU结果/内存读结果 | CSR 旧值 | FPU 计算结果 | 向量计算结果 |
| 写入时序 | tick_memwb | tick_memwb | tick_memwb | tick_memwb |
| 读取时序 | 组合逻辑 | 组合逻辑 | 组合逻辑 | 组合逻辑 |
| 写入使能 | `OpcodeCtrlTop.regWrite` | `OpcodeCtrlTop.csrRegWrite` | `OpcodeCtrlTop.fpRegWrite` | `OpcodeCtrlTop.vecRegWrite` |

因此，**建议新建 `RegisterFileTop`** 作为统一寄存器堆顶层，通过 Scala 参数控制包含哪些类型：

```
RegisterFileTop(Width, Zicsr, FPU, Vector)
  ├── RegisterFile        整数寄存器 x0–x31（必选）
  ├── CSRs (Zicsr=true)   CSR 寄存器（可选）
  ├── FPRegs (FPU=true)   浮点寄存器 f0–f31（未来）
  └── VecRegs (Vec=true)  向量寄存器 v0–v31（未来）
```

**写回 Mux 也应该移入 `RegisterFileTop`**，使 `top.scala` 只需传入"写回数据候选"，而不需要在顶层做 Mux 选择：

```scala
class RegisterFileTop(Width: Int = 64, Zicsr: Boolean = true,
                      Debug: Boolean = false) extends Module {
  val io = IO(new Bundle {
    // ── 读端口 ───────────────────────────────────────────
    val rs1       = Input(UInt(5.W))
    val rs2       = Input(UInt(5.W))
    val rs1_dout  = Output(UInt(Width.W))
    val rs2_dout  = Output(UInt(Width.W))

    // ── 整数写回 ─────────────────────────────────────────
    val rd        = Input(UInt(5.W))
    val rd_we     = Input(Bool())         // regWrite
    val alu_data  = Input(UInt(Width.W))  // ALU 结果
    val mem_data  = Input(UInt(Width.W))  // 内存读结果
    val wb_sel    = Input(UInt(2.W))      // 00=ALU 01=MEM 10=CSR rdata
    val tick_memwb = Input(Bool())

    // ── CSR 接口（Zicsr=true 时有效）────────────────────
    val csr_addr    = if (Zicsr) Some(Input(UInt(12.W)))  else None
    val csr_we      = if (Zicsr) Some(Input(Bool()))      else None
    val csr_wdata   = if (Zicsr) Some(Input(UInt(Width.W))) else None
    val csr_rdata   = if (Zicsr) Some(Output(UInt(Width.W))) else None
    val trap_en     = if (Zicsr) Some(Input(Bool()))      else None
    val trap_cause  = if (Zicsr) Some(Input(UInt(Width.W))) else None
    val trap_epc    = if (Zicsr) Some(Input(UInt(Width.W))) else None
    val mtvec_out   = if (Zicsr) Some(Output(UInt(Width.W))) else None
    val mret_en     = if (Zicsr) Some(Input(Bool()))      else None
    val mepc_out    = if (Zicsr) Some(Output(UInt(Width.W))) else None

    // ── Debug ────────────────────────────────────────────
    val regs_debug = if (Debug) Some(Output(Vec(32, UInt(Width.W)))) else None
  })

  val rf = Module(new RegisterFile(Width = Width, Debug = Debug))
  // ...（连线略，见 §3.1）

  if (Zicsr) {
    val csr = Module(new CSRs(Width = Width))
    // ...（连线略，见 §3.1）
  }

  // 写回 Mux（在 RegisterFileTop 内完成，top.scala 不需要再做）
  val wb_data = MuxLookup(io.wb_sel, io.alu_data)(Seq(
    0.U -> io.alu_data,
    1.U -> io.mem_data,
    2.U -> (if (Zicsr) csrRdata else 0.U(Width.W)),
  ))
  rf.io.rd_write_din := wb_data
}
```

`top.scala` 中原来的 `mux2_1_WriteBack` 模块可以删除，由 `RegisterFileTop` 内部的 Mux 替代。

---

## 二、IO_Distribute 与 CSR 的边界

### 2.1 IO_Distribute 负责什么

`IO_Distribute`（`Control.scala`）的输入是 **ALU 计算出的物理地址**（`alu_result`，64 位），输出是若干比特的设备使能信号。它的地址空间都是物理内存映射地址（MMIO），例如：

| 设备 | 地址范围 |
|------|---------|
| 主存 | `0x80000000–0x8FFFFFFF` |
| 串口 | `0xA00003F8–0xA00003FF` |
| RTC  | `0xA0000048–0xA000004F` |
| 键盘 | `0xA0000060–0xA0000063` |

### 2.2 CSR 地址为什么不经过 IO_Distribute

CSR 地址（12 位，如 `0x300`/`0x305`/`0x341`/`0x342`）是**指令编码字段**，不是物理内存地址：

- 来源：指令 `imm[31:20]`（I-type 立即数），在 **ID 阶段**由 `OpcodeCtrl_Zicsr` 解码
- 访问：不经过内存总线，不走 ALU 地址路径，不产生任何 `alu_result`
- 时序：CSR 读是组合逻辑（取旧值写回 rd），CSR 写在 WB 节拍
- 对比：`IO_Distribute` 在 EX/MEM 阶段才收到 `alu_result`

所以 **CSR 访问完全绕过 `IO_Distribute`**，直接由 `OpcodeCtrl_Zicsr` → `RegisterFileTop.CSRs` 完成，两条路径互不干扰：

```
CSR 指令路径：
  Decoder.imm[11:0] → OpcodeCtrl_Zicsr.csr_addr → RegisterFileTop.CSRs → rd 写回

内存/MMIO 路径：
  ALU.alu_result → IO_Distribute → DataCacheL1 / Serial / RTC / ...
```

---

## 三、OpcodeCtrl_Zicsr 设计

### 3.1 模块接口

```scala
class OpcodeCtrl_Zicsr extends Module {
  val io = IO(new Bundle {
    val opcode  = Input(UInt(7.W))
    val funct3  = Input(UInt(3.W))
    val funct7  = Input(UInt(7.W))   // 用于区分 ecall vs mret（funct12 高7位）

    val trapEn      = Output(Bool())    // ecall：触发异常，跳 mtvec
    val mretEn      = Output(Bool())    // mret：从异常返回，跳 mepc
    val csrEn       = Output(Bool())    // csrrw/csrrs/csrrc/csrrwi/csrrsi/csrrci
    val csrOp       = Output(UInt(2.W)) // 00=写 01=置位 10=清位
    val csrImm      = Output(Bool())    // true=立即数版本（csrrwi/csrrsi/csrrci）
    val csrRegWrite = Output(Bool())    // CSR 旧值写回 rd（regWrite 语义）
    val sel         = Output(Bool())    // 本模块命中任意 SYSTEM 指令
  })
  // 全部默认 false/0
  val trapEn      = WireDefault(false.B)
  val mretEn      = WireDefault(false.B)
  val csrEn       = WireDefault(false.B)
  val csrOp       = WireDefault(0.U(2.W))
  val csrImm      = WireDefault(false.B)
  val csrRegWrite = WireDefault(false.B)
  val sel         = WireDefault(false.B)

  when(io.opcode === "b1110011".U) {
    sel := true.B
    when(io.funct3 === "b000".U) {
      // ecall: funct7=0000000（funct12=000000000000）
      // mret:  funct7=0011000（funct12=001100000010）
      when(io.funct7 === "b0011000".U) {
        mretEn := true.B
      }.otherwise {
        trapEn := true.B  // ecall（以及 ebreak，暂时同样处理）
      }
    }.otherwise {
      csrEn       := true.B
      csrRegWrite := true.B
      csrImm      := io.funct3(2)  // funct3[2]=1 → 立即数版本（csrrwi/csrrsi/csrrci）
      switch(io.funct3(1, 0)) {
        is("b01".U) { csrOp := "b00".U }  // csrrw / csrrwi
        is("b10".U) { csrOp := "b01".U }  // csrrs / csrrsi
        is("b11".U) { csrOp := "b10".U }  // csrrc / csrrci
      }
    }
  }

  io.trapEn      := trapEn
  io.mretEn      := mretEn
  io.csrEn       := csrEn
  io.csrOp       := csrOp
  io.csrImm      := csrImm
  io.csrRegWrite := csrRegWrite
  io.sel         := sel
}
```

### 3.2 CSR 写数据的计算

CSR 指令的写入值需要在外部（`top.scala` 或 `RegisterFileTop`）根据 `csrOp` 计算，使用 CSR 旧值（`csr_rdata`）和寄存器/立即数操作数：

| 指令 | csrOp | 立即数版本？| 写入值 |
|------|-------|------------|-------|
| csrrw | 00 | 否 | `rs1` |
| csrrs | 01 | 否 | `old \| rs1` |
| csrrc | 10 | 否 | `old & ~rs1` |
| csrrwi | 00 | 是 | `zimm`（rs1字段低5位零扩展） |
| csrrsi | 01 | 是 | `old \| zimm` |
| csrrci | 10 | 是 | `old & ~zimm` |

`zimm` = `rs1` 字段（指令 bits[19:15]），零扩展到 64 位。

```scala
// top.scala 或 RegisterFileTop 内计算 CSR wdata：
val zimm     = Cat(0.U(59.W), instruction(19, 15))
val operand  = Mux(csrImm, zimm, rs1_dout)
val csr_wdata = MuxLookup(csrOp, operand)(Seq(
  "b00".U -> operand,
  "b01".U -> (csr_rdata | operand),
  "b10".U -> (csr_rdata & ~operand),
))
```

### 3.3 OpcodeCtrlTop 的扩展

在 `OpcodeCtrlTop` 中新增 `Zicsr: Boolean = true` 参数，条件实例化 `OpcodeCtrl_Zicsr`，透传信号：

```scala
class OpcodeCtrlTop(Width: Int = 32, M_Extension: Boolean = false,
                    Zicsr: Boolean = true) extends Module {
  val io = IO(new Bundle {
    // ...（现有信号不变）
    // 新增：
    val trapEn      = if (Zicsr) Some(Output(Bool()))    else None
    val mretEn      = if (Zicsr) Some(Output(Bool()))    else None
    val csrEn       = if (Zicsr) Some(Output(Bool()))    else None
    val csrOp       = if (Zicsr) Some(Output(UInt(2.W))) else None
    val csrImm      = if (Zicsr) Some(Output(Bool()))    else None
    val csrRegWrite = if (Zicsr) Some(Output(Bool()))    else None
  })

  // regWrite 需要合并 CSR 写回：
  io.regWrite := std.io.regWrite || mSel ||
    (if (Zicsr) zicsr.io.csrRegWrite else false.B)
}
```

---

## 四、top.scala 变更摘要

完成上述模块后，`top.scala` 需调整：

| 变更 | 内容 |
|------|------|
| 删除 `mux2_1_WriteBack` | 写回 Mux 移入 `RegisterFileTop` |
| 新增 `RegisterFileTop_inst` | 替换原 `RegisterFile_inst`，含 CSRs |
| `wb_sel` 信号 | `00=ALU, 01=MEM, 10=CSR`，由 `memtoReg + csrRegWrite` 组合决定 |
| `mux8_3.in3` | `RegisterFileTop_inst.io.mtvec_out.get` |
| `mux8_3.in4` | `RegisterFileTop_inst.io.mepc_out.get` |
| `mux8_3.sel` | 替换为 `MuxCase`，trapEn→3, mretEn→4, branch_taken→1 优先顺序 |
| `Metronome.stuck` | 已有 `opcode ≠ 1110011` 时才 stall，保持不变 |

### PC 选择器 sel 优先级（完整）

```scala
mux8_3_inst.io.sel := MuxCase(0.U, Seq(
  OpcodeCtrlTop_inst.io.trapEn.get                                    -> 3.U, // ecall（最高优先）
  OpcodeCtrlTop_inst.io.mretEn.get                                    -> 4.U, // mret
  (OpcodeCtrlTop_inst.io.branch && ALU_Top_inst.io.branch_taken)      -> 1.U, // 分支/JAL
  // JALR：branch=true 已在 sel=1 里，但 JALR 的目标是 reg+imm（in2），需再细分
))
// 注：JALR 与 B/JAL 都用 branch=true，区分方式是 aluop（0111=JALR）
// 可在 OpcodeCtrlTop 加 jalrEn 信号，或者直接在 MuxCase 里判断 aluop
```

---

## 五、实现顺序建议

| 步骤 | 文件 | 内容 |
|------|------|------|
| 1 | `Control.scala` | 新增 `OpcodeCtrl_Zicsr`，扩展 `OpcodeCtrlTop` |
| 2 | `DataManage.scala` | 新增 `RegisterFileTop`（含 CSRs + 写回 Mux） |
| 3 | `top.scala` | 替换 `RegisterFile_inst` + `mux2_1_WriteBack`，接入 mux8_3.in3/in4，更新 sel 逻辑 |
| 4 | `abstract-machine/am/src/riscv/npc/cte.c` | 补全 `__am_irq_handle`（case 11 + mepc+=4，参考 nemu 版） |
| 5 | 测试 | `make -C am-kernels/tests/cpu-tests run ALL=add config=NpcDpiConfig` |
