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
#include <pthread.h>
#include <utils.h>
// #include "reg.h"

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
  printf("Examine memory from address " FMT_WORD ":\n", addr);
  printf("-------------------------------------------------------------------------------------------\n");
  printf("Address\t      0    1    2    3    4    5    6    7    8    9    a    b    c    d    e    f\n");
  printf("-------------------------------------------------------------------------------------------\n");
  for(int i = 0; i < len; i ++) {
    if(i % 16 == 0) {
      printf(FMT_WORD ": ", addr + i);
    }
    word_t data = vaddr_read(addr + i, BYTE);
    printf("0x%02x ", (unsigned)(data & 0xff));
    if(i % 16 == 15) {
      printf("\n");
      printf(FMT_WORD ": ", addr + i - 15);
      for(int j = i - 15; j <= i; j ++) {
        word_t c = vaddr_read(addr + j, BYTE);
        printf("%c    ", (char)c);
      }
      printf("\n");
    }
    else if(i == len - 1) {
      printf("\n");
      printf(FMT_WORD ": ", addr + i - (i % 16));
      for(int j = i - (i % 16); j <= i; j ++) {
        word_t c = vaddr_read(addr + j, BYTE);
        printf("%c    ", (char)c);
      }
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
#define MAX_EXPR_LEN 10
#define MAX_RESULT_LEN 128

typedef struct {
    char expr[MAX_EXPR_LEN * 3];
    sword_t result;
    char info[MAX_RESULT_LEN];
    int success;
} ExprTask;

char python_path[] = "/home/pyx/Workspace/ysyx-workbench/exper-test/varify.py";



void calculate_expr(const char *expr, sword_t *result, char *info, int *success) {
    char cmd[MAX_EXPR_LEN *3 + 128];
    FILE *fp;
    char output[MAX_RESULT_LEN];
    
    // 构造命令：调用 Python 脚本
    snprintf(cmd, sizeof(cmd), "python3 %s \"%s\"", python_path, expr);
    
    // 执行命令并读取输出
    fp = popen(cmd, "r");
    if (fp == NULL) {
        fprintf(stderr, "Failed to run python script\n");
        return;
    }
    
    // 读取结果
    if (fgets(output, sizeof(output), fp) != NULL) {
        // 尝试解析为数字
        int64_t parsed = 0;
        if (sscanf(output, "%" SCNd64, &parsed) == 1) {
            *result = (sword_t)parsed;
            // 成功：output 包含数字字符串，result 被赋值
            pclose(fp);
            return;
        } else {
            // 失败：output 包含错误信息（非数字字符串）
            // 去除换行符
            output[strcspn(output, "\n")] = 0;
            strcpy(info, output);
            *success = 0;
            pclose(fp);
            return;
        }
    }
}

// 线程函数：处理表达式计算
void *expr_worker(void *arg) {
    ExprTask *task = (ExprTask *)arg;
    calculate_expr(task->expr, &task->result, task->info, &task->success);
    return NULL;
}

// void gen_rand_expr(char* expr_buf, int top); {
//   switch (choose(3)) {
//     case 0: gen_num(); break;
//     case 1: gen('('); gen_rand_expr(); gen(')'); break;
//     default: gen_rand_expr(); gen_rand_op(); gen_rand_expr(); break;
//   }
// }
typedef struct ExprNode{
    char member[4];  // 增大以容纳数字、操作符、空格和 '\0'
    struct ExprNode *next;
    struct ExprNode *prev;
}ExprNode;


int choose(int n) {
    return rand() % n;
}

void gen_rand_op(ExprNode* node) {
    int op_idx = choose(4);
    char op;
    switch (op_idx) {
        case 0: op = '+'; break;
        case 1: op = '-'; break;
        case 2: op = '*'; break;
        case 3: op = '/'; break;
        default: op = '+'; break;
    }
    node->member[0] = op;
    node->member[1] = ' ';
    node->member[2] = '\0';
    return;
}


void gen_rand_expr(int* length, ExprNode* node);

void gen_num(ExprNode* node) {
    int num = choose(100);
    if(num == 0 && node->prev != NULL && node->prev->member[0] == '/') {
        num = choose(99) + 1; // avoid divide zero
    }
    sprintf(node->member,  "%2d", num);
    // node->member[2] = ' ';
}

void case_0(ExprNode* node, int *length) {
    (*length)++;
    gen_num(node);
    return;
}

void case_1(ExprNode* node, int *length) {
    if(*length + 3 > MAX_EXPR_LEN) {
        (*length)++;
        gen_num(node);
        return;
    }
    ExprNode* left_node = (ExprNode*)malloc(sizeof(ExprNode));
    ExprNode* right_node = (ExprNode*)malloc(sizeof(ExprNode));
    memset(left_node, 0, sizeof(ExprNode));
    memset(right_node, 0, sizeof(ExprNode));
    *length += 2; // for '(' and ')'
    left_node->member[0] = '(';
    left_node->member[1] = ' ';
    right_node->member[0] = ')';
    right_node->member[1] = ' ';
    if(node->prev != NULL) {
        left_node->prev = node->prev;
        node->prev->next = left_node;
    }else {
        left_node->prev = NULL;
    }
    node->prev = left_node;
    if(node->next != NULL) {
        right_node->next = node->next;
        node->next->prev = right_node;
    }else {
        right_node->next = NULL;
    }
    node->next = right_node;
    left_node->next = node;
    right_node->prev = node;
    
    
    gen_rand_expr(length, node);
    return;
}

void case_2(ExprNode* node, int *length) {
    if(*length + 3 > MAX_EXPR_LEN) {
        (*length)++;
        gen_num(node);
        return;
    }
    ExprNode* left_node = (ExprNode*)malloc(sizeof(ExprNode));
    ExprNode* right_node = (ExprNode*)malloc(sizeof(ExprNode));
    memset(left_node, 0, sizeof(ExprNode));
    memset(right_node, 0, sizeof(ExprNode));
    *length += 1; // for operator
    if(node->prev != NULL) {
        left_node->prev = node->prev;
        node->prev->next = left_node;
    }else {
        left_node->prev = NULL;
    }
    node->prev = left_node;
    if(node->next != NULL) {
        right_node->next = node->next;
        node->next->prev = right_node;
    }else {
        right_node->next = NULL;
    }
    node->next = right_node;
    left_node->next = node;
    right_node->prev = node;
    
    gen_rand_expr(length, left_node);
    gen_rand_op(node);  
    gen_rand_expr(length, right_node);
    return;
}



void gen_rand_expr(int* length, ExprNode* node) {
    switch (choose(3)) {
    case 0: case_0(node, length); break;
    case 1: case_1(node, length); break;
    default: case_2(node, length); break;
    }
}

void build_expr_from_list(ExprNode* center, char* expr_buf) {
    memset(expr_buf, 0, MAX_EXPR_LEN * 3);
    ExprNode* p = center;
    while(p->prev != NULL) {
        p = p->prev;
    }
    int idx = 0;
    // printf("Expression: ");
    while(p->next != NULL) {
        expr_buf[idx++] = p->member[0];
        expr_buf[idx++] = p->member[1];
        // printf("%s ", p->member);
        p = p->next;
        free(p->prev);
    }
    expr_buf[idx++] = p->member[0];
    expr_buf[idx++] = p->member[1];
    // printf("%s\n", p->member);
    free(p);
    expr_buf[idx] = '\0';
    // printf("%s\n", expr_buf);
    
}

static int cmd_test(char *args) {
  pthread_t thread;
  int max = 100;
  if(args != NULL)
  {
    max = atoi(args);
  }
  ExprNode* center = NULL;
  srand(time(NULL));  // 初始化随机数种子
  char EXPR_BUF[max][MAX_EXPR_LEN * 3];
  char Error_INFO[max][MAX_RESULT_LEN];
  int  Error_FLAG[max];
  memset(Error_INFO, 0, sizeof(Error_INFO));
  memset(Error_FLAG, 0, sizeof(Error_FLAG));
  sword_t  results[max][2];
  for (int i = 0; i < max; i++) {
        // printf("----- Generating Expression %d -----\n", i + 1);
        int lenth = 0;
        center = (ExprNode*)malloc(sizeof(ExprNode));
        memset(center, 0, sizeof(ExprNode));
        
        // 动态分配 ExprTask，避免栈上变量被覆盖
        ExprTask *t = (ExprTask*)malloc(sizeof(ExprTask));
        memset(t, 0, sizeof(ExprTask));
        
        gen_rand_expr(&lenth, center);
        printf("center member: %s\n", center->member);
        build_expr_from_list(center, EXPR_BUF[i]);
        strcpy(t->expr, EXPR_BUF[i]);
        t->result = 0;
        t->success = 1;
        
        printf("Generated expr (len=%d): %s\n", lenth, t->expr);
        
        if (pthread_create(&thread, NULL, expr_worker, t) == 0) {
            pthread_join(thread, NULL);
        } else {
          free(t);
          continue;
        }
        printf("%s = %" PRId64 "\n", t->expr, (int64_t)t->result);
        if(t->success == 0) {
          Error_FLAG[i] = 1;
          strcpy(Error_INFO[i], t->info);
          // printf("Python evaluation failed: %s\n", Error_INFO[i]);
          free(t);
          continue;
        }
        bool success = true;
        sword_t nemu_result = expr(t->expr, &success);
        results[i][0] = nemu_result;
        results[i][1] = t->result;
        if(!success) {
          printf("NEMU evaluation failed!\n");
          free(t);
          continue;
        }
        printf("NEMU result: %" PRId64 "\n", (int64_t)nemu_result);
        if(nemu_result != t->result) {
          printf("Mismatch result! NEMU: %" PRId64 ", Python: %" PRId64 "\n",
              (int64_t)nemu_result, (int64_t)t->result);
        } else {
          printf("Match result!\n");
        }
        free(t);
    }
  int missmatch_cnt = 0;
  int missmatch_flag[max];
  memset(missmatch_flag, 0, sizeof(missmatch_flag));
  for(int i = 0; i < max; i ++) {
    printf("----- Expression %d -----\n", i + 1);
    printf("Expression: %s\n", EXPR_BUF[i]);
    printf("NEMU result: %" PRId64 ", Python result: %" PRId64 "\n",
        (int64_t)results[i][0], (int64_t)results[i][1]);

    if(results[i][0] != results[i][1]) {
      printf("Mismatch result!\n");
      missmatch_flag[i] = 1;
      missmatch_cnt ++;
    } else {
      printf("Match result!\n");
    }
    printf("\n");
  }
  if(missmatch_cnt != 0) {
    printf("these expressions mismatched!\n");
    for(int i = 0; i < max; i ++) {
      if(missmatch_flag[i] == 1) {
        printf("----- Expression %d -----\n", i + 1);
        printf("Expression: %s\n", EXPR_BUF[i]);
        printf("NEMU result: %" PRId64 ", Python result: %" PRId64 "\n",
            (int64_t)results[i][0], (int64_t)results[i][1]);
      }
    }
  }
  int error_cnt = 0;
  printf("\n");
  printf("Expressions with Python evaluation errors:\n");
  for(int i = 0; i < max; i ++) {
    if(Error_FLAG[i] == 1) {
      printf("----- Expression %d -----\n", i + 1);
      printf("Expression: %s\n", EXPR_BUF[i]);
      printf("Python evaluation failed: %s\n", Error_INFO[i]);
      error_cnt ++;
    }
  }
  printf("\n");
  printf("Total success: %d, match count: %d, mismatch count: %d\n", max - error_cnt, max - error_cnt - missmatch_cnt, missmatch_cnt);
  return 0;
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

static int cmd_start(char *args) {
#ifdef NPC
  npc_start_trace();
#endif
  printf("Instruction trace started.\n");
  return 0;
}

static int cmd_stop(char *args) {
#ifdef NPC
  npc_stop_trace();
#endif
  printf("Instruction trace stopped.\n");
  return 0;
}


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
  { "q", "Exit NEMU", cmd_q },
  { "si", "Step N instructions exactly", cmd_si },
  { "info", "Display register state or watchpoint information", cmd_info },
  { "x", "Examine memory: x N EXPR", cmd_x },
  { "p", "Evaluate expression EXPR", cmd_p },
  { "w", "Set a watchpoint at expression EXPR, TYPE, FLAG, SETVAL", cmd_w },
  { "d", "Delete watchpoint number N", cmd_d },
  { "test", "A test use python script varify EXPR", cmd_test },
  { "start", "Start instruction trace", cmd_start },
  { "stop", "Stop instruction trace", cmd_stop },
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

void sdb_mainloop() {  // use in nemu/src/engine/interpreter/init.c
  if (is_batch_mode) {
    cmd_c(NULL);
    return;
  }
  update_other_regs();
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
