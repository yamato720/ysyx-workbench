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

#include "sdb.h"
#include "isa.h"



static WP wp_pool[NR_WP] = {};
static WP *head = NULL, *tail = NULL;

void init_wp_pool() {
  int i;
  for (i = 0; i < NR_WP; i ++) {
    wp_pool[i].busy = false;
    wp_pool[i].NO = 0;
    wp_pool[i].next = NULL;
    wp_pool[i].addr = 0;
    wp_pool[i].type = 0;
    wp_pool[i].old_value = 0;
    wp_pool[i].new_value = 0;
    wp_pool[i].set_value = 0;
    wp_pool[i].set_flag = false;
  }

  head = NULL;
  // free_ = wp_pool;
}

static void show_wp_info(WP* wp) {
  if(wp == NULL) {
    return;
  }
  printf("Num\tAddress\t\tMatch Type\tOld Value\t\tNew Value\t\tSet Value\t\tUse Flag\n");
    char type[8] = "";
    if(wp->addr < 32 + 15)
    {
      sprintf(type, "REG: %s", isa_reg_idx2str(wp->addr));
    } else if(wp->type == BYTE) {
      sprintf(type, "BYTE");
    } else if(wp->type == HALF) {
      sprintf(type, "HALF");
    } else if(wp->type == WORD) {
      sprintf(type, "WORD");
    } else if(wp->type == DWORD) {
      sprintf(type, "DWORD");
    } else {
      sprintf(type, "UNK");
    }
    printf("%d\t0x%08lx\t%s\t\t0x%016lx\t0x%016lx\t0x%016lx\t%s\n", wp->NO, wp->addr, type, wp->old_value, wp->new_value, wp->set_value, wp->set_flag ? "Yes" : "No");
}

WP* new_wp(vaddr_t addr, int type, int flag, word_t setval) {
  WP* wp = NULL;
  wp = find_wp_byAddr(addr);
  if(wp != NULL && (flag == 0 || flag == 1 || flag == 2 || flag ==3)) {
    printf("Watchpoint already exists:\n");
    show_wp_info(wp);
    return NULL;
  } else if(wp != NULL && (flag == 4 || flag == 5 || flag == 6)) {
    printf("Watchpoint no %d updated\n", wp->NO);
    if(flag ==4) // -FS
    {
      printf("Set flag = %s -> %s\n", wp->set_flag ? "Yes" : "No", !wp->set_flag ? "Yes" : "No");
      wp->set_flag = !wp->set_flag;
    }
    else if(flag == 5)// -FV=EXPR
    {
      printf("Set value = 0x%lx -> 0x%lx\n", wp->set_value, setval);
      printf("Set flag = %s -> Yes\n", wp->set_flag ? "Yes" : "No");
      wp->set_value = setval;
      wp->set_flag = 1;
    } else if(flag == 6) { // -FTYPE
      printf("Set type = %d -> %d\n", wp->type, type);
      wp->type = type;
      if(addr < 32 + 15)
      {
        printf("But Register watchpoint don't care data type\n");
        return wp;
      }
      wp->old_value = vaddr_read(addr, type);
      wp->new_value = vaddr_read(addr, type);
    }
    
    return wp;
  }
  for(int i = 0; i < NR_WP; i ++) {
    if(wp_pool[i].busy == false) {
      wp = &wp_pool[i];
    }
  }
  if(wp == NULL) {
    printf("No free watchpoint\n");
    return NULL;
  }
  if(head == NULL) {
    head = wp;
    tail = wp;
    wp->NO = 1;
  } else {
    tail->next = wp;
    wp->NO = tail->NO + 1;
    tail = wp;
  }
  wp->busy = true;
  wp->addr = addr;
  wp->type = type;
  if(addr < 32 + 15)
  {
    wp->old_value = isa_reg_idx2val(addr);
  }else {
    wp->old_value = vaddr_read(addr, type);
  }
  wp->new_value = wp->old_value;
  wp->next = NULL;
  if(flag ==3 || flag == 5) {
    wp->set_flag = true;
    wp->set_value = setval;
  } else if(flag == 2 || flag == 4) {
    wp->set_flag = true;
  } else {
    wp->set_flag = false;
  }

  printf("Created watchpoint %d with info\n", wp->NO);
  show_wp_info(wp);
  return wp;
}

WP* find_wp_byAddr(vaddr_t addr) {
  WP* wp = head;
  while(wp != NULL) {
    if(wp->addr == addr) {
      return wp;
    }
    wp = wp->next;
  }
  return NULL;
}

WP* find_wp_byNO(int no) {
  WP* wp = head;
  while(wp != NULL) {
    if(wp->NO == no) {
      return wp;
    }
    wp = wp->next;
  }
  return NULL;
}

