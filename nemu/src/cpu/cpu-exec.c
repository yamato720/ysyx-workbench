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
#include <errno.h>
#include <signal.h>

#ifdef NPC
#include "npc_debug.h"
// NPC integration: declare NPC functions
extern void npc_init();
extern void npc_single_run();
extern int npc_step_cycle();
extern void npc_getvalue();
extern uint64_t npc_get_pc();
extern uint32_t npc_get_inst();
extern uint64_t npc_get_current_pc();
extern uint32_t npc_get_frontend_instruction();
extern int npc_is_finished();
extern uint64_t npc_get_reg(int idx);
extern uint64_t npc_get_cycle_count();
extern uint64_t npc_get_commit_count();
extern uint32_t npc_get_backpressure_reasons();
extern uint32_t npc_get_pipeline_features();
extern uint64_t npc_get_pipeline_stall_count(uint32_t counter);
extern uint64_t npc_get_timing_sample_count(uint32_t timing_class);
extern uint64_t npc_get_timing_total_cycles(uint32_t timing_class, uint32_t stage);
extern uint64_t npc_get_timing_max_total_cycles(uint32_t timing_class);
extern uint64_t npc_get_timing_last_pc(uint32_t timing_class);
extern uint32_t npc_get_timing_last_instruction(uint32_t timing_class);
extern uint64_t npc_get_timing_last_stage_cycles(uint32_t timing_class, uint32_t stage);
extern uint32_t npc_get_last_timing_class();
extern uint64_t npc_get_last_timing_stage_cycles(uint32_t stage);
extern uint64_t npc_get_last_timing_total_cycles();
#endif
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

#ifdef NPC
static const double npc_clock_mhz = 300.0;

enum {
  NPC_TIMING_NORMAL = 0,
  NPC_TIMING_LOAD,
  NPC_TIMING_STORE,
  NPC_TIMING_MULDIV,
  NPC_TIMING_LOAD_LB,
  NPC_TIMING_LOAD_LH,
  NPC_TIMING_LOAD_LW,
  NPC_TIMING_LOAD_LD,
  NPC_TIMING_LOAD_LBU,
  NPC_TIMING_LOAD_LHU,
  NPC_TIMING_LOAD_LWU,
  NPC_TIMING_STORE_SB,
  NPC_TIMING_STORE_SH,
  NPC_TIMING_STORE_SW,
  NPC_TIMING_STORE_SD,
  NPC_TIMING_M_MUL,
  NPC_TIMING_M_MULH,
  NPC_TIMING_M_MULHSU,
  NPC_TIMING_M_MULHU,
  NPC_TIMING_M_DIV,
  NPC_TIMING_M_DIVU,
  NPC_TIMING_M_REM,
  NPC_TIMING_M_REMU,
  NPC_TIMING_M_MULW,
  NPC_TIMING_M_DIVW,
  NPC_TIMING_M_DIVUW,
  NPC_TIMING_M_REMW,
  NPC_TIMING_M_REMUW,
  NPC_TIMING_ALL,
  NPC_TIMING_CLASS_COUNT,
};

enum {
  NPC_PIPELINE_STALL_FETCH_AXI = 0,
  NPC_PIPELINE_STALL_ID,
  NPC_PIPELINE_STALL_EXECUTE,
  NPC_PIPELINE_STALL_MEMORY,
  NPC_PIPELINE_STALL_REDIRECT,
};

enum {
  NPC_TIMING_IF = 0,
  NPC_TIMING_ID,
  NPC_TIMING_EX,
  NPC_TIMING_MEM,
  NPC_TIMING_WB,
  NPC_TIMING_STAGE_COUNT,
};

static const char *npc_timing_class_names[NPC_TIMING_CLASS_COUNT] = {
  "normal",
  "load(all)", "store(all)", "m(all)",
  "load.lb", "load.lh", "load.lw", "load.ld", "load.lbu", "load.lhu", "load.lwu",
  "store.sb", "store.sh", "store.sw", "store.sd",
  "m.mul", "m.mulh", "m.mulhsu", "m.mulhu", "m.div", "m.divu", "m.rem", "m.remu",
  "m.mulw", "m.divw", "m.divuw", "m.remw", "m.remuw",
  "all",
};

