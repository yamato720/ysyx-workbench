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

#include <cpu/cpu.h>
#include <cpu/decode.h>
#include <cpu/difftest.h>
#include <locale.h>

#ifdef NPC
// NPC integration: declare NPC functions
extern void npc_init();
extern void npc_single_run();
extern void npc_getvalue();
extern uint64_t npc_get_pc();
extern uint32_t npc_get_inst();
extern int npc_is_finished();
extern uint64_t npc_get_reg(int idx);
#endif
#include "../isa/riscv64/local-include/reg.h"

#include "../../src/monitor/sdb/sdb.h"

/* The assembly code of instructions executed is only output to the screen
 * when the number of instructions executed is less than this value.
 * This is useful when you use the `si' command.
 * You can modify this value as you want.
 */
#define MAX_INST_TO_PRINT 10

CPU_state cpu = {};
uint64_t g_nr_guest_inst = 0;
static uint64_t g_timer = 0; // unit: us
static bool g_print_step = false;

#define MAX_IRINGBUF_LEN 64

static char iringbuf[MAX_IRINGBUF_LEN][129];
static int current_id = 0;



void device_update();

static void trace_and_difftest(Decode *_this, vaddr_t dnpc) {
#ifdef CONFIG_ITRACE_COND
  if (ITRACE_COND) { log_write("%s\n", _this->logbuf); }
  strncpy(iringbuf[current_id], _this->logbuf, sizeof(iringbuf[0]) - 1);
  iringbuf[current_id][sizeof(iringbuf[0]) - 1] = '\0';
  if(++current_id >= MAX_IRINGBUF_LEN) {
    current_id = 0;
  }
#endif
  // printf("iringbuf[%d]: %s\n", current_id, iringbuf[current_id]);
  if (g_print_step) { IFDEF(CONFIG_ITRACE, puts(_this->logbuf)); }
  // printf("pc: 0x%016lx, dnpc: 0x%016lx, inst: 0x%08x\n", _this->pc, dnpc, _this->isa.inst);
  IFDEF(CONFIG_DIFFTEST, difftest_step(_this->pc, dnpc));
}


void show_iringbuf() {
  printf("==== Instruction Ring Buffer ====\n");
  int count = g_nr_guest_inst < MAX_IRINGBUF_LEN ? g_nr_guest_inst : MAX_IRINGBUF_LEN;
  for (int i = 0; i < count; i++) {
    if (iringbuf[i][0] != '\0') {
      int current_index = (current_id == 0) ? (MAX_IRINGBUF_LEN - 1) : (current_id - 1);
      if(i == current_index) {
        printf("---> ");
      } else {
        printf("     ");
      }
      printf("%s\n", iringbuf[i]);
    }
  }
  printf("=================================\n");
}


void get_current_iringbuf(char* buf) {
  int current_index = (current_id == 0) ? (MAX_IRINGBUF_LEN - 1) : (current_id - 1);
  // Find end of existing string, then append "\n<iringbuf>"
  int start = 0;
  while (buf[start] != '\0') {
    start++;
  }
  buf[start++] = '\n';
  for (int i = 0; i < 129; i++) {
    buf[start + i] = iringbuf[current_index][i];
    if (iringbuf[current_index][i] == '\0') {
      break;
    }
  }
}

