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
        ↓ compile with ARCH=riscv{32,64}-nemu
  abstract-machine (AM)                  ← machine-independent abstraction layer
        ↓ loads a Config-owned construction
  NEMU host + Verilator NPC/SoC, or an FPGA backend
```

- **NEMU** (`nemu/`) — RISC-V64 interpreter in C. Supports instruction/function/memory tracing, a built-in debugger (SDB), and differential testing against QEMU/Spike.
- **NPC** (`npc/`) — Config-parameterized RISC-V CPU and FPGA harnesses written in Chisel. A terminal Scala Config owns the hardware ABI, backend, and construction directory.
- **Abstract-Machine** (`abstract-machine/`) — Minimal hardware abstraction. Config-driven NPC/SoC runs use the `riscv32-nemu` or `riscv64-nemu` image ABI and a saved NEMU host from the selected construction.
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

### NPC Constructions

```bash
make -C npc config-list
make -C npc build config=NpcDpiConfig
make -C npc build config=U55cYsyxSocFpgaConfig
make -C npc version
```

Generated ABI, logs, profiles, and FPGA assets live under
`npc/constructions/<Config-FQCN>/`. Low-level Chisel and FPGA Make goals are
internal construction steps and are not public interfaces.

### Abstract-Machine Programs

```bash
# From an ordinary AM program directory:
make ARCH=riscv64-nemu run    # Build and run on a regular NEMU
make ARCH=native run          # Build and run natively on host

# Config-driven hardware runs use the cpu-tests entry below.
```

### CPU Tests

```bash
cd am-kernels/tests/cpu-tests

# Run one test on a Verilator NPC construction (ARCH is inferred):
make run ALL=add config=NpcDpiConfig

# Run a batch on a SoC construction:
make run-bat ALL="add div" config=YsyxSimulationConfig

# Reuse a saved construction by its stable short version index:
make run ALL=add version=1
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

### NPC Chisel Conventions

- Core RTL lives under `npc/chisel/rv-core/`; FPGA shells live under `npc/chisel/fpga-harness/`.
- Terminal Configs live under `npc/chisel/configs/` and compose reusable fragments with CDE `++`.
- Make-selectable Configs are discovered automatically and must have a complete, no-argument construction class.
- DPI-C memory is a `BlackBox`; C++ implementation is in `npc/csrc/pmem.cpp`.
- DPI-C functions: `pmem_read_a`, `pmem_write_a`, `pmem_read_b`, `pmem_write_b`
- To avoid naming conflict with NEMU's `cpu` global, the NPC global is named `npc_cpu`
- C interface for NEMU integration uses `extern "C"` wrappers in `npc/csrc/npc_core.h`

### NPC Pipeline Modules

| Module | Role |
|---|---|
| `NpcFrontend` | Fetch and decode coordination |
| `NpcBackend` | Execute, memory, CSR, and writeback coordination |
| `InstructionFetch` | Instruction-side AXI fetch path |
| `NpcDecode` | Instruction decode and control generation |
| `IntegerAlu` / `MulDivAlu` | Integer and M-extension execution |
| `FloatingAlu` | F-extension execution |
| `NpcMemoryFabric` | Internal/external memory routing |
| `RegisterFile` / `FloatingRegisterFile` | Integer and floating-point register files |

### Abstract-Machine Platform Files

Adding a new platform requires `abstract-machine/scripts/<isa>-<platform>.mk`. Existing patterns:
- `riscv64-nemu.mk` — includes `isa/riscv.mk` + `platform/nemu.mk`
- Direct `*-npc` AM platforms have been removed; hardware simulation and FPGA hosts are selected by Scala Config.
- The `ARCH` variable splits on `-` to extract `ISA` and `PLATFORM`.
- Compiler flags for RISC-V: `-march=rv64i_zicsr -mabi=lp64 -mcmodel=medany -mstrict-align`

### Tracing & Debugging (NEMU)

Controlled via `menuconfig` (`CONFIG_ITRACE`, `CONFIG_FTRACE`, `CONFIG_MTRACE`, `CONFIG_DTRACE`). Condition for itrace can be set with `CONFIG_ITRACE_COND`. Difftest reference is selected at config time and compiled in via `CFLAGS`.

### NPC–NEMU Integration Mode

To run NEMU calling NPC for hardware simulation:
1. Select a terminal Config, for example `NpcDpiConfig` or `YsyxSimulationConfig`.
2. Let `make run` or `make run-bat` create or refresh the construction atomically.
3. The saved NEMU executable loads the Verilator ABI from that same construction; current-tree objects are never mixed into a saved construction.
4. The `npc/csrc/npc_core.h` C interface (`npc_init`, `npc_single_run`, `npc_get_pc`, etc.) is used by `nemu/src/cpu/cpu-exec.c`.

## Submodules

`am-kernels`, `fceux-am`, `nvboard`, and `npc/VerilogVisualization` are git submodules. After cloning: `git submodule update --init --recursive`. The `capstone` and `spike-diff` tools under `nemu/tools/` are cloned automatically during NEMU's first build.
