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

#include <memory/paddr.h>
#include <pthread.h>
#include <time.h>

#ifndef NPC_FPGA_REMOTE
#include <readline/history.h>
#include <readline/readline.h>
#endif

#define MAX_EXPR_LEN 10
#define MAX_RESULT_LEN 128

/* 输入、目标内存和表达式差分测试均由命令实现复用，集中在本基础层。 */
#ifndef NPC_FPGA_REMOTE
char *sdb_gets(void) {
  static char *line_read = NULL;

  if (line_read != NULL) {
    free(line_read);
    line_read = NULL;
  }

  line_read = readline("(nemu) ");
  if (line_read != NULL && *line_read != '\0') add_history(line_read);
  return line_read;
}
#else
char *sdb_gets(void) {
  static char line[4096];
  fputs("(nemu) ", stdout);
  fflush(stdout);
  if (fgets(line, sizeof(line), stdin) == NULL) return NULL;
  line[strcspn(line, "\r\n")] = '\0';
  return line;
}
#endif

vaddr_t hex2hex(char *hex, bool *success) {
  if (hex == NULL || strlen(hex) <= 2 || hex[0] != '0' || hex[1] != 'x') {
    *success = false;
    printf("Invalid hex number\n");
    return 0;
  }

  vaddr_t value = 0;
  for (size_t index = 2; index < strlen(hex); index++) {
    char character = hex[index];
    value <<= 4;
    if (character >= '0' && character <= '9') {
      value += character - '0';
    } else if (character >= 'a' && character <= 'f') {
      value += character - 'a' + 10;
    } else if (character >= 'A' && character <= 'F') {
      value += character - 'A' + 10;
    } else {
      *success = false;
      printf("Invalid hex number\n");
      return 0;
    }
  }
  return value;
}

bool target_memory_read_buffer(vaddr_t address, void *destination, size_t length) {
  if (destination == NULL || address < PMEM_LEFT || address > PMEM_RIGHT ||
      length > (size_t)(PMEM_RIGHT - address) + 1) {
    return false;
  }
#ifdef NPC_FPGA_REMOTE
  extern int npc_read_memory(uint64_t guest_address, void *destination, size_t size);
  return npc_read_memory(address, destination, length) == 0;
#else
  uint8_t *bytes = destination;
  for (size_t index = 0; index < length; index++) {
    bytes[index] = (uint8_t)vaddr_read(address + index, 1);
  }
  return true;
#endif
}

bool target_memory_read(vaddr_t address, int length, word_t *value) {
  if (value == NULL || (length != 1 && length != 2 && length != 4 && length != 8) ||
      length > (int)sizeof(word_t)) return false;
  uint8_t bytes[8] = {0};
  if (!target_memory_read_buffer(address, bytes, (size_t)length)) return false;
  word_t result = 0;
  for (int index = length - 1; index >= 0; index--) result = (result << 8) | bytes[index];
  *value = result;
  return true;
}

typedef struct {
  char expr[MAX_EXPR_LEN * 3];
  sword_t result;
  char info[MAX_RESULT_LEN];
  int success;
} ExprTask;

typedef struct ExprNode {
  char member[4];
  struct ExprNode *next;
  struct ExprNode *prev;
} ExprNode;

static const char python_path[] = "/home/pyx/Workspace/ysyx-workbench/exper-test/varify.py";

static void calculate_expr(const char *expression, sword_t *result, char *info, int *success) {
  char command[MAX_EXPR_LEN * 3 + 128];
  char output[MAX_RESULT_LEN];
  snprintf(command, sizeof(command), "python3 %s \"%s\"", python_path, expression);

  FILE *stream = popen(command, "r");
  if (stream == NULL) {
    fprintf(stderr, "Failed to run python script\n");
    return;
  }

  if (fgets(output, sizeof(output), stream) != NULL) {
    int64_t parsed = 0;
    if (sscanf(output, "%" SCNd64, &parsed) == 1) {
      *result = (sword_t)parsed;
      pclose(stream);
      return;
    }
    output[strcspn(output, "\n")] = 0;
    strcpy(info, output);
    *success = 0;
    pclose(stream);
  }
}

static void *expr_worker(void *argument) {
  ExprTask *task = argument;
  calculate_expr(task->expr, &task->result, task->info, &task->success);
  return NULL;
}

static int choose(int count) {
  return rand() % count;
}

static void gen_rand_op(ExprNode *node) {
  static const char operators[] = {'+', '-', '*', '/'};
  node->member[0] = operators[choose(ARRLEN(operators))];
  node->member[1] = ' ';
  node->member[2] = '\0';
}

static void gen_rand_expr(int *length, ExprNode *node);

static void gen_num(ExprNode *node) {
  int number = choose(100);
  if (number == 0 && node->prev != NULL && node->prev->member[0] == '/') number = choose(99) + 1;
  sprintf(node->member, "%2d", number);
}

static void generate_number(ExprNode *node, int *length) {
  (*length)++;
  gen_num(node);
}