typedef struct {
  uint64_t cycles_before;
  uint64_t cycles_after;
  uint64_t commits_before;
  uint64_t commits_after;
  vaddr_t pc;
  uint32_t inst;
  bool valid;
} NPCInstructionTiming;

static NPCInstructionTiming npc_last_instruction = {};

#ifdef NPC_FPGA_REMOTE
extern bool npc_debug_is_interactive(void);
extern int npc_debug_halt(void);
extern int npc_debug_resume(void);
extern int npc_debug_step(void);
extern uint32_t npc_debug_stop_reason(void);
extern uint64_t npc_debug_stop_pc(void);
extern uint64_t npc_get_last_commit_pc(void);

static volatile sig_atomic_t npc_interrupt_requested;

static void npc_interrupt_handler(int signal_number) {
  (void)signal_number;
  npc_interrupt_requested = 1;
}
#endif

#define NPC_BACKPRESSURE_WATCHDOG_US (5ULL * 1000ULL * 1000ULL)

static void npc_report_backpressure_watchdog(vaddr_t guest_pc, uint64_t cycles_before,
                                               uint64_t commits_before, uint64_t blocked_us,
                                               uint32_t reasons) {
  static const struct {
    uint32_t bit;
    const char *description;
  } reason_names[] = {
    { NPC_BACKPRESSURE_IF_AXI, "IF AXI request/response pending" },
    { NPC_BACKPRESSURE_IF_ID, "IF/ID held by ID" },
    { NPC_BACKPRESSURE_ID_EX, "ID/EX held by EX" },
    { NPC_BACKPRESSURE_EX_MEM, "EX/MEM held by MEM" },
    { NPC_BACKPRESSURE_MEM_LSU_WAIT, "MEM waiting for LSU completion" },
    { NPC_BACKPRESSURE_LSU_AXI, "LSU AXI transaction active" },
    { NPC_BACKPRESSURE_SERIAL_EX, "serialized EX operation active" },
    { NPC_BACKPRESSURE_REDIRECT_FLUSH, "redirect/flush active" },
    { NPC_BACKPRESSURE_UNCLASSIFIED_BUSY, "core busy outside known reason bits" },
  };
  const uint32_t known_mask = NPC_BACKPRESSURE_IF_AXI | NPC_BACKPRESSURE_IF_ID |
      NPC_BACKPRESSURE_ID_EX | NPC_BACKPRESSURE_EX_MEM | NPC_BACKPRESSURE_MEM_LSU_WAIT |
      NPC_BACKPRESSURE_LSU_AXI | NPC_BACKPRESSURE_SERIAL_EX |
      NPC_BACKPRESSURE_REDIRECT_FLUSH | NPC_BACKPRESSURE_UNCLASSIFIED_BUSY;

  printf(ANSI_FG_RED "[NPC watchdog] no commit while backpressured for %.3f s" ANSI_NONE "\n",
         (double)blocked_us / 1000000.0);
  printf("  guest pc=" FMT_WORD " hardware pc=" FMT_WORD " frontend inst=0x%08x\n",
         guest_pc, (vaddr_t)npc_get_current_pc(), npc_get_frontend_instruction());
  printf("  commits=%" PRIu64 " hardware cycles=%" PRIu64 " (+%" PRIu64 ") reasons=0x%08x\n",
         commits_before, npc_get_cycle_count(), npc_get_cycle_count() - cycles_before, reasons);
  printf("  blocking reasons:\n");
  for (int i = 0; i < ARRLEN(reason_names); i++) {
    if (reasons & reason_names[i].bit) {
      printf("    - %s\n", reason_names[i].description);
    }
  }
  if (reasons & ~known_mask) {
    printf("    - reserved/future reason bits: 0x%08x\n", reasons & ~known_mask);
  }
  printf("  cumulative stalls: IF AXI=%" PRIu64 ", ID=%" PRIu64 ", EX=%" PRIu64
         ", MEM=%" PRIu64 ", redirects=%" PRIu64 "\n",
         npc_get_pipeline_stall_count(NPC_PIPELINE_STALL_FETCH_AXI),
         npc_get_pipeline_stall_count(NPC_PIPELINE_STALL_ID),
         npc_get_pipeline_stall_count(NPC_PIPELINE_STALL_EXECUTE),
         npc_get_pipeline_stall_count(NPC_PIPELINE_STALL_MEMORY),
         npc_get_pipeline_stall_count(NPC_PIPELINE_STALL_REDIRECT));
}

