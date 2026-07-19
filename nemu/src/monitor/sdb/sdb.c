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
#include "sdb.h"
#include <errno.h>
#include <limits.h>
#include <utils.h>
// #include "reg.h"

static int is_batch_mode = false;

static int cmd_c(char *args) {
  cpu_exec(-1);
  return 0;
}

#ifdef NPC
static int cmd_perf(char *args) {
  if (args != NULL) {
    printf("Usage: perf\n");
    return 0;
  }
  npc_print_performance();
  return 0;
}
#endif

#ifdef NPC_SOC
static int cmd_cpi(char *args) {
  if (args != NULL) {
    printf("Usage: cpi\n");
    return 0;
  }
  npc_soc_print_cpi();
  return 0;
}

static int cmd_ipc(char *args) {
  if (args != NULL) {
    printf("Usage: ipc\n");
    return 0;
  }
  npc_soc_print_ipc();
  return 0;
}
#endif


static int cmd_q(char *args) {
#ifdef NPC_FPGA_REMOTE
  extern int npc_debug_halt(void);
  extern bool npc_debug_is_halted(void);
  if (!npc_debug_is_halted() && npc_debug_halt() != 0)
    fprintf(stderr, "failed to halt FPGA before exit: %s\n", strerror(errno));
#endif
  quit();
  return -1;
}

static int cmd_si(char *args) {
  int n = 1;
  if (args != NULL) {
    char *end = NULL;
    long parsed = strtol(args, &end, 0);
    if (end == args || *end != '\0' || parsed <= 0 || parsed > INT_MAX) {
      printf("Usage: si [positive count]\n");
      return 0;
    }
    n = (int)parsed;
  }
  cpu_exec(n);
  return 0;
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
  if (len <= 0 || len > 1024 * 1024) {
    printf("Length must be in range 1..1048576\n");
    return 0;
  }
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
  printf("Examine memory from address " FMT_WORD ":\n", addr);
  printf("-------------------------------------------------------------------------------------------\n");
  printf("Address\t      0    1    2    3    4    5    6    7    8    9    a    b    c    d    e    f\n");
  printf("-------------------------------------------------------------------------------------------\n");
  uint8_t *memory = malloc((size_t)len);
  if (memory == NULL || !target_memory_read_buffer(addr, memory, (size_t)len)) {
    free(memory);
    printf("Cannot read target RAM at " FMT_WORD "\n", addr);
    return 0;
  }
  for(int i = 0; i < len; i ++) {
    if(i % 16 == 0) {
      printf(FMT_WORD ": ", addr + i);
    }
    word_t data = memory[i];
    printf("0x%02x ", (unsigned)(data & 0xff));
    if(i % 16 == 15) {
      printf("\n");
      printf(FMT_WORD ": ", addr + i - 15);
      for(int j = i - 15; j <= i; j ++) {
        word_t c = memory[j];
        printf("%c    ", (char)c);
      }
      printf("\n");
    }
    else if(i == len - 1) {
      printf("\n");
      printf(FMT_WORD ": ", addr + i - (i % 16));
      for(int j = i - (i % 16); j <= i; j ++) {
        word_t c = memory[j];
        printf("%c    ", (char)c);
      }
      printf("\n");
    }
  }
  free(memory);
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
  } else if(strcmp(args, "e") == 0) {
    show_error();
    return 0;
  }
  #ifdef CONFIG_TRACE
  else if (strcmp(args, "i") == 0) {
    // Display instruction ring buffer
    show_iringbuf();
    return 0;
  }else if(strcmp(args, "m") == 0) {
    // Display memory access log
    #ifdef NPC
    npc_display_mem_access();
    #else
    show_mem_access();
    #endif
    return 0;
  }else if(strcmp(args, "f") == 0) {
    // Display function trace
    display_ftrace();
    return 0;
  }else if(strcmp(args, "d") == 0) {
    // Display device access log
    show_device_access();
    return 0;
  }
  #endif
  else {
    printf("Unknown info command '%s'\n", args);
  }
  return 0;
}
static int cmd_test(char *args) {
  return sdb_run_expression_test(args);
}


