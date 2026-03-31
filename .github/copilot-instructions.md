# Copilot Instructions for ysyx-workbench

This is the **"一生一芯" (One Student, One Chip)** engineering project — a full-stack educational platform for learning computer architecture, spanning a software CPU emulator (NEMU), a Chisel-designed hardware CPU (NPC), an OS (nanos-lite), and a machine-independent application layer (abstract-machine).

## Required Environment Variables

These must be set before building anything (add to `~/.bashrc`):

```bash
export NEMU_HOME=/path/to/ysyx-workbench/nemu
export AM_HOME=/path/to/ysyx-workbench/abstract-machine
export NPC_HOME=/path/to/ysyx-workbench/npc
export NVBOARD_HOME=/path/to/ysyx-workbench/nvboard
```

## Architecture Overview

```
am-kernels / navy-apps / nanos-lite      ← applications and OS
        ↓ compile with ARCH=riscv64-{nemu,npc}
  abstract-machine (AM)                  ← machine-independent abstraction layer
        ↓ runs on
  NEMU (software emulator)   NPC (Chisel RTL → Verilator simulation)
```

- **NEMU** (`nemu/`) — RISC-V64 interpreter in C. Supports instruction/function/memory tracing, a built-in debugger (SDB), and differential testing against QEMU/Spike.
- **NPC** (`npc/`) — RISC-V64 CPU written in Chisel (Scala). Compiled via `sbt` → Verilog → Verilator → C++ simulation binary.
- **Abstract-Machine** (`abstract-machine/`) — Minimal hardware abstraction. Programs target `ARCH=riscv64-nemu` or `ARCH=riscv64-npc`. The same binary runs on both.
- **am-kernels** (`am-kernels/`) — Test programs (cpu-tests, alu-tests, am-tests) and benchmarks for validating NEMU and NPC.
- **nanos-lite** (`nanos-lite/`) — Minimal OS kernel running on AM.
- **navy-apps** (`navy-apps/`) — Application suite with ported libc, SDL, NES emulator (fceux), etc.
- **nvboard** (`nvboard/`) — Virtual FPGA development board for logic modules.

## Build Commands

### NEMU

```bash
cd nemu
make menuconfig            # Configure ISA, tracing, difftest options (Kconfig)
make                       # Build (uses config from menuconfig)
make ISA=riscv64 SHARE=1   # Build as .so — used as difftest reference by NPC
```

The built binary is `nemu/build/riscv64-nemu-interpreter` (or `-so` for shared).

### NPC (Chisel CPU)

```bash
cd npc
make chisel-dpi            # Chisel → Verilog (with DPI-C, for simulation)
make chisel-cpu            # Build standalone simulator (depends on chisel-dpi)
make chisel-cpu-lib        # Build as object library for NEMU integration
make run-chisel IMG=path/to/image.bin
make run-difftest IMG=path/to/image.bin   # NPC vs NEMU difftest
make gtk                   # Open GTKWave to view wave.vcd
make clean
```

### Abstract-Machine Programs

```bash
# From any program directory (e.g., am-kernels/tests/cpu-tests, nanos-lite):
make ARCH=riscv64-nemu run    # Build and run on NEMU
make ARCH=riscv64-npc run     # Build and run on NPC
make ARCH=native run          # Build and run natively on host
```

### CPU Tests

```bash
cd am-kernels/tests/cpu-tests

# Run a single test:
make ARCH=riscv64-nemu ALL=add run

# Run on NPC hardware:
make ARCH=riscv64-npc ALL=string run

# Batch run all tests:
make ARCH=riscv64-nemu run
```

## Key Conventions

### NEMU Instruction Implementation (`nemu/src/isa/riscv64/inst.c`)

Instructions are defined with the `INSTPAT` macro using RISC-V bit-pattern strings:

```c
// Pattern: binary bit string with '?' as wildcard, then: name, type, action
INSTPAT("0000000 ????? ????? 000 ????? 01100 11", add, R, R(rd) = src1 + src2);
INSTPAT("??????? ????? ????? 000 ????? 11000 11", beq, B, if (src1 == src2) s->dnpc = s->pc + imm);
```

Types (`TYPE_R/I/S/B/U/J/N`) control which operand decode macros fire. `R(i)` = register access, `Mr`/`Mw` = virtual memory read/write. New instructions follow this same pattern.

CSR operations use `csr_read(addr)` / `csr_write(addr, val)` macros defined locally in `inst.c`. Supported CSRs: `mstatus` (0x300), `mtvec` (0x305), `mepc` (0x341), `mcause` (0x342).

### NPC Chisel Conventions (`npc/chisel/src/main/scala/`)

- CPU is parameterized: `class CPU(Width: Int = 64, Debug: Boolean = false, useDPI: Boolean = false, M_Extension: Boolean = false)`
- Debug signals use `Option`: `val regs_debug = if (Debug) Some(Output(...)) else None`
- DPI-C memory is a `BlackBox` (`DPIMem.scala`); C++ implementation is in `npc/csrc/pmem.cpp` (128 MB static array)
- DPI-C functions: `pmem_read_a`, `pmem_write_a`, `pmem_read_b`, `pmem_write_b`
- To avoid naming conflict with NEMU's `cpu` global, the NPC global is named `npc_cpu`
- C interface for NEMU integration uses `extern "C"` wrappers in `npc/csrc/npc_core.h`

### NPC Pipeline Modules

| Module | Role |
|---|---|
| `Metronome` | Timing / clock distribution |
| `PC_Ctrl` | Program counter control |
| `Decoder` | Instruction decode |
| `InsBuffer` | 128-entry instruction buffer |
| `InsCacheL1` / `DataCacheL1` | L1 caches |
| `OpcodeCtrlTop` | Opcode + ALU control |
| `ImmGenerator` | Immediate field extraction |
| `RegisterFile` | 32×64-bit register file |
| `ALU_Top` | ALU (with optional M-extension) |

### Abstract-Machine Platform Files

Adding a new platform requires `abstract-machine/scripts/<isa>-<platform>.mk`. Existing patterns:
- `riscv64-nemu.mk` — includes `isa/riscv.mk` + `platform/nemu.mk`
- `riscv64-npc.mk` — includes `isa/riscv.mk` + `platform/npc.mk`
- The `ARCH` variable splits on `-` to extract `ISA` and `PLATFORM`.
- Compiler flags for RISC-V: `-march=rv64i_zicsr -mabi=lp64 -mcmodel=medany -mstrict-align`

### Tracing & Debugging (NEMU)

Controlled via `menuconfig` (`CONFIG_ITRACE`, `CONFIG_FTRACE`, `CONFIG_MTRACE`, `CONFIG_DTRACE`). Condition for itrace can be set with `CONFIG_ITRACE_COND`. Difftest reference is selected at config time and compiled in via `CFLAGS`.

### NPC–NEMU Integration Mode

To run NEMU calling NPC for hardware simulation:
1. Build NPC as library: `make -C npc chisel-cpu-lib`
2. Build NEMU with `USENPC=1`: this adds `-DNPC` to `CFLAGS` and links the NPC object files.
3. The `npc/csrc/npc_core.h` C interface (`npc_init`, `npc_single_run`, `npc_get_pc`, etc.) is used by `nemu/src/cpu/cpu-exec.c`.

## Submodules

`am-kernels`, `fceux-am`, `nvboard`, and `npc/VerilogVisualization` are git submodules. After cloning: `git submodule update --init --recursive`. The `capstone` and `spike-diff` tools under `nemu/tools/` are cloned automatically during NEMU's first build.
