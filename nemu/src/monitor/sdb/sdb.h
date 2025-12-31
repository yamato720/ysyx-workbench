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

#ifndef __SDB_H__
#define __SDB_H__

#include <common.h>
#include <memory/vaddr.h>
// #include "reg.h"

enum { 
  BYTE = 1,
  HALF = 2,
  WORD = 4,
  DWORD = 8
};
#define NR_WP 32

typedef struct watchpoint {
  bool busy;
  int NO;
  struct watchpoint *next;
  vaddr_t addr;
  int type;
  word_t old_value;
  word_t new_value;
  word_t set_value;
  bool   set_flag;

  /* TODO: Add more members if necessary */

} WP;

word_t expr(char *e, bool *success);
WP* new_wp(vaddr_t addr, int type, int flag, word_t setval);
WP* find_wp_byAddr(vaddr_t addr);
WP* find_wp_byNO(int no);
void free_wp_byNO(int no);
void wp_display();

bool check_watchpoints();
void update_other_regs();

#endif