static int cmd_p(char *args) {
  if (args == NULL) {
    printf("Usage: p EXPR TYPE(BYTE HALF WORD DWORD)\n");
    return 0;
  }
  bool success = true;
  sword_t result = 0;
  result = expr(args, &success);
  if (success) {
    printf("%s = %" PRId64 " (" FMT_WORD ")\n", args, (int64_t)result, (word_t)result);
  } else {
    printf("Failed to evaluate expression: %s\n", args);
  }
  return 0;
}
static int cmd_w(char *args) {
  if (args == NULL) {
    printf("Usage: w EXPR FLAG(-x)\n");
    printf("\tEXPR: Address or register name, only use $ to specify register\n");
    printf("\t-B: Use byte(default)\n");
    printf("\t-H: Use half word\n");
    printf("\t-W: Use word\n");
    printf("\t-D: Use double word\n");
    printf("\t-S: USE set value flag. \n");
    printf("\t-FS: Update watchpoint: flip set value flag\n");
    printf("\t-V=EXPR: Set watch value. Set the watch value flag\n");
    printf("\t-FV=EXPR: Update watch value and set the watch value flag\n");
    printf("\t-FTYPE: Update watchpoint type to TYPE\n"); // finished
    // printf("\t-R: Specify that the watchpoint is set on a register, stop when change\n");
    // printf("\t-R=EXPR: Set watchpoint at register, EXPR is value that register should save, stop when equal\n ");
    // printf("\t-RS=Number: Flip the register watchpoint stop condition\n");
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
  if(arg0[0] == '$'){
    bool reg_success = true;
    // printf("$\n");
    int idx = 0;
    // flag = 1; // register watchpoint
    isa_reg_str2val(arg0 + 1, &reg_success, &idx);
    if(!reg_success) {
      printf("Invalid register name: %s\n", arg0);
      return 0;
    }
    // printf("%d\n", idx);
    WP* wp = new_wp(idx, type, flag, setval);
    if(wp == NULL) {
      return 0;
    }
    return 0;
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

  printf("Set watchpoint at address " FMT_WORD " with type %d, flag %d, setval " FMT_WORD "\n",
      addr, type, flag, setval);
  
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

#if defined(NPC) && defined(NPC_VCD_TRACE)
static int cmd_start(char *args) {
  npc_start_trace();
  printf("Instruction trace started.\n");
  return 0;
}

static int cmd_stop(char *args) {
  npc_stop_trace();
  printf("Instruction trace stopped.\n");
  return 0;
}
#endif


static int cmd_bat(char *args) {
  // #ifdef CONFIG_DIFFTEST

  for(unsigned int i = 0; i <= 0xffffffff; i ++) {
    cmd_si("1");
    if(nemu_state.state == NEMU_ABORT || nemu_state.state == NEMU_END) {
      printf("next use si %d for skip\n", i);
      break;
    }
  }
  return 0;

  // #else
  // int num = args == NULL ? 1000 : atoi(args);

  // if(!is_pc_watchpoint_triggered()) {
  //   printf("PC watchpoint is not triggered, setting PC for skip to that value\n");
  //   return 0;
  // }
  // for(int i = 0; i < num; i ++) {
  //   cmd_c(NULL);
  //   if(nemu_state.state != NEMU_RUNNING) {
  //     printf("next use num %d for skip\n", i);
  //     break;
  //   }
  // }
  // return 0;
  // printf("Skipping to wrong instruction need difftest!\n");
  
  
  
  // return 0;
  // #endif

}

static int cmd_help(char *args);

static struct {
  const char *name;
  const char *description;
  int (*handler) (char *);
} cmd_table [] = {
  { "help", "Display information about all supported commands", cmd_help },
  { "c", "Continue the execution of the program", cmd_c },
#ifdef NPC
  { "perf", "Show NPC performance and the latest commit interval", cmd_perf },
  { "pref", "Alias for perf", cmd_perf },
#endif
#ifdef NPC_SOC
  { "cpi", "Show NPC SoC cycles per committed instruction", cmd_cpi },
  { "ipc", "Show NPC SoC instructions per cycle and 300 MHz MIPS", cmd_ipc },
#endif
  { "q", "Exit NEMU", cmd_q },
  { "si", "Step N instructions exactly", cmd_si },
  { "info", "Display register state or watchpoint information", cmd_info },
  { "x", "Examine memory: x N EXPR", cmd_x },
  { "p", "Evaluate expression EXPR", cmd_p },
  { "w", "Set a watchpoint at expression EXPR, TYPE, FLAG, SETVAL", cmd_w },
  { "d", "Delete watchpoint number N", cmd_d },
  { "test", "A test use python script varify EXPR", cmd_test },
#if defined(NPC) && defined(NPC_VCD_TRACE)
  { "start", "Start NPC VCD trace", cmd_start },
  { "stop", "Stop NPC VCD trace", cmd_stop },
#endif
  { "bat", "Batch mode: execute next N instructions if PC watchpoint is triggered", cmd_bat },

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

bool sdb_is_batch_mode(void) { return is_batch_mode; }

void sdb_mainloop() {  // use in nemu/src/engine/interpreter/init.c
  if (is_batch_mode) {
    cmd_c(NULL);
    return;
  }
  update_other_regs();
  for (char *str; (str = sdb_gets()) != NULL; ) {
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
        if (cmd_table[i].handler(args) < 0) { return; }  // do function
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

  // update_other_regs();
}
