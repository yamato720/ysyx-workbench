/***************************************************************************************
* RISC-V machine and floating CSR state access.
***************************************************************************************/

#include <isa.h>
#include <cpu/cpu.h>

#include "csr.h"

enum {
  FFLAGS_MASK = 0x1f,
  MSTATUS_FS_MASK = 0x00006000,
  MSTATUS_FS_DIRTY = 0x00006000,
};

static bool is_floating_csr(unsigned addr) {
  return addr == RISCV_CSR_FFLAGS || addr == RISCV_CSR_FRM || addr == RISCV_CSR_FCSR;
}

bool riscv_csr_f_enabled(void) {
#ifdef CONFIG_RISCV_F
  return (cpu.mstatus & MSTATUS_FS_MASK) != 0;
#else
  return false;
#endif
}

bool riscv_csr_access_ok(unsigned addr) {
  return !is_floating_csr(addr) || riscv_csr_f_enabled();
}

unsigned riscv_csr_frm(void) {
  return cpu.frm & 0x7;
}

void riscv_csr_mark_f_dirty(void) {
  cpu.mstatus = (cpu.mstatus & ~MSTATUS_FS_MASK) | MSTATUS_FS_DIRTY;
}

void riscv_csr_or_fflags(uint32_t flags) {
  cpu.fflags |= flags & FFLAGS_MASK;
}

word_t riscv_csr_read(unsigned addr) {
  switch (addr) {
    case RISCV_CSR_FFLAGS: return cpu.fflags & FFLAGS_MASK;
    case RISCV_CSR_FRM: return riscv_csr_frm();
    case RISCV_CSR_FCSR: return (riscv_csr_frm() << 5) | (cpu.fflags & FFLAGS_MASK);
    case RISCV_CSR_MSTATUS: return cpu.mstatus;
    case RISCV_CSR_MTVEC: return cpu.mtvec;
    case RISCV_CSR_MEPC: return cpu.mepc;
    case RISCV_CSR_MCAUSE: return cpu.mcause;
    default: panic("Unknown CSR " FMT_WORD, (word_t)addr); return 0;
  }
}

void riscv_csr_write(unsigned addr, word_t value) {
  switch (addr) {
    case RISCV_CSR_FFLAGS:
      cpu.fflags = value & FFLAGS_MASK;
      riscv_csr_mark_f_dirty();
      return;
    case RISCV_CSR_FRM:
      cpu.frm = value & 0x7;
      riscv_csr_mark_f_dirty();
      return;
    case RISCV_CSR_FCSR:
      cpu.fflags = value & FFLAGS_MASK;
      cpu.frm = (value >> 5) & 0x7;
      riscv_csr_mark_f_dirty();
      return;
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
  cpu.fflags = 0;
  cpu.frm = 0;
}
