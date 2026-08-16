/***************************************************************************************
* RISC-V machine CSR state access.
***************************************************************************************/

#include <isa.h>
#include <cpu/cpu.h>

#include "csr.h"

bool riscv_csr_access_ok(unsigned addr) {
  return addr == RISCV_CSR_MSTATUS || addr == RISCV_CSR_MTVEC ||
    addr == RISCV_CSR_MEPC || addr == RISCV_CSR_MCAUSE;
}

word_t riscv_csr_read(unsigned addr) {
  switch (addr) {
    case RISCV_CSR_MSTATUS: return cpu.mstatus;
    case RISCV_CSR_MTVEC: return cpu.mtvec;
    case RISCV_CSR_MEPC: return cpu.mepc;
    case RISCV_CSR_MCAUSE: return cpu.mcause;
    default: panic("Unknown CSR " FMT_WORD, (word_t)addr); return 0;
  }
}

void riscv_csr_write(unsigned addr, word_t value) {
  switch (addr) {
    case RISCV_CSR_MSTATUS: cpu.mstatus = value; return;
    case RISCV_CSR_MTVEC: cpu.mtvec = value; return;
    case RISCV_CSR_MEPC: cpu.mepc = value; return;
    case RISCV_CSR_MCAUSE: cpu.mcause = value; return;
    default: panic("Unknown CSR " FMT_WORD, (word_t)addr); return;
  }
}

void riscv_csr_reset(void) {
  cpu.mstatus = 0;
  cpu.mcause = 0;
  cpu.mepc = 0;
  cpu.mtvec = 0;
}
