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

/* We use the POSIX regex functions to process regular expressions.
 * Type 'man regex' for more information about POSIX regex functions.
 */
#include <regex.h>
#include "../../isa/riscv64/local-include/reg.h"
#include "sdb.h"

enum {
  TK_NOTYPE = 256, TK_EQ, TK_NEQ, AND, NUM, HEX_NUM, REG_NAME,

  /* TODO: Add more token types */

};

static struct rule {
  const char *regex;
  int token_type;
} rules[] = {

  /* TODO: Add more rules.
   * Pay attention to the precedence level of different rules.
   */

  {" +", TK_NOTYPE},    // spaces
  {"\\+", '+'},         // plus
  {"==", TK_EQ},        // equal
  {"-", '-'},           // minus
  {"\\*", '*'},         // multiply
  {"/", '/'},           // divide
  {"\\(", '('},         // left parenthesis
  {"\\)", ')'},         // right parenthesis
  {"[0-9]+", NUM},      // number
  {"[xX][0-9a-fA-F]+", HEX_NUM},      // hex number，read number 0 then check x 
  {"\\$(\\$0)|(ra)|(sp)|(gp)|(tp)|(t0-6)|(s0-11)|(a0-7)", REG_NAME},     // register name
  {"!=", TK_NEQ},    // not equal
  {"&&", AND},      // and
};

#define NR_REGEX ARRLEN(rules)

static regex_t re[NR_REGEX] = {};

/* Rules are used for many times.
 * Therefore we compile them only once before any usage.
 */
void init_regex() {
  int i;
  char error_msg[128];
  int ret;

  for (i = 0; i < NR_REGEX; i ++) {
    ret = regcomp(&re[i], rules[i].regex, REG_EXTENDED);
    if (ret != 0) {
      regerror(ret, &re[i], error_msg, 128);
      panic("regex compilation failed: %s\n%s", error_msg, rules[i].regex);
    }
  }
}

typedef struct token {
  int type;
  word_t member;
  bool otherwise;
} Token;

static Token tokens[32] __attribute__((used)) = {};
static int nr_token __attribute__((used))  = 0;

  
word_t str2num(char *str, int len) {
  word_t val = 0;
  for(int i = 0; i < len; i ++) {
    val = val * 10 + (str[i] - '0');
  }
  return val;
}

word_t str2hexnum(char *str, int len) {
  word_t val = 0;
  for(int i = 0; i < len; i ++) {
    if(str[i] >= 'a' && str[i] <= 'f') {
      val = val * 16 + (str[i] - 'a' + 10);
      continue;
    }
    if(str[i] >= 'A' && str[i] <= 'F') {
      val = val * 16 + (str[i] - 'A' + 10);
      continue;
    }
    val = val * 16 + (str[i] - '0');
  }
  // printf("get hex num:%ld(0x%s)\n", val, str);
  return val;
}

int get_last_op(int token_idx) {
  for(int i = token_idx - 1; i >= 0; i --) {
    if(tokens[i].type == '-' || tokens[i].type == '*' ){
      return i;
    }
  }
  return -1;
}


