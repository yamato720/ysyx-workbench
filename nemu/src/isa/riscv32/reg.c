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
#include "local-include/reg.h"

const char *regs[] = {
  "$0", "ra", "sp", "gp", "tp", "t0", "t1", "t2",
  "s0", "s1", "a0", "a1", "a2", "a3", "a4", "a5",
  "a6", "a7", "s2", "s3", "s4", "s5", "s6", "s7",
  "s8", "s9", "s10", "s11", "t3", "t4", "t5", "t6"
};
const char *other_regs[] = {
  "pc", "mstatus", "sstatus", "mepc", "sepc", "mtvec", "stvec",
  "mcause", "scause", "mtval", "stval", "mip", "sip", "mie", "sie"
};

word_t other_regs_val[] = {
  0, 0, 0, 0, 0, 0, 0,
  0, 0, 0, 0, 0, 0, 0, 0
};

word_t other_regs_val_stored[] = {
  0, 0, 0, 0, 0, 0, 0,
  0, 0, 0, 0, 0, 0, 0, 0
};

word_t reg_val[32] = {0};

void stored_gpr() {
  for(int i = 0; i < 32; i ++) {
    reg_val[i] = gpr(i);
  }
  other_regs_val_stored[0] = cpu.pc;
}

void update_other_regs() {
  other_regs_val[0] = cpu.pc;
  // other_regs_val[1] = cpu.mstatus.val;
  // other_regs_val[2] = cpu.sstatus.val;
  // other_regs_val[3] = cpu.mepc;
  // other_regs_val[4] = cpu.sepc;
  // other_regs_val[5] = cpu.mtvec;
  // other_regs_val[6] = cpu.stvec;
  // other_regs_val[7] = cpu.mcause;
  // other_regs_val[8] = cpu.scause;
  // other_regs_val[9] = cpu.mtval;
  // other_regs_val[10] = cpu.stval;
  // other_regs_val[11] = cpu.mip.val;
  // other_regs_val[12] = cpu.sip.val;
  // other_regs_val[13] = cpu.mie.val;
  // other_regs_val[14] = cpu.sie.val;
}

void check_reg(int idx, bool *success) {
  if(idx < 32) {
    if(gpr(idx) != reg_val[idx]) {
      *success = true;
      return ;
    }
    *success = false;
    return ;
  }
  else if(idx - 32 < 15) {
    if(other_regs_val[idx - 32] != other_regs_val_stored[idx - 32]) {
      *success = true;
      return ;
    }
    *success = false;
    return ;
  } else {
    *success = false;
    return ;
  }
}


void isa_reg_display() {
  for(int i = 0; i < 32; i ++) {
    bool success = false;
    // word_t val = isa_reg_str2val(regs[i], &success);
    // if(!success) {
    //   printf("Cannot find register %s\n", regs[i]);
    //   return;
    // }
    check_reg(i, &success);
    if(success) {
      printf("%s:0x%08lx <-- 0x%08lx\t", regs[i], reg_val[i], gpr(i));
    } else {
    printf("%s:0x%08lx\t\t\t", regs[i], gpr(i));}
    if(i % 4 == 3) {
      printf("\n");
    }
  }
  printf("here are other regs:\n");
  for(int i = 0; i < 1; i ++) { // currently only pc is supported
    bool success = false;
    check_reg(i + 32, &success);
    if(success) {
      printf("%s:0x%08lx <-- 0x%08lx\t", other_regs[i], other_regs_val_stored[i], other_regs_val[i]);
    } else {
      printf("%s:0x%08lx\t\t\t", other_regs[i], other_regs_val[i]);
    }
    if(i % 3 == 2) {
      printf("\n");
    }
  }
  printf("\n");
}

word_t isa_reg_str2val(const char *s, bool *success, int *idx) {
  for(int i = 0; i < 32; i ++) {
    if(strcmp(s, regs[i]) == 0) {
      *success = true;
      *idx = i;
      // printf("%s\n%s\n", s, regs[i]);
      // printf("Find register %s\n", regs[i]);
      return gpr(i);
    }
  }
  // printf("Other regs that not in registers file\n");
  for(int i = 0; i < 15; i ++) { 
    if(strcmp(s, other_regs[i]) == 0) {
      *success = true;
      *idx = i + 32;
      // printf("%s\n%s\n", s, other_regs[i]);
      // printf("Find other register %s\n", other_regs[i]);
      return other_regs_val[i];
    }
  }
  *success = false;
  return 0;
}

word_t isa_reg_idx2val(int idx) {
  if(idx < 32)
    return gpr(idx);
  else if(idx - 32 < 15)
    return other_regs_val[idx - 32];
  else
    return 0;
}



const char* isa_reg_idx2str(int idx) {
  if(idx < 32)
    return regs[idx];
  else if(idx - 32 < 15)
    return other_regs[idx - 32];
  else
    return "UNK";
}