static void generate_parenthesized(ExprNode *node, int *length) {
  if (*length + 3 > MAX_EXPR_LEN) {
    generate_number(node, length);
    return;
  }

  ExprNode *left = malloc(sizeof(*left));
  ExprNode *right = malloc(sizeof(*right));
  memset(left, 0, sizeof(*left));
  memset(right, 0, sizeof(*right));
  *length += 2;
  left->member[0] = '(';
  left->member[1] = ' ';
  right->member[0] = ')';
  right->member[1] = ' ';

  left->prev = node->prev;
  if (node->prev != NULL) node->prev->next = left;
  node->prev = left;
  left->next = node;
  right->next = node->next;
  if (node->next != NULL) node->next->prev = right;
  node->next = right;
  right->prev = node;
  gen_rand_expr(length, node);
}

static void generate_binary(ExprNode *node, int *length) {
  if (*length + 3 > MAX_EXPR_LEN) {
    generate_number(node, length);
    return;
  }

  ExprNode *left = malloc(sizeof(*left));
  ExprNode *right = malloc(sizeof(*right));
  memset(left, 0, sizeof(*left));
  memset(right, 0, sizeof(*right));
  *length += 1;
  left->prev = node->prev;
  if (node->prev != NULL) node->prev->next = left;
  node->prev = left;
  left->next = node;
  right->next = node->next;
  if (node->next != NULL) node->next->prev = right;
  node->next = right;
  right->prev = node;

  gen_rand_expr(length, left);
  gen_rand_op(node);
  gen_rand_expr(length, right);
}

static void gen_rand_expr(int *length, ExprNode *node) {
  switch (choose(3)) {
    case 0: generate_number(node, length); break;
    case 1: generate_parenthesized(node, length); break;
    default: generate_binary(node, length); break;
  }
}

static void build_expr_from_list(ExprNode *center, char *expression) {
  memset(expression, 0, MAX_EXPR_LEN * 3);
  ExprNode *node = center;
  while (node->prev != NULL) node = node->prev;

  int index = 0;
  while (node->next != NULL) {
    expression[index++] = node->member[0];
    expression[index++] = node->member[1];
    node = node->next;
    free(node->prev);
  }
  expression[index++] = node->member[0];
  expression[index++] = node->member[1];
  free(node);
  expression[index] = '\0';
}

int sdb_run_expression_test(char *args) {
  pthread_t thread;
  int max = args == NULL ? 100 : atoi(args);
  char expressions[max][MAX_EXPR_LEN * 3];
  char error_info[max][MAX_RESULT_LEN];
  int error_flags[max];
  sword_t results[max][2];

  memset(error_info, 0, sizeof(error_info));
  memset(error_flags, 0, sizeof(error_flags));
  srand(time(NULL));

  for (int index = 0; index < max; index++) {
    int length = 0;
    ExprNode *center = malloc(sizeof(*center));
    ExprTask *task = malloc(sizeof(*task));
    memset(center, 0, sizeof(*center));
    memset(task, 0, sizeof(*task));
    gen_rand_expr(&length, center);
    printf("center member: %s\n", center->member);
    build_expr_from_list(center, expressions[index]);
    strcpy(task->expr, expressions[index]);
    task->success = 1;
    printf("Generated expr (len=%d): %s\n", length, task->expr);

    if (pthread_create(&thread, NULL, expr_worker, task) != 0) {
      free(task);
      continue;
    }
    pthread_join(thread, NULL);
    printf("%s = %" PRId64 "\n", task->expr, (int64_t)task->result);
    if (task->success == 0) {
      error_flags[index] = 1;
      strcpy(error_info[index], task->info);
      free(task);
      continue;
    }

    bool success = true;
    sword_t nemu_result = expr(task->expr, &success);
    results[index][0] = nemu_result;
    results[index][1] = task->result;
    if (!success) {
      printf("NEMU evaluation failed!\n");
      free(task);
      continue;
    }
    printf("NEMU result: %" PRId64 "\n", (int64_t)nemu_result);
    if (nemu_result != task->result) {
      printf("Mismatch result! NEMU: %" PRId64 ", Python: %" PRId64 "\n",
        (int64_t)nemu_result, (int64_t)task->result);
    } else {
      printf("Match result!\n");
    }
    free(task);
  }

  int mismatch_count = 0;
  int mismatch_flags[max];
  memset(mismatch_flags, 0, sizeof(mismatch_flags));
  for (int index = 0; index < max; index++) {
    printf("----- Expression %d -----\n", index + 1);
    printf("Expression: %s\n", expressions[index]);
    printf("NEMU result: %" PRId64 ", Python result: %" PRId64 "\n",
      (int64_t)results[index][0], (int64_t)results[index][1]);
    if (results[index][0] != results[index][1]) {
      printf("Mismatch result!\n");
      mismatch_flags[index] = 1;
      mismatch_count++;
    } else {
      printf("Match result!\n");
    }
    printf("\n");
  }

  int error_count = 0;
  printf("\nExpressions with Python evaluation errors:\n");
  for (int index = 0; index < max; index++) {
    if (error_flags[index] == 1) {
      printf("----- Expression %d -----\n", index + 1);
      printf("Expression: %s\n", expressions[index]);
      printf("Python evaluation failed: %s\n", error_info[index]);
      error_count++;
    }
  }
  printf("\nTotal success: %d, match count: %d, mismatch count: %d\n",
    max - error_count, max - error_count - mismatch_count, mismatch_count);
  return 0;
}
