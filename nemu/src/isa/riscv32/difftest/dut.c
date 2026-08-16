/***************************************************************************************
* Copyright (c) 2014-2024 Zihao Yu, Nanjing University
*
* NEMU is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

#include <isa.h>
#include <cpu/difftest.h>
#include "../local-include/reg.h"

void riscv_difftest_pack(riscv_difftest_state_t *state) {
  memset(state, 0, sizeof(*state));
  for (int i = 0; i < RISCV_GPR_NUM; i++) state->gpr[i] = cpu.gpr[i];
  state->pc = cpu.pc;
  state->mstatus = cpu.mstatus;
}

void riscv_difftest_unpack(const riscv_difftest_state_t *state) {
  for (int i = 0; i < RISCV_GPR_NUM; i++) cpu.gpr[i] = state->gpr[i];
  cpu.gpr[0] = 0;
  cpu.pc = state->pc;
  cpu.mstatus = state->mstatus;
}

bool isa_difftest_checkregs(riscv_difftest_state_t *ref_r, vaddr_t pc) {
#ifndef NPC
  riscv_difftest_state_t dut;
  riscv_difftest_pack(&dut);
#define DUT_GPR(i) dut.gpr[i]
#define DUT_PC dut.pc
#define DUT_MSTATUS dut.mstatus
#else
  extern uint64_t npc_get_reg(int idx);
  extern uint64_t npc_get_mstatus(void);
  extern uint64_t npc_get_pc(void);
#define DUT_GPR(i) npc_get_reg(i)
#define DUT_PC npc_get_pc()
#define DUT_MSTATUS npc_get_mstatus()
#endif
  for (int i = 0; i < RISCV_GPR_NUM; i++) {
    if (ref_r->gpr[i] != DUT_GPR(i)) {
      Log("GPR x%d differs after " FMT_WORD ": ref=0x%016" PRIx64 ", dut=0x%016" PRIx64,
          i, pc, ref_r->gpr[i], (uint64_t)DUT_GPR(i));
      return false;
    }
  }
  if (ref_r->pc != DUT_PC) {
    Log("PC differs after " FMT_WORD ": ref=0x%016" PRIx64 ", dut=0x%016" PRIx64,
        pc, ref_r->pc, (uint64_t)DUT_PC);
    return false;
  }
  if (ref_r->mstatus != DUT_MSTATUS) {
    Log("mstatus differs after " FMT_WORD ": ref=0x%016" PRIx64 ", dut=0x%016" PRIx64,
        pc, ref_r->mstatus, (uint64_t)DUT_MSTATUS);
    return false;
  }
  return true;
#undef DUT_GPR
#undef DUT_PC
#undef DUT_MSTATUS
}

void isa_difftest_attach() {
}