static void npc_read_counters(uint64_t *cycles, uint64_t *commits) {
  *cycles = npc_get_cycle_count();
  *commits = npc_get_commit_count();
}

static bool npc_pipeline_enabled(void) {
  return (npc_get_pipeline_features() & 0x1u) != 0;
}

static void npc_print_pipeline_configuration(const char *mode) {
  const uint32_t features = npc_get_pipeline_features();
  if (!npc_pipeline_enabled()) return;

  printf("[%s] pipeline: enabled, interlock=enabled, ID forwarding=%s, EX forwarding=%s\n",
         mode,
         (features & 0x2u) ? "enabled" : "disabled",
         (features & 0x4u) ? "enabled" : "disabled");
  printf("[%s] pipeline stalls (cycles/events): IF AXI=%" PRIu64 ", ID RAW/backpressure=%" PRIu64
         ", EX backpressure=%" PRIu64 ", MEM backpressure=%" PRIu64 ", redirects/flushes=%" PRIu64 "\n",
         mode,
         npc_get_pipeline_stall_count(NPC_PIPELINE_STALL_FETCH_AXI),
         npc_get_pipeline_stall_count(NPC_PIPELINE_STALL_ID),
         npc_get_pipeline_stall_count(NPC_PIPELINE_STALL_EXECUTE),
         npc_get_pipeline_stall_count(NPC_PIPELINE_STALL_MEMORY),
         npc_get_pipeline_stall_count(NPC_PIPELINE_STALL_REDIRECT));
}

static bool npc_is_detailed_timing_class(uint32_t timing_class) {
  return timing_class >= NPC_TIMING_LOAD_LB && timing_class <= NPC_TIMING_M_REMUW;
}

static void npc_print_pipeline_timing(const char *mode) {
  uint64_t last_stage[NPC_TIMING_STAGE_COUNT];
  for (int stage = 0; stage < NPC_TIMING_STAGE_COUNT; stage++) {
    last_stage[stage] = npc_get_last_timing_stage_cycles(stage);
  }

  printf("[%s] %s (cycles)\n", mode,
         npc_pipeline_enabled() ? "last committed instruction latency / stage residency" : "last committed instruction pipeline timing");
  printf("+------------+------------+------------+------+------+------+------+------+-------+\n");
  printf("| class      | pc         | instruction| IF   | ID   | EX   | MEM  | WB   | %s |\n",
         npc_pipeline_enabled() ? "latency" : "total  ");
  printf("+------------+------------+------------+------+------+------+------+------+-------+\n");
  printf("| %-10s | 0x%08" PRIx64 " | 0x%08" PRIx32 " | %4" PRIu64 " | %4" PRIu64
         " | %4" PRIu64 " | %4" PRIu64 " | %4" PRIu64 " | %5" PRIu64 " |\n",
         npc_timing_class_names[npc_get_last_timing_class()],
         (uint64_t)npc_last_instruction.pc, npc_last_instruction.inst,
         last_stage[NPC_TIMING_IF], last_stage[NPC_TIMING_ID],
         last_stage[NPC_TIMING_EX], last_stage[NPC_TIMING_MEM],
         last_stage[NPC_TIMING_WB], npc_get_last_timing_total_cycles());
  printf("+------------+------------+------------+------+------+------+------+------+-------+\n");

  printf("[%s] %s\n", mode,
         npc_pipeline_enabled()
           ? "instruction latency / stage residency (global CPI/IPC is not derived from this table)"
           : "pipeline timing profile (average cycles per committed instruction)");
  printf("+------------+-------+---------+---------+---------+---------+---------+-----------+-----------+\n");
  printf("| class      | count | IF avg  | ID avg  | EX avg  | MEM avg | WB avg  | %s | %s |\n",
         npc_pipeline_enabled() ? "latency  " : "total avg",
         npc_pipeline_enabled() ? "max latency" : "max total  ");
  printf("+------------+-------+---------+---------+---------+---------+---------+-----------+-----------+\n");
  for (int timing_class = 0; timing_class < NPC_TIMING_CLASS_COUNT; timing_class++) {
    uint64_t count = npc_get_timing_sample_count(timing_class);
    if (count == 0) continue;
    double average[NPC_TIMING_STAGE_COUNT] = {};
    double total_average = 0.0;
    if (count != 0) {
      for (int stage = 0; stage < NPC_TIMING_STAGE_COUNT; stage++) {
        average[stage] = (double)npc_get_timing_total_cycles(timing_class, stage) / (double)count;
        total_average += average[stage];
      }
    }
    printf("| %-10s | %5" PRIu64 " | %7.2f | %7.2f | %7.2f | %7.2f | %7.2f | %9.2f | %9" PRIu64 " |\n",
           npc_timing_class_names[timing_class], count,
           average[NPC_TIMING_IF], average[NPC_TIMING_ID], average[NPC_TIMING_EX],
           average[NPC_TIMING_MEM], average[NPC_TIMING_WB], total_average,
           npc_get_timing_max_total_cycles(timing_class));
  }
  printf("+------------+-------+---------+---------+---------+---------+---------+-----------+-----------+\n");

  printf("[%s] latest committed sample for each observed load/store/M operation\n", mode);
  printf("+------------+-------+------------+------------+------+------+------+------+------+-------+\n");
  printf("| class      | count | pc         | instruction| IF   | ID   | EX   | MEM  | WB   | %s |\n",
         npc_pipeline_enabled() ? "latency" : "total  ");
  printf("+------------+-------+------------+------------+------+------+------+------+------+-------+\n");
  for (int timing_class = 0; timing_class < NPC_TIMING_CLASS_COUNT; timing_class++) {
    const uint64_t count = npc_get_timing_sample_count(timing_class);
    if (!npc_is_detailed_timing_class(timing_class) || count == 0) continue;

    uint64_t total = 0;
    uint64_t stage[NPC_TIMING_STAGE_COUNT];
    for (int index = 0; index < NPC_TIMING_STAGE_COUNT; index++) {
      stage[index] = npc_get_timing_last_stage_cycles(timing_class, index);
      total += stage[index];
    }
    printf("| %-10s | %5" PRIu64 " | 0x%08" PRIx64 " | 0x%08" PRIx32
           " | %4" PRIu64 " | %4" PRIu64 " | %4" PRIu64 " | %4" PRIu64
           " | %4" PRIu64 " | %5" PRIu64 " |\n",
           npc_timing_class_names[timing_class], count,
           npc_get_timing_last_pc(timing_class),
           npc_get_timing_last_instruction(timing_class),
           stage[NPC_TIMING_IF], stage[NPC_TIMING_ID], stage[NPC_TIMING_EX],
           stage[NPC_TIMING_MEM], stage[NPC_TIMING_WB], total);
  }
  printf("+------------+-------+------------+------------+------+------+------+------+------+-------+\n");
}