void free_wp_byNO(int no) {
  WP* wp = head;
  WP* prev = NULL;
  bool deleted = false;
  while(wp != NULL) {
    if(wp->NO == no) {
      if(prev == NULL) {
        head = wp->next;
      } else {
        prev->next = wp->next;
      }
      wp->busy = false;
      printf("Deleted watchpoint %d\n", no);
      deleted = true;
      wp = wp->next;
      break;
    }
    prev = wp;
    wp = wp->next;
  }
  if(!deleted) {
    printf("No watchpoint number %d\n", no);
    return;
  }
  while (wp != NULL)
  {
    wp->NO--;
    wp = wp->next;
  }
  return;
  
}

void wp_display() {
  WP* wp = head;
  if(wp == NULL) {
    printf("No watchpoints\n");
    return;
  }
  printf("Num\tAddress\t\tMatch Type\tOld Value\t\tNew Value\t\tSet Value\t\tUse Flag\n");
  while(wp != NULL && wp->busy) {
    char type[8] = "";
    if(wp->addr < 32 + 15)
    {
      sprintf(type, "REG: %s", isa_reg_idx2str(wp->addr));
    }
    else if(wp->type == BYTE) {
      sprintf(type, "BYTE");
    } else if(wp->type == HALF) {
      sprintf(type, "HALF");
    } else if(wp->type == WORD) {
      sprintf(type, "WORD");
    } else if(wp->type == DWORD) {
      sprintf(type, "DWORD");
    } else {
      sprintf(type, "UNK");
    }
    printf("%d\t0x%08lx\t%s\t\t0x%016lx\t0x%016lx\t0x%016lx\t%s\n", wp->NO, wp->addr, type, wp->old_value, wp->new_value, wp->set_value, wp->set_flag ? "Yes" : "No");
    wp = wp->next;
  }
}

bool check_watchpoints() {
  WP* wp = head;
  bool triggered = false;
  while(wp != NULL) {
    if(wp->addr < 32 + 15)
    {
      wp->new_value = isa_reg_idx2val(wp->addr);
    } else {
      wp->new_value = vaddr_read(wp->addr, wp->type);
    }
    if(wp->set_flag) {
      if(wp->new_value == wp->set_value) {
        if(wp->addr < 32 + 15){
          printf("Watchpoint %d triggered at register %s: value match set value 0x%016lx\n", wp->NO, isa_reg_idx2str(wp->addr), wp->set_value);
        }else {
          printf("Watchpoint %d triggered at address 0x%08lx: value match set value 0x%016lx\n", wp->NO, wp->addr, wp->set_value);
        }
        char type[8] = "";
        if(wp->addr < 32 + 15)
        {
          sprintf(type, "REG: %s", isa_reg_idx2str(wp->addr));
        } else if(wp->type == BYTE) {
          sprintf(type, "BYTE");
        } else if(wp->type == HALF) {
          sprintf(type, "HALF");
        } else if(wp->type == WORD) {
          sprintf(type, "WORD");
        } else if(wp->type == DWORD) {
          sprintf(type, "DWORD");
        } else {
          sprintf(type, "UNK");
        }
        if(wp->addr < 32 + 15)
        {
          printf("Match type is REG %s\n", isa_reg_idx2str(wp->addr));
        } else {
          printf("Match type is %s\n", type);
        }
        triggered = true;
      }
    } else {
      if(wp->new_value != wp->old_value) {
        if(wp->addr < 32 + 15){
          printf("Watchpoint %d triggered at register %s: value changed from 0x%016lx to 0x%016lx\n", wp->NO, isa_reg_idx2str(wp->addr), wp->old_value, wp->new_value);
        } else {
          printf("Watchpoint %d triggered at address 0x%08lx: value changed from 0x%016lx to 0x%016lx\n", wp->NO, wp->addr, wp->old_value, wp->new_value);
        }
        char type[8] = "";
        if(wp->addr < 32 + 15)
        {
          sprintf(type, "REG: %s", isa_reg_idx2str(wp->addr));
        } else if(wp->type == BYTE) {
          sprintf(type, "BYTE");
        } else if(wp->type == HALF) {
          sprintf(type, "HALF");
        } else if(wp->type == WORD) {
          sprintf(type, "WORD");
        } else if(wp->type == DWORD) {
          sprintf(type, "DWORD");
        } else {
          sprintf(type, "UNK");
        }
        if(wp->addr < 32 + 15)
        {
          printf("Match type is REG %s\n", isa_reg_idx2str(wp->addr));
        } else {
          printf("Match type is %s\n", type);
        }
        triggered = true;
      }
    }
    wp->old_value = wp->new_value;
    wp = wp->next;
  }
  return triggered;
}

/* TODO: Implement the functionality of watchpoint */

