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

word_t reg_val[32] = {0};

void stored_gpr() {
  for(int i = 0; i < 32; i ++) {
    reg_val[i] = gpr(i);
  }
}

word_t check_reg(int idx, bool *success) {
  if(gpr(idx) != reg_val[idx]) {
    *success = true;
    return reg_val[idx];
  }
  *success = false;
  return idx;
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
  *success = false;
  return 0;
}

word_t isa_reg_idx2val(int idx) {
  return gpr(idx);
}

const char* isa_reg_idx2str(int idx) {
  return regs[idx];
}
