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
#include <cpu/cpu.h>
#include <readline/readline.h>
#include <readline/history.h>
#include "sdb.h"

static int is_batch_mode = false;

void init_regex();
void init_wp_pool();

/* We use the `readline' library to provide more flexibility to read from stdin. */
static char* rl_gets() {
  static char *line_read = NULL;

  if (line_read) {
    free(line_read);
    line_read = NULL;
  }

  line_read = readline("(nemu) ");

  if (line_read && *line_read) {
    add_history(line_read);
  }

  return line_read;
}

static int cmd_c(char *args) {
  cpu_exec(-1);
  return 0;
}


static int cmd_q(char *args) {
  quit();
  return -1;
}

static int cmd_si(char *args) {
  int n = 1;
  if (args != NULL) {
    n = atoi(args);
  }
  cpu_exec(n);
  return 0;
}

vaddr_t hex2hex(char* hex, bool *success) {
  if(hex == NULL || strlen(hex) <= 2 || hex[0] != '0' || hex[1] != 'x') {
    *success = false;
    printf("Invalid hex number\n");
    return 0;
  }
  vaddr_t val = 0;
  for(int i = 2; i < strlen(hex); i ++) {
    char c = hex[i];
    val = val << 4;
    if(c >= '0' && c <= '9') {
      val += c - '0';
    } else if(c >= 'a' && c <= 'f') {
      val += c - 'a' + 10;
    } else if(c >= 'A' && c <= 'F') {
      val += c - 'A' + 10;
    } else {
      *success = false;
      printf("Invalid hex number\n");
      return 0;
    }
  }
  return val;
}


static int cmd_x(char *args) {
  if (args == NULL) {
    printf("Usage: x N EXPR\n");
    return 0;
  }
  char *arg1 = strtok(args, " ");
  char *arg2 = strtok(NULL, " ");
  if (arg2 == NULL) {
    printf("Usage: x N EXPR\n");
    return 0;
  }
  // int len = strtol(arg1, NULL, 0);
  int len = atoi(arg1);
  bool success = true;
  vaddr_t addr = expr(arg2, &success);
  if(!success) {
    return 0;
  }
  // printf("arg1: %d, arg2: %lx\n", len, addr);
  if(addr < expr("0x80000000", &success) || addr > expr("0x87FFFFFF", &success)) {
    printf("Invalid address\n");
    printf("Address should be in range 0x80000000 - 0x87FFFFFF\n");
    return 0;
  }
  // printf("0x%08lx: ", addr);
  printf("Examine memory from address 0x%08lx:\n", addr);
  for(int i = 0; i < len; i ++) {
    if(i % 16 == 0) {
      printf("0x%08lx: ", addr + i);
    }
    word_t data = vaddr_read(addr + i, BYTE);
    printf("0x%02lx ", data);
    if(i % 16 == 15) {
      printf("\n");
    }
    else if(i == len - 1) {
      printf("\n");
    }
  }
  return 0;
}

static int cmd_info(char *args) {
  if (args == NULL) {
    printf("Usage: info r/w\n");
    return 0;
  }
  if (strcmp(args, "r") == 0) {
    isa_reg_display();
    return 0;
  }
  else if (strcmp(args, "w") == 0) {
    wp_display();
    return 0;
  }
  else {
    printf("Unknown info command '%s'\n", args);
  }
  return 0;
}