void npc_print_performance(void) {
  uint64_t cycles, commits;
  npc_read_counters(&cycles, &commits);

#ifdef NPC_SOC
  const char *mode = "NPC-SoC";
#else
  const char *mode = "NPC";
#endif

  if (commits == 0 || cycles == 0) {
    printf("[%s] performance: cycles=%" PRIu64 ", commits=%" PRIu64
           "; CPI/IPC are unavailable before the first commit.\n",
           mode, cycles, commits);
  } else {
    double cpi = (double)cycles / (double)commits;
    double ipc = (double)commits / (double)cycles;
    double modeled_ms = (double)cycles / (npc_clock_mhz * 1000.0);
    printf("[%s] performance: cycles=%" PRIu64 ", commits=%" PRIu64
           ", CPI=%.4f, IPC=%.4f, @ %.0f MHz: %.2f MIPS, modeled time=%.3f ms\n",
           mode, cycles, commits, cpi, ipc, npc_clock_mhz,
           ipc * npc_clock_mhz, modeled_ms);
  }

  npc_print_pipeline_configuration(mode);

  if (!npc_last_instruction.valid) {
    printf("[%s] last commit: unavailable.\n", mode);
    return;
  }

  printf("[%s] last commit: pc=0x%08" PRIx64 ", inst=0x%08" PRIx32
         ", interval=%" PRIu64 " cycles, commits=%" PRIu64 " -> %" PRIu64 "\n",
         mode, (uint64_t)npc_last_instruction.pc, npc_last_instruction.inst,
         npc_last_instruction.cycles_after - npc_last_instruction.cycles_before,
         npc_last_instruction.commits_before, npc_last_instruction.commits_after);
  npc_print_pipeline_timing(mode);
}

