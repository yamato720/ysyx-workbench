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

// extern int64_t npc_get_pc();
// extern uint32_t npc_get_inst();
// extern int64_t npc_get_reg(int idx);

bool isa_difftest_checkregs(CPU_state *ref_r, vaddr_t pc) {
  #ifndef NPC
  bool success = true;
  for(int i = 0; i < 32; i++) {
    if (ref_r->gpr[i] != cpu.gpr[i]) {
      Log("Mismatch at register %d: expected " FMT_WORD ", got " FMT_WORD,
          i, ref_r->gpr[i], cpu.gpr[i]);
      success = false;
    }
  }
  if (ref_r->pc != cpu.pc) {
    Log("Mismatch at PC: expected " FMT_WORD ", got " FMT_WORD,
        ref_r->pc, cpu.pc);
    success = false;
  }
  return success;
  #else
  // NPC mode: compare with NPC's CPU state
  bool success = true;
  for (int i = 0; i < 32; i++) {
    if (ref_r->gpr[i] != npc_get_reg(i)) {
      Log("Mismatch at register %d: expected " FMT_WORD ", got " FMT_WORD,
          i, ref_r->gpr[i], npc_get_reg(i));
      success = false;
    }
  }
  if (ref_r->pc != npc_get_pc()) {
    Log("Mismatch at PC: expected " FMT_WORD ", got " FMT_WORD,
        ref_r->pc, npc_get_pc());
    success = false;
  }
  #endif
  return success;
}

void isa_difftest_attach() {
}