#ifdef NPC
// NPC mode: execute using hardware simulation
static void exec_once(Decode *s, vaddr_t pc) {
  // npc_getvalue();
  
  // Update NEMU's CPU state from NPC
  s->pc = pc;
  s->snpc = pc;
  // printf("exec_once pc: 0x%016lx\n", pc);
  // npc_getvalue();
  npc_single_run();
  s->dnpc = npc_get_pc();
  s->isa.inst = npc_get_inst();

#ifdef CONFIG_NPC_DIFFTEST_NEMU
  // NPC self-difftest: run NEMU software in parallel and compare
  {
    Decode nemu_s;
    nemu_s.pc = pc;
    nemu_s.snpc = pc;
    isa_exec_once(&nemu_s);
    // cpu.gpr[] has been updated by isa_exec_once

    bool diff = false;
    for (int i = 0; i < 32; i++) {
      uint64_t npc_val = npc_get_reg(i);
      if (cpu.gpr[i] != npc_val) {
        Log("NPC self-difftest: GPR[%d] mismatch at pc=" FMT_WORD ": NEMU=" FMT_WORD " NPC=" FMT_WORD,
            i, pc, cpu.gpr[i], npc_val);
        diff = true;
      }
    }
    if (nemu_s.dnpc != s->dnpc) {
      Log("NPC self-difftest: next PC mismatch at pc=" FMT_WORD ": NEMU=" FMT_WORD " NPC=" FMT_WORD,
          pc, nemu_s.dnpc, s->dnpc);
      diff = true;
    }
    if (diff) {
      Log("NPC self-difftest FAILED at instruction #%" PRIu64 ", pc=" FMT_WORD,
          g_nr_guest_inst + 1, pc);
      isa_reg_display();
      nemu_state.state = NEMU_ABORT;
      nemu_state.halt_pc = pc;
    }
    // Maintain NEMU software state
    cpu.pc = nemu_s.dnpc;
  }
#else
  cpu.pc = s->dnpc;
#endif
  
  // Calculate instruction length for RISC-V
  // If lowest 2 bits are not 11, it's a 2-byte compressed instruction
  int ilen = ((s->isa.inst & 0x3) == 0x3) ? 4 : 2;
  s->snpc = s->pc + ilen;
  
  // Check for ebreak instruction (RISC-V trap for program termination)
  // ebreak encoding: 0x00100073
  if (s->isa.inst == 0x00100073) {
    // a0 register (x10) contains the exit code
    // In NPC mode, read it from the hardware
    extern void set_nemu_state(int state, vaddr_t pc, int halt_ret);
    extern uint64_t npc_get_reg(int idx);
    int halt_ret = (int)npc_get_reg(10);  // Read a0 from NPC
    set_nemu_state(NEMU_END, s->pc, halt_ret);
  }
  
#ifdef CONFIG_ITRACE
  // Format instruction trace similar to normal NEMU mode
  char *p = s->logbuf;
  p += snprintf(p, sizeof(s->logbuf), FMT_WORD ":", s->pc);
  uint8_t *inst = (uint8_t *)&s->isa.inst;
  for (int i = ilen - 1; i >= 0; i--) {
    p += snprintf(p, 4, " %02x", inst[i]);
  }
  int space_len = (4 - ilen) * 3 + 1;
  memset(p, ' ', space_len);
  p += space_len;
  
  void disassemble(char *str, int size, uint64_t pc, uint8_t *code, int nbyte);
  disassemble(p, s->logbuf + sizeof(s->logbuf) - p,
      s->pc, (uint8_t *)&s->isa.inst, ilen);
#endif
}
#else
// Normal NEMU mode: software simulation
static void exec_once(Decode *s, vaddr_t pc) {
  s->pc = pc;
  s->snpc = pc;
  isa_exec_once(s);
  cpu.pc = s->dnpc;
  // printf("dnpc: 0x%016lx\n", s->dnpc);
#ifdef CONFIG_ITRACE
  char *p = s->logbuf;
  p += snprintf(p, sizeof(s->logbuf), FMT_WORD ":", s->pc);
  int ilen = s->snpc - s->pc;
  int i;
  uint8_t *inst = (uint8_t *)&s->isa.inst;
#ifdef CONFIG_ISA_x86
  for (i = 0; i < ilen; i ++) {
#else
  for (i = ilen - 1; i >= 0; i --) {
#endif
    p += snprintf(p, 4, " %02x", inst[i]);
  }
  int ilen_max = MUXDEF(CONFIG_ISA_x86, 8, 4);
  int space_len = ilen_max - ilen;
  if (space_len < 0) space_len = 0;
  space_len = space_len * 3 + 1;
  memset(p, ' ', space_len);
  p += space_len;

  void disassemble(char *str, int size, uint64_t pc, uint8_t *code, int nbyte);
  disassemble(p, s->logbuf + sizeof(s->logbuf) - p,
      MUXDEF(CONFIG_ISA_x86, s->snpc, s->pc), (uint8_t *)&s->isa.inst, ilen);
#endif
}
#endif





static void execute(uint64_t n) {
  Decode s;
  for (;n > 0; n --) {
    stored_gpr();
    exec_once(&s, cpu.pc);
    g_nr_guest_inst ++;
    trace_and_difftest(&s, cpu.pc);
    update_other_regs();
    #if defined(CONFIG_MTRACE) || defined(CONFIG_MY_TRACE)
    record_ins_info();
    #endif
    #if defined(CONFIG_FTRACE) || defined(CONFIG_MY_TRACE)
    check_ftrace(cpu.pc, s.isa.inst);
    #endif
#ifdef CONFIG_WATCHPOINT
    if (check_watchpoints()) {
      nemu_state.state = NEMU_STOP;
      break;
    }
#endif
    if (nemu_state.state != NEMU_RUNNING) break;
    IFDEF(CONFIG_DEVICE, device_update());
  }
}

static void statistic() {
  IFNDEF(CONFIG_TARGET_AM, setlocale(LC_NUMERIC, ""));
#define NUMBERIC_FMT MUXDEF(CONFIG_TARGET_AM, "%", "%'") PRIu64
  Log("host time spent = " NUMBERIC_FMT " us", g_timer);
  Log("total guest instructions = " NUMBERIC_FMT, g_nr_guest_inst);
  if (g_timer > 0) Log("simulation frequency = " NUMBERIC_FMT " inst/s", g_nr_guest_inst * 1000000 / g_timer);
  else Log("Finish running in less than 1 us and can not calculate the simulation frequency");
}

void assert_fail_msg() {
  nemu_state.state = NEMU_ABORT;
  isa_reg_display();
  statistic();
}

void quit() {
  nemu_state.state = NEMU_QUIT;
}

/* Simulate how the CPU works. */
void cpu_exec(uint64_t n) {
  IFDEF(CONFIG_NPC_DIFFTEST_NEMU,
    static bool npc_difftest_logged = false;
    if (!npc_difftest_logged) {
      Log("NPC self-difftest: " ANSI_FMT("ON", ANSI_FG_GREEN) " (using NEMU software as reference)");
      npc_difftest_logged = true;
    }
  );
  g_print_step = (n < MAX_INST_TO_PRINT);
  switch (nemu_state.state) {
    case NEMU_END: case NEMU_ABORT: case NEMU_QUIT:
      printf("Program execution has ended. To restart the program, exit NEMU and run again.\n");
      return;
    default: nemu_state.state = NEMU_RUNNING;
  }

  uint64_t timer_start = get_time();
  
  execute(n);

  uint64_t timer_end = get_time();
  g_timer += timer_end - timer_start;

  switch (nemu_state.state) {
    case NEMU_RUNNING: nemu_state.state = NEMU_STOP; break;

    case NEMU_END: case NEMU_ABORT:
      Log("nemu: %s at pc = " FMT_WORD,
          (nemu_state.state == NEMU_ABORT ? ANSI_FMT("ABORT", ANSI_FG_RED) :
           (nemu_state.halt_ret == 0 ? ANSI_FMT("HIT GOOD TRAP", ANSI_FG_GREEN) :
            ANSI_FMT("HIT BAD TRAP", ANSI_FG_RED))),
          nemu_state.halt_pc);
      // fall through
    case NEMU_QUIT: statistic();
  }
}