#ifdef NPC_SOC
static const double npc_soc_clock_mhz = npc_clock_mhz;

static bool npc_soc_read_counters(uint64_t *cycles, uint64_t *commits) {
  npc_read_counters(cycles, commits);
  if (*commits == 0) {
    printf("[NPC-SoC] no committed instruction yet; CPI/IPC are unavailable.\n");
    return false;
  }
  return true;
}

void npc_soc_print_cpi(void) {
  uint64_t cycles, commits;
  if (!npc_soc_read_counters(&cycles, &commits)) return;
  printf("[NPC-SoC] cycles=%" PRIu64 ", commits=%" PRIu64 ", CPI=%.4f\n",
         cycles, commits, (double)cycles / (double)commits);
}

void npc_soc_print_ipc(void) {
  uint64_t cycles, commits;
  if (!npc_soc_read_counters(&cycles, &commits)) return;
  double ipc = (double)commits / (double)cycles;
  printf("[NPC-SoC] cycles=%" PRIu64 ", commits=%" PRIu64 ", IPC=%.4f, @ %.0f MHz: %.2f MIPS\n",
         cycles, commits, ipc, npc_soc_clock_mhz, ipc * npc_soc_clock_mhz);
}

void npc_soc_print_performance(void) {
  // Keep the SoC and standalone NEMU views identical.  npc_print_performance
  // selects the NPC-SoC label through NPC_SOC and includes pipeline counters.
  npc_print_performance();
}
#endif
#endif

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
  uint64_t cycles_before = npc_get_cycle_count();
  uint64_t commits_before = npc_get_commit_count();
  uint64_t blocking_started_at = 0;
  bool blocking = false;

#ifdef NPC_FPGA_REMOTE
  if (npc_debug_is_interactive() && npc_debug_step() != 0) {
    fprintf(stderr, "FPGA single-step failed: %s\n", strerror(errno));
    set_nemu_state(NEMU_ABORT, pc, -1);
    return;
  }
#endif

  // npc_getvalue();
  
  // Update NEMU's CPU state from NPC
  s->pc = pc;
  s->snpc = pc;
  // Advance a cycle at a time so a ready/valid deadlock returns control to
  // NEMU. Both interactive commands and batch mode reach this same path.
  while (npc_get_commit_count() == commits_before) {
    npc_step_cycle();
    if (npc_get_commit_count() != commits_before) {
      break;
    }

    const uint32_t reasons = npc_get_backpressure_reasons();
    if (reasons != 0) {
      const uint64_t now = get_time();
      if (!blocking) {
        blocking = true;
        blocking_started_at = now;
      }
      if (now - blocking_started_at >= NPC_BACKPRESSURE_WATCHDOG_US) {
        npc_report_backpressure_watchdog(pc, cycles_before, commits_before,
                                         now - blocking_started_at, reasons);
        set_nemu_state(NEMU_ABORT, pc, -1);
        return;
      }
    } else {
      blocking = false;
    }

    // A simulator finish without a retirement is not a backpressure timeout,
    // but it cannot produce a valid architectural instruction either.
    if (npc_is_finished()) {
      printf(ANSI_FG_RED "[NPC] simulator finished before instruction commit" ANSI_NONE
             " at pc=" FMT_WORD "\n", pc);
      set_nemu_state(NEMU_ABORT, pc, -1);
      return;
    }
  }
  s->dnpc = npc_get_pc();
  s->isa.inst = npc_get_inst();

  uint64_t cycles_after = npc_get_cycle_count();
  uint64_t commits_after = npc_get_commit_count();
  if (commits_after > commits_before) {
    npc_last_instruction = (NPCInstructionTiming) {
      .cycles_before = cycles_before,
      .cycles_after = cycles_after,
      .commits_before = commits_before,
      .commits_after = commits_after,
      .pc = pc,
      .inst = s->isa.inst,
      .valid = true,
    };
  }

