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
#include <common.h>
#include <isa.h>
#include <sdb.h>

word_t isa_raise_intr(word_t NO, vaddr_t epc) { // NO: interrupt/exception code; epc: exception program counter
  cpu.mcause = NO;
  cpu.mepc   = epc;
  printf("Raising interrupt NO = %lu, epc = 0x%08lx\n", NO, epc);
  // nemu_state.state = NEMU_STOP;
  record_error(NO, epc);
  return cpu.mtvec; // s->dnpc = cpu.mtvec;
}

#define MAX_ERROR_NUM 16
static char intr_error_str[MAX_ERROR_NUM][128];
static int intr_error_idx = 0;

void record_error(word_t NO, vaddr_t epc) {
  snprintf(intr_error_str[intr_error_idx], sizeof(intr_error_str[intr_error_idx]), "Interrupt NO = %lu, epc = 0x%08lx", NO, epc);
  intr_error_idx = (intr_error_idx + 1) % MAX_ERROR_NUM;
}

void show_error() {
  printf("==== Interrupt/Error Log ====\n");
  int count = intr_error_idx < MAX_ERROR_NUM ? intr_error_idx : MAX_ERROR_NUM;
  for (int i = 0; i < count; i++) {
    printf("%s\n", intr_error_str[i]);
  }
  printf("============================\n");
}

word_t isa_query_intr() {
  return INTR_EMPTY;
}