static bool make_token(char *e) {
  int position = 0;
  int i;
  regmatch_t pmatch;

  nr_token = 0;
  for(int j = 0; j < 32; j ++) {
    tokens[j].type = 0;
    tokens[j].member = 0;
    tokens[j].otherwise = false;
  }

  while (e[position] != '\0') {
    /* Try all rules one by one. */
    for (i = 0; i < NR_REGEX; i ++) {
      if (regexec(&re[i], e + position, 1, &pmatch, 0) == 0 && pmatch.rm_so == 0) {
        char *substr_start = e + position;
        int substr_len = pmatch.rm_eo;

        Log("match rules[%d] = \"%s\" at position %d with len %d: %.*s",
            i, rules[i].regex, position, substr_len, substr_len, substr_start);

        position += substr_len;

        /* TODO: Now a new token is recognized with rules[i]. Add codes
         * to record the token in the array `tokens'. For certain types
         * of tokens, some extra actions should be performed.
         */

        switch (rules[i].token_type) {
          case TK_NOTYPE:
            break;
            case TK_EQ: {
              tokens[nr_token].type = TK_EQ;
              tokens[nr_token].member = TK_EQ;
              nr_token++;
              break;
            }
            case '+': {
              tokens[nr_token].type = '+';
              tokens[nr_token].member = '+';
              nr_token++;
              break;
            }
            case '-': {
              tokens[nr_token].type = '-';
              tokens[nr_token].member = '-';
              if(tokens[nr_token-1].type != NUM && tokens[nr_token-1].type != ')' ) {
                tokens[nr_token].otherwise = true;
              }else {
                tokens[nr_token].otherwise = false;
              }
              nr_token++;
            break;
            }
            case '*': {
              tokens[nr_token].type = '*';
              tokens[nr_token].member = '*';
              if(tokens[nr_token-1].type != NUM && tokens[nr_token-1].type != ')' ) {
                tokens[nr_token].otherwise = true;
              }else {
                tokens[nr_token].otherwise = false;
              }
              nr_token++;
              break;
            }
            case '/': {
              tokens[nr_token].type = '/';
              tokens[nr_token].member = '/';
              nr_token++;
            break;
            }
            case '(': {
              tokens[nr_token].type = '(';
              tokens[nr_token].member = '(';
              nr_token++;
            break;
            }
            case ')': {
              tokens[nr_token].type = ')';
              tokens[nr_token].member = ')';
              nr_token++;
            break;
            }
          case NUM:{
              if(tokens[nr_token-1].otherwise == true) {
                if(tokens[nr_token-1].type == '-') {
                  // this is a negative number
                  tokens[nr_token-1].member = -str2num(substr_start, substr_len);
                  printf("get negative num:%ld\n", tokens[nr_token-1].member);
                  tokens[nr_token-1].type = NUM;
                  tokens[nr_token-1].otherwise = false;
                  break;
                }
              }
              tokens[nr_token].type = NUM;
              tokens[nr_token].member = str2num(substr_start, substr_len);
              printf("get num:%ld\n", tokens[nr_token].member);
              nr_token ++;
              break;
          }
          case HEX_NUM:{
            if(tokens[nr_token-1].type == NUM && tokens[nr_token-1].member == 0) {
              // combine '0' and 'x1234' to '0x1234'
              tokens[nr_token-1].member = str2hexnum(substr_start + 1, substr_len - 1);
              if(tokens[nr_token-2].otherwise == true) {
                nr_token --;
                if(tokens[nr_token-1].type == '-') {
                  // this is a negative hex number
                  tokens[nr_token-1].member = -tokens[nr_token-1].member;
                  printf("get negative hex num:%ld\n", tokens[nr_token-1].member);
                }
                else if(tokens[nr_token-1].type == '*') {
                  // get memory address value
                  if(tokens[nr_token].member < str2hexnum("80000000", 8) || tokens[nr_token].member > str2hexnum("87FFFFFF", 8)) {
                    printf("Invalid memory address: 0x%lx\n", tokens[nr_token].member);
                    return false;
                  }
                  tokens[nr_token-1].member = vaddr_read(tokens[nr_token].member, BYTE);
                  printf("get memory address value:%ld\n", tokens[nr_token-1].member);
                }
                tokens[nr_token-1].type = NUM;
                tokens[nr_token-1].otherwise = false;
              }
              break;
            } else
            {
              printf("get wrong hex num format: %ld%s\n",tokens[nr_token-1].member, substr_start);
              return false;
              break;
            }
            
          }
          case REG_NAME: {
            bool success = true;
            int idx = 0;
            tokens[nr_token].member = isa_reg_str2val(substr_start + 1, &success, &idx);
            if(!success) {
              printf("Invalid register name: %.*s\n", substr_len, substr_start);
              return false;
            }
            tokens[nr_token].type = NUM;
            printf("get reg val:%ld\n", tokens[nr_token].member);
            nr_token ++;
            break;
          }
          case TK_NEQ:{
            tokens[nr_token].type = TK_NEQ;
            tokens[nr_token].member = TK_NEQ;
            nr_token ++;
            break;
          }
          case (AND):{
            tokens[nr_token].type = AND;
            tokens[nr_token].member = TK_NEQ;
            nr_token ++;
            break;
          }
          default:break;
        }
        break;
      }
    }

    if (i == NR_REGEX) {
      printf("no match at position %d\n%s\n%*.s^\n", position, e, position, "");
      return false;
    }
  }

  return true;
}