static int cmd_p(char *args) {
  if (args == NULL) {
    printf("Usage: p EXPR TYPE(BYTE HALF WORD DWORD)\n");
    return 0;
  }
  bool success = true;
  word_t result = 0;
  result = expr(args, &success);
  if (success) {
    printf("%s = %ld (0x%lx)\n", args, result, result);
  } else {
    printf("Failed to evaluate expression: %s\n", args);
  }
  return 0;
}
static int cmd_w(char *args) {
  if (args == NULL) {
    printf("Usage: w EXPR FLAG(-x)\n");
    printf("\t-B: Use byte(default)\n");
    printf("\t-H: Use half word\n");
    printf("\t-W: Use word\n");
    printf("\t-D: Use double word\n");
    printf("\t-S: USE set value flag. \n");
    printf("\t-FS: Update watchpoint: flip set value flag\n");
    printf("\t-V=EXPR: Set watch value. Set the watch value flag\n");
    printf("\t-FV=EXPR: Update watch value and set the watch value flag\n");
    printf("\t-FTYPE: Update watchpoint type to TYPE\n");
    return 0;
  }
  bool success = true;
  char *arg0 = strtok(args, " ");
  char *arg1 = strtok(NULL, " ");
  int type = BYTE;
  int flag = 0;
  word_t setval = 0;
  while(arg1 != NULL && arg1[0] == '-') {
      bool isValidFlag = false;
      if(arg1[1] == 'B') {
        type = BYTE;
        isValidFlag = true;
      } else if(arg1[1] == 'H') {
        type = HALF;
        isValidFlag = true;
      } else if(arg1[1] == 'W') {
        type = WORD;
        isValidFlag = true;
      } else if(arg1[1] == 'D') {
        type = DWORD;
        isValidFlag = true;
      } else if(arg1[1] == 'S') {
        flag = 2;
        isValidFlag = true;
      } else if(arg1[1] == 'V'&& arg1[2] == '=') {
        isValidFlag = true;
        char* valstr = &arg1[3];
        setval = expr(valstr, &success);
        if(!success) {
          printf("Failed in setval calculation\n");
          isValidFlag = false;
        }
        flag = 3;
      } 
      else if(arg1[1] == 'F' && arg1[2] == 'S') {
        flag = 4;
        isValidFlag = true;
      }
      else if(arg1[1] == 'F' && arg1[2] == 'V' && arg1[3] == '=') {
        isValidFlag = true;
        char* valstr = &arg1[4];
        setval = expr(valstr, &success);
        if(!success) {
          printf("Failed in setval calculation\n");
          isValidFlag = false;
        }
        flag = 5;
      }
      else if(arg1[1] == 'F' ) {
        isValidFlag = true;
        char typestr = arg1[2];
        if(typestr == 'B') {
          type = BYTE;
        } else if(typestr == 'H') {
          type = HALF;
        } else if(typestr == 'W') {
          type = WORD;
        } else if(typestr == 'D') {
          type = DWORD;
        } else {
          printf("Invalid type in -FTYPE: %c\n", typestr);
          isValidFlag = false;
        }
        flag = 6;
      }
      if(!isValidFlag) {
        printf("Invalid flag: %s\n", arg1);
        return 0;
      }
    arg1 = strtok(NULL, " ");
  }
  vaddr_t addr = expr(arg0, &success);
  if(!success) {
    printf("Failed in addr calculation\n");
    return 0;
  }
  if(addr < expr("0x80000000", &success) || addr > expr("0x87FFFFFF", &success)) {
    printf("Invalid address\n");
    printf("Address should be in range 0x80000000 - 0x87FFFFFF\n");
    return 0;
  }

  printf("Set watchpoint at address 0x%08lx with type %d, flag %d, setval %ld\n", addr, type, flag, setval);
  
  WP* wp = new_wp(addr, type, flag, setval);
  if(wp == NULL) {
    return 0;
  }
  return 0;
}

static int cmd_d(char *args) {
  if (args == NULL) {
    printf("Usage: d N\n");
    return 0;
  }
  int no = atoi(args);
  free_wp_byNO(no);
  return 0;
}

static int cmd_help(char *args);

static struct {
  const char *name;
  const char *description;
  int (*handler) (char *);
} cmd_table [] = {
  { "help", "Display information about all supported commands", cmd_help },
  { "c", "Continue the execution of the program", cmd_c },
  { "q", "Exit NEMU", cmd_q },
  { "si", "Step N instructions exactly", cmd_si },
  { "info", "Display register state or watchpoint information", cmd_info },
  { "x", "Examine memory: x N EXPR", cmd_x },
  { "p", "Evaluate expression EXPR", cmd_p },
  { "w", "Set a watchpoint at expression EXPR, TYPE, FLAG, SETVAL", cmd_w },
  { "d", "Delete watchpoint number N", cmd_d },

  /* TODO: Add more commands */

};

#define NR_CMD ARRLEN(cmd_table)

static int cmd_help(char *args) {
  /* extract the first argument */
  char *arg = strtok(NULL, " ");
  int i;

  if (arg == NULL) {
    /* no argument given */
    for (i = 0; i < NR_CMD; i ++) {
      printf("%s - %s\n", cmd_table[i].name, cmd_table[i].description);
    }
  }
  else {
    for (i = 0; i < NR_CMD; i ++) {
      if (strcmp(arg, cmd_table[i].name) == 0) {
        printf("%s - %s\n", cmd_table[i].name, cmd_table[i].description);
        return 0;
      }
    }
    printf("Unknown command '%s'\n", arg);
  }
  return 0;
}

void sdb_set_batch_mode() {
  is_batch_mode = true;
}

void sdb_mainloop() {
  if (is_batch_mode) {
    cmd_c(NULL);
    return;
  }

  for (char *str; (str = rl_gets()) != NULL; ) {
    char *str_end = str + strlen(str);

    /* extract the first token as the command */
    char *cmd = strtok(str, " ");
    if (cmd == NULL) { continue; }

    /* treat the remaining string as the arguments,
     * which may need further parsing
     */
    char *args = cmd + strlen(cmd) + 1;
    if (args >= str_end) {
      args = NULL;
    }

#ifdef CONFIG_DEVICE
    extern void sdl_clear_event_queue();
    sdl_clear_event_queue();
#endif

    int i;
    for (i = 0; i < NR_CMD; i ++) {
      if (strcmp(cmd, cmd_table[i].name) == 0) {
        if (cmd_table[i].handler(args) < 0) { return; }
        break;
      }
    }

    if (i == NR_CMD) { printf("Unknown command '%s'\n", cmd); }
  }
}

void init_sdb() {
  /* Compile the regular expressions. */
  init_regex();

  /* Initialize the watchpoint pool. */
  init_wp_pool();
}