#ifdef CONFIG_NPC_DIFFTEST_NEMU
  // NPC self-difftest: run NEMU software in parallel and compare
  {
    Decode nemu_s;
    nemu_s.pc = pc;
    nemu_s.snpc = pc;
    isa_exec_once(&nemu_s);
    // cpu.gpr[] has been updated by isa_exec_once

    // Collect ALL mismatches before printing anything
    struct { const char *name; uint64_t nemu_val; uint64_t npc_val; } mm[33];
    int nm = 0;
    for (int i = 0; i < 32; i++) {
      uint64_t npc_val = npc_get_reg(i);
      if (cpu.gpr[i] != npc_val) {
        mm[nm].name     = isa_reg_idx2str(i);
        mm[nm].nemu_val = cpu.gpr[i];
        mm[nm].npc_val  = npc_val;
        nm++;
      }
    }
    // ebreak terminates the test program. NEMU keeps dnpc at pc + 4 before
    // ending execution, while NPC treats it as a synchronous trap and exposes
    // mtvec as its committed next PC. Registers must still match.
    bool is_terminal_ebreak = s->isa.inst == 0x00100073;
    if (!is_terminal_ebreak && nemu_s.dnpc != s->dnpc) {
      mm[nm].name     = "next_pc";
      mm[nm].nemu_val = nemu_s.dnpc;
      mm[nm].npc_val  = s->dnpc;
      nm++;
    }

    if (nm > 0) {
      printf(ANSI_FG_RED
        "╔══════════════════════════════════════════════════════════╗\n"
        "║            NPC self-difftest FAILED                      ║\n"
        "╚══════════════════════════════════════════════════════════╝"
        ANSI_NONE "\n");
      printf("  inst #" ANSI_FG_YELLOW "%" PRIu64 ANSI_NONE
             "   pc=" ANSI_FG_YELLOW FMT_WORD ANSI_NONE "\n",
             g_nr_guest_inst + 1, pc);
      printf(ANSI_FG_CYAN "  %-10s  %-20s  %-20s\n" ANSI_NONE,
             "register", "NEMU (expected)", "NPC (got)");
      for (int i = 0; i < nm; i++) {
        printf("  " ANSI_FG_YELLOW "%-10s" ANSI_NONE
               "  " ANSI_FG_GREEN  "0x%016" PRIx64 ANSI_NONE
               "  " ANSI_FG_RED    "0x%016" PRIx64 ANSI_NONE "\n",
               mm[i].name, mm[i].nemu_val, mm[i].npc_val);
      }
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
    // A cycle-level NPC watchdog can abort before exec_once has a committed
    // instruction to populate in Decode. Do not trace or difftest that
    // incomplete record.
    if (nemu_state.state == NEMU_ABORT) break;
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

#if defined(NPC) && defined(NPC_FPGA_REMOTE)
static void execute_fpga_free_run(void) {
  const uint64_t commits_before = npc_get_commit_count();
  struct sigaction action = {0};
  struct sigaction previous = {0};
  action.sa_handler = npc_interrupt_handler;
  sigemptyset(&action.sa_mask);
  npc_interrupt_requested = 0;
  sigaction(SIGINT, &action, &previous);

  if (npc_debug_resume() != 0) {
    fprintf(stderr, "FPGA resume failed: %s\n", strerror(errno));
    set_nemu_state(NEMU_ABORT, cpu.pc, -1);
  } else {
    while (!npc_is_finished() && !npc_interrupt_requested) npc_step_cycle();
    if (npc_interrupt_requested) {
      if (npc_debug_halt() != 0) {
        fprintf(stderr, "FPGA halt after Ctrl-C failed: %s\n", strerror(errno));
        set_nemu_state(NEMU_ABORT, cpu.pc, -1);
      } else {
        cpu.pc = npc_debug_stop_pc();
        nemu_state.state = NEMU_STOP;
      }
    } else if (npc_debug_stop_reason() == 3) {
      cpu.pc = npc_debug_stop_pc();
      set_nemu_state(NEMU_END, npc_get_last_commit_pc(), (int)npc_get_reg(10));
    } else if (npc_is_finished()) {
      set_nemu_state(NEMU_ABORT, cpu.pc, -1);
    }
  }

  sigaction(SIGINT, &previous, NULL);
  const uint64_t commits_after = npc_get_commit_count();
  if (commits_after >= commits_before) g_nr_guest_inst += commits_after - commits_before;
}
#endif

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
  
#if defined(NPC) && defined(NPC_FPGA_REMOTE)
  if (n == UINT64_MAX && npc_debug_is_interactive() && !has_watchpoints())
    execute_fpga_free_run();
  else
    execute(n);
#else
  execute(n);
#endif

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
#ifdef NPC
      npc_print_performance();
#endif
      // fall through
    case NEMU_QUIT: statistic();
  }
}