bool check_parentheses(int p, int q, bool *success, int deepth) {
  if (tokens[p].type != '(' || tokens[q].type != ')') {
    return false;
  }
  int cnt = 0;
  int i;
  int zero_cnt = 0;
  for (i = p ; i <= q; i ++) {
    if (tokens[i].type == '(') {
      cnt ++;
    } else if (tokens[i].type == ')') {
      if (cnt == 0) {
        *success = false;
        printf("Unmatched parentheses, not enough left parentheses\n");
        return false;
      }
      cnt --;
      if (cnt == 0) {
        zero_cnt ++;
      }
    }
  }
  // printf("from p=%d to q=%d\n", p, q);
  if (cnt != 0) {
    *success = false;
    printf("Unmatched parentheses, not enough right parentheses\n");
    return false;
  }
  return zero_cnt == 1;
}

word_t eval(int p, int q, bool *success, int deepth) {
  if (*success == false) {
    return 0;
  }
  // printf("eval from p=%d to q=%d\n", p, q);
  if (p > q) {
    *success = false;
    printf("Bad expression\n");
    // exit(0);
    return 0;
  }
  else if (p == q) {
    return tokens[p].member;
  } 
  else if(check_parentheses(p, q, success, deepth)) {
    if(!(*success)) {
      return 0;
    }
    return eval(p + 1, q - 1, success, deepth + 1);
  } 
  else {
    
    if(!(*success)) {
      return 0;
    }
    int op_idx = -1;
    word_t op = 0;
    int parentheses_flag = 0;
    for(int i = p; i <= q; i ++) {
      if(tokens[i].type == '(') {
        parentheses_flag++;
        continue;
      } else if(tokens[i].type == ')') {
        parentheses_flag--;
        continue;
      }
      if((tokens[i].type == '+' || tokens[i].type == '-' || tokens[i].type == '*' || tokens[i].type == '/' 
          || tokens[i].type == TK_EQ || tokens[i].type == TK_NEQ || tokens[i].type == AND)
        && parentheses_flag == 0) {
        if(op == TK_EQ || op == TK_NEQ || op == AND) {
          continue;
        }
        else if((op == '+' || op == '-') && (tokens[i].type == '*' || tokens[i].type == '/')) {
          continue;
        }
        op_idx = i;
        op = tokens[i].type;
      }
    }
    // printf("op is %c, num is %ld\n", (char)op, op);
    word_t val1, val2, res = 0;
    val1 = eval(p, op_idx - 1, success, deepth + 1);
    val2 = eval(op_idx + 1, q, success, deepth + 1);
    switch (op) {
      case '+': res = val1 + val2; break;
      case '-': res = val1 - val2; break;
      case '*': res = val1 * val2; break;
      case '/': res = val1 / val2; break;
      case TK_EQ: res = (val1 == val2); break;
      case TK_NEQ: res = (val1 != val2); break;
      case AND: res = (val1 && val2); break;
      default: 
        *success = false;
        printf("Invalid operator, for char is %c, num is %ld\n", (char)op, op);
        printf("from p=%d to q=%d\n", p, q);
        printf("deepth is %d\n", deepth);
        return 0;
    }
    // printf("get res: %ld\n", res);
    // printf("deepth is %d\n", deepth);
    return res;
      
  }
}


word_t expr(char *e, bool *success) {
  if (!make_token(e)) {
    *success = false;
    return 0;
  }

  int deepth = 0;
  // printf("expression tokens:\n");
  // for(int i = 0; i < nr_token; i ++) {
  //   if(tokens[i].type == NUM) {
  //     printf("%ld ", tokens[i].member);
  //   } else {
  //     printf("%c ", (char)tokens[i].member);
  //   }
  // }
  // printf("\n");
  word_t result = eval(0, nr_token - 1, success, deepth);
  if(success == false) {
    return 0;
  }
  return result;
}
