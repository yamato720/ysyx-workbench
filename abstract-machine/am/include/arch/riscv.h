#ifndef ARCH_H__
#define ARCH_H__

#ifdef __riscv_e
#define NR_REGS 16
#else
#define NR_REGS 32
#endif

struct Context {
  uintptr_t gpr[NR_REGS];
  uintptr_t mcause, mstatus, mepc; // mcause: interrupt/exception code; mstatus: machine status; mepc: machine exception program counter
  void *pdir;
};

#ifdef __riscv_e
#define GPR1 gpr[15] // a5: syscall number
#else
#define GPR1 gpr[17] // a7: syscall number
#endif

#define GPR2 gpr[10] // a0: arg0
#define GPR3 gpr[11] // a1: arg1
#define GPR4 gpr[12] // a2: arg2
#define GPRx gpr[10] // a0: return value

#endif
