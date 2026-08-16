/***************************************************************************************
* RISC-V machine CSR access helpers shared by RV32 and RV64 NEMU.
***************************************************************************************/

#ifndef __RISCV_CSR_H__
#define __RISCV_CSR_H__

#include <common.h>

enum riscv_csr_address {
  RISCV_CSR_MSTATUS = 0x300,
  RISCV_CSR_MTVEC   = 0x305,
  RISCV_CSR_MEPC    = 0x341,
  RISCV_CSR_MCAUSE  = 0x342,
};

bool riscv_csr_access_ok(unsigned addr);
word_t riscv_csr_read(unsigned addr);
void riscv_csr_write(unsigned addr, word_t value);
void riscv_csr_reset(void);

#endif
