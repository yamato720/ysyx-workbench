/***************************************************************************************
* RISC-V scalar binary32 execution helpers shared by RV32 and RV64 NEMU.
***************************************************************************************/

#ifndef __RISCV_FPU_H__
#define __RISCV_FPU_H__

#include <common.h>

struct Decode;

bool riscv_f_exec(struct Decode *s);
void riscv_f_reset(void);

#endif
