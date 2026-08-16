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
#include <memory/paddr.h>
#include <locale.h>
#include <errno.h>
#include <signal.h>
#include <stdlib.h>
#include <unistd.h>
#ifdef CONFIG_NPC_PERFORMANCE_HTML
#include <pipeline-html.h>
#endif

#ifdef NPC
#ifdef CONFIG_NPC_PERFORMANCE_HTML
#include <performance-html.h>
#endif
#include "npc_debug.h"
#ifdef NPC_FPGA_REMOTE
#include <npc-fpga-trace.h>
#endif
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
extern uint64_t npc_get_last_store_sequence(void);
extern uint64_t npc_get_last_store_address(void);
extern uint64_t npc_get_last_store_data(void);
extern uint32_t npc_get_last_store_strobe(void);
extern uint32_t npc_get_last_store_word_bytes(void);
extern uint64_t npc_get_cycle_count();
extern uint64_t npc_get_commit_count();
extern uint64_t npc_get_cache_counter(uint32_t cache, uint32_t counter)
  __attribute__((weak));
extern uint32_t npc_get_cache_configuration(uint32_t cache, uint32_t field)
  __attribute__((weak));
extern uint32_t npc_get_backpressure_reasons();
extern uint32_t npc_get_pipeline_features();
extern uint64_t npc_get_pipeline_stall_count(uint32_t counter);
extern uint64_t npc_get_timing_sample_count(uint32_t timing_class);
extern uint64_t npc_get_timing_total_cycles(uint32_t timing_class, uint32_t stage);
extern uint64_t npc_get_timing_max_total_cycles(uint32_t timing_class);
extern uint64_t npc_get_timing_total_latency(uint32_t timing_class);
extern uint64_t npc_get_timing_memory_queue_cycles(uint32_t timing_class);
extern uint64_t npc_get_timing_last_memory_queue_cycles(uint32_t timing_class);
extern uint64_t npc_get_timing_last_total_latency(uint32_t timing_class);
extern uint64_t npc_get_timing_last_pc(uint32_t timing_class);
extern uint32_t npc_get_timing_last_instruction(uint32_t timing_class);
extern uint64_t npc_get_timing_last_stage_cycles(uint32_t timing_class, uint32_t stage);
extern uint32_t npc_get_last_timing_class();
extern uint64_t npc_get_last_timing_stage_cycles(uint32_t stage);
extern uint64_t npc_get_last_timing_stage_start(uint32_t stage);
extern uint64_t npc_get_last_timing_total_cycles();
extern uint64_t npc_get_last_timing_memory_queue_start();
extern uint64_t npc_get_last_timing_memory_service_start();
extern uint64_t npc_get_last_timing_memory_queue_cycles();
extern uint64_t npc_get_last_timing_memory_service_cycles();
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
#ifndef NPC_CLOCK_MHZ
#define NPC_CLOCK_MHZ 300
#endif
static const double npc_clock_mhz = (double)NPC_CLOCK_MHZ;

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
  NPC_PIPELINE_STALL_FETCH_STARVATION = 0,
  NPC_PIPELINE_STALL_ID,
  NPC_PIPELINE_STALL_EXECUTE,
  NPC_PIPELINE_STALL_MEMORY,
  NPC_PIPELINE_STALL_REDIRECT,
  NPC_PIPELINE_STALL_COUNT,
};

enum {
  NPC_TIMING_IF = 0,
  NPC_TIMING_ID,
  NPC_TIMING_EX,
  NPC_TIMING_MEM,
  NPC_TIMING_WB,
  NPC_TIMING_STAGE_COUNT,
};

enum {
  NPC_CACHE_CONFIGURATION_ENABLED = 0,
  NPC_CACHE_CONFIGURATION_CAPACITY_BYTES,
  NPC_CACHE_CONFIGURATION_LINE_BYTES,
  NPC_CACHE_CONFIGURATION_WAYS,
  NPC_CACHE_CONFIGURATION_SETS,
  NPC_CACHE_CONFIGURATION_MAPPING,
  NPC_CACHE_CONFIGURATION_REPLACEMENT,
  NPC_CACHE_CONFIGURATION_READ_MISS,
  NPC_CACHE_CONFIGURATION_WRITE_POLICY,
  NPC_CACHE_CONFIGURATION_WRITE_MISS,
  NPC_CACHE_CONFIGURATION_STORAGE,
  NPC_CACHE_CONFIGURATION_INSTRUCTION_BUFFER_ENABLED,
  NPC_CACHE_CONFIGURATION_INSTRUCTION_BUFFER_ENTRIES,
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

static bool npc_monitoring_available(void) {
#ifdef NPC_FPGA_REMOTE
  return npc_hardware_monitoring_available();
#else
  return true;
#endif
}

#ifdef CONFIG_NPC_PERFORMANCE_HTML
static uint64_t npc_recorded_trace_sequence;

static void npc_record_pipeline_html(
    Decode *instruction, uint64_t sequence, uint64_t commit_cycle) {
  uint64_t stage[PIPELINE_HTML_STAGE_COUNT];
#ifndef NPC_FPGA_REMOTE
  uint64_t stage_start[PIPELINE_HTML_STAGE_COUNT];
  PipelineHtmlMemoryTiming memory = {};
#endif
  for (uint32_t index = 0; index < PIPELINE_HTML_STAGE_COUNT; index++) {
    stage[index] = npc_get_last_timing_stage_cycles(index);
#ifndef NPC_FPGA_REMOTE
    stage_start[index] = npc_get_last_timing_stage_start(index);
#endif
  }

  const int instruction_length = (instruction->isa.inst & 0x3) == 0x3 ? 4 : 2;
  char disassembly[160] = {};
  void disassemble(char *str, int size, uint64_t pc, uint8_t *code, int nbyte);
  disassemble(disassembly, sizeof(disassembly), instruction->pc,
              (uint8_t *)&instruction->isa.inst, instruction_length);
#ifdef NPC_FPGA_REMOTE
  npc_pipeline_html_record(sequence, instruction->pc, instruction->isa.inst,
                           disassembly, commit_cycle, stage);
#else
  memory.valid = (instruction->isa.inst & 0x7f) == 0x03 ||
                 (instruction->isa.inst & 0x7f) == 0x23;
  memory.queue_start_cycle = npc_get_last_timing_memory_queue_start();
  memory.service_start_cycle = npc_get_last_timing_memory_service_start();
  memory.queue_cycles = npc_get_last_timing_memory_queue_cycles();
  memory.service_cycles = npc_get_last_timing_memory_service_cycles();
  // `commitValid` 是提交握手经寄存器导出的观测信号；调用方读到它时，硬件周期
  // 已经推进了一拍。时间线必须回到实际提交边沿，否则 WB 后会凭空多出一拍。
  const uint64_t commit_edge = commit_cycle == 0 ? 0 : commit_cycle - 1;
  npc_pipeline_html_record_absolute_with_memory(
      sequence, instruction->pc, instruction->isa.inst, disassembly,
      commit_edge, stage, stage_start, &memory);
#endif
  if (sequence > npc_recorded_trace_sequence)
    npc_recorded_trace_sequence = sequence;
}

#ifdef NPC_FPGA_REMOTE
static int npc_import_fpga_trace_record(const struct npc_fpga_trace_record *record,
                                        uint64_t sequence, void *opaque) {
  (void)opaque;
  if (record == NULL || sequence <= npc_recorded_trace_sequence) return 0;
  if (npc_recorded_trace_sequence != 0 &&
      sequence != npc_recorded_trace_sequence + 1) {
    errno = EPROTO;
    return -1;
  }

  const uint32_t instruction = record->instruction;
  const int instruction_length = (instruction & 0x3) == 0x3 ? 4 : 2;
  char disassembly[160] = {};
  void disassemble(char *str, int size, uint64_t pc, uint8_t *code, int nbyte);
  disassemble(disassembly, sizeof(disassembly), record->pc,
              (uint8_t *)&instruction, instruction_length);
  uint64_t stage[PIPELINE_HTML_STAGE_COUNT];
  for (uint32_t index = 0; index < PIPELINE_HTML_STAGE_COUNT; index++)
    stage[index] = record->stage[index];
  npc_pipeline_html_record_hardware(sequence, record->pc, instruction,
                                    disassembly, record->commit_cycle, stage,
                                    record->saturation);
  npc_recorded_trace_sequence = sequence;
  npc_last_instruction = (NPCInstructionTiming) {
    .cycles_before = 0,
    .cycles_after = record->commit_cycle,
    .commits_before = sequence - 1,
    .commits_after = sequence,
    .pc = record->pc,
    .inst = instruction,
    .valid = true,
  };
  return 0;
}

static void npc_refresh_last_hardware_timing(void) {
  if (!npc_monitoring_available()) return;
  const uint64_t commits = npc_get_timing_sample_count(NPC_TIMING_ALL);
  if (commits == 0) return;
  npc_last_instruction = (NPCInstructionTiming) {
    .cycles_before = 0,
    .cycles_after = npc_get_last_timing_total_cycles(),
    .commits_before = commits - 1,
    .commits_after = commits,
    .pc = npc_get_timing_last_pc(NPC_TIMING_ALL),
    .inst = npc_get_timing_last_instruction(NPC_TIMING_ALL),
    .valid = true,
  };
}

static void npc_import_fpga_trace(void) {
  if (!npc_monitoring_available()) return;
  if (npc_runtime_visit_trace(npc_import_fpga_trace_record, NULL) != 0) {
    if (errno != EBUSY)
      fprintf(stderr, "cannot import U55C runtime trace: %s\n", strerror(errno));
    return;
  }
  npc_pipeline_html_set_dropped(npc_runtime_trace_dropped());
  npc_refresh_last_hardware_timing();
}
#endif

static void npc_finish_pipeline_html(void) {
#ifdef NPC_FPGA_REMOTE
  if (!npc_monitoring_available()) return;
  npc_import_fpga_trace();
#endif
  uint64_t stalls[PIPELINE_HTML_STAGE_COUNT];
  for (uint32_t index = 0; index < PIPELINE_HTML_STAGE_COUNT; index++) {
    stalls[index] = npc_get_pipeline_stall_count(index);
  }
  npc_pipeline_html_finalize(stalls,
#ifdef CONFIG_NPC_PIPELINE_HTML
      true
#else
      false
#endif
  );
}
#endif

#ifdef NPC_FPGA_REMOTE
extern bool npc_debug_is_interactive(void);
extern int npc_debug_halt(void);
extern int npc_debug_resume(void);
extern int npc_debug_step(void);
extern uint32_t npc_debug_stop_reason(void);
extern uint64_t npc_debug_stop_pc(void);
extern uint64_t npc_get_last_commit_pc(void);
extern bool npc_runtime_has_failed(void);
extern bool npc_runtime_has_completed(void);
extern uint64_t npc_runtime_completion_code(void);
extern uint64_t npc_runtime_completion_pc(void);
extern uint64_t npc_runtime_completion_next_pc(void);

static volatile sig_atomic_t npc_interrupt_requested;

static void npc_interrupt_handler(int signal_number) {
  (void)signal_number;
  npc_interrupt_requested = 1;
}
#endif

#define NPC_PROGRESS_WATCHDOG_US (5ULL * 1000ULL * 1000ULL)

static void npc_report_progress_watchdog(vaddr_t guest_pc, uint64_t cycles_before,
                                         uint64_t commits_before, uint64_t blocked_us,
                                         uint32_t reasons) {
  static const struct {
    uint32_t bit;
    const char *description;
  } reason_names[] = {
    { NPC_BACKPRESSURE_IF_AXI, "IF has no dispatchable instruction" },
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

  printf(ANSI_FG_RED "[NPC watchdog] no architectural progress for %.3f s" ANSI_NONE "\n",
         (double)blocked_us / 1000000.0);
  printf("  guest pc=" FMT_WORD " hardware pc=" FMT_WORD " frontend inst=0x%08x\n",
         guest_pc, (vaddr_t)npc_get_current_pc(), npc_get_frontend_instruction());
  printf("  commits=%" PRIu64 " hardware cycles=%" PRIu64 " (+%" PRIu64 ") reasons=0x%08x\n",
         commits_before, npc_get_cycle_count(), npc_get_cycle_count() - cycles_before, reasons);
  printf("  sampled blocking reasons:\n");
  for (int i = 0; i < ARRLEN(reason_names); i++) {
    if (reasons & reason_names[i].bit) {
      printf("    - %s\n", reason_names[i].description);
    }
  }
  if (reasons == 0) {
    printf("    - none reported by the mailbox\n");
  }
  if (reasons & ~known_mask) {
    printf("    - reserved/future reason bits: 0x%08x\n", reasons & ~known_mask);
  }
  printf("  cumulative stalls: IF starvation=%" PRIu64 ", ID=%" PRIu64 ", EX=%" PRIu64
         ", MEM=%" PRIu64 ", redirects=%" PRIu64 "\n",
         npc_get_pipeline_stall_count(NPC_PIPELINE_STALL_FETCH_STARVATION),
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
  printf("[%s] pipeline stalls (cycles/events): IF starvation=%" PRIu64 ", ID RAW/backpressure=%" PRIu64
         ", EX backpressure=%" PRIu64 ", MEM backpressure=%" PRIu64 ", redirects/flushes=%" PRIu64 "\n",
         mode,
         npc_get_pipeline_stall_count(NPC_PIPELINE_STALL_FETCH_STARVATION),
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
  printf("| class      | count | IF avg  | IF/ID   | EX avg  | MEM avg | WB avg  | %s | %s |\n",
         npc_pipeline_enabled() ? "latency  " : "total avg",
         npc_pipeline_enabled() ? "max latency" : "max total  ");
  printf("+------------+-------+---------+---------+---------+---------+---------+-----------+-----------+\n");
  for (int timing_class = 0; timing_class < NPC_TIMING_CLASS_COUNT; timing_class++) {
    uint64_t count = npc_get_timing_sample_count(timing_class);
    if (count == 0) continue;
    double average[NPC_TIMING_STAGE_COUNT] = {};
    if (count != 0) {
      for (int stage = 0; stage < NPC_TIMING_STAGE_COUNT; stage++) {
        average[stage] = (double)npc_get_timing_total_cycles(timing_class, stage) / (double)count;
      }
    }
    printf("| %-10s | %5" PRIu64 " | %7.2f | %7.2f | %7.2f | %7.2f | %7.2f | %9.2f | %9" PRIu64 " |\n",
           npc_timing_class_names[timing_class], count,
           average[NPC_TIMING_IF], average[NPC_TIMING_ID], average[NPC_TIMING_EX],
           average[NPC_TIMING_MEM], average[NPC_TIMING_WB],
           (double)npc_get_timing_total_latency(timing_class) / (double)count,
           npc_get_timing_max_total_cycles(timing_class));
  }
  printf("+------------+-------+---------+---------+---------+---------+---------+-----------+-----------+\n");

#ifdef NPC_FPGA_REMOTE
  if (npc_runtime_trace_dropped() != 0)
    printf("[%s] latest captured prefix sample for each observed load/store/M operation\n", mode);
  else
#endif
    printf("[%s] latest committed sample for each observed load/store/M operation\n", mode);
  printf("+------------+-------+------------+------------+------+------+------+------+------+-------+\n");
  printf("| class      | count | pc         | instruction| IF   | ID   | EX   | MEM  | WB   | %s |\n",
         npc_pipeline_enabled() ? "latency" : "total  ");
  printf("+------------+-------+------------+------------+------+------+------+------+------+-------+\n");
  for (int timing_class = 0; timing_class < NPC_TIMING_CLASS_COUNT; timing_class++) {
    const uint64_t count = npc_get_timing_sample_count(timing_class);
    if (!npc_is_detailed_timing_class(timing_class) || count == 0) continue;

    uint64_t stage[NPC_TIMING_STAGE_COUNT];
    for (int index = 0; index < NPC_TIMING_STAGE_COUNT; index++) {
      stage[index] = npc_get_timing_last_stage_cycles(timing_class, index);
    }
    printf("| %-10s | %5" PRIu64 " | 0x%08" PRIx64 " | 0x%08" PRIx32
           " | %4" PRIu64 " | %4" PRIu64 " | %4" PRIu64 " | %4" PRIu64
           " | %4" PRIu64 " | %5" PRIu64 " |\n",
           npc_timing_class_names[timing_class], count,
           npc_get_timing_last_pc(timing_class),
           npc_get_timing_last_instruction(timing_class),
           stage[NPC_TIMING_IF], stage[NPC_TIMING_ID], stage[NPC_TIMING_EX],
           stage[NPC_TIMING_MEM], stage[NPC_TIMING_WB],
           npc_get_timing_last_total_latency(timing_class));
  }
  printf("+------------+-------+------------+------------+------+------+------+------+------+-------+\n");
}

#ifdef CONFIG_NPC_PERFORMANCE_HTML
static bool npc_performance_html_finished;

#ifdef CONFIG_NPC_CACHE_HTML
#ifdef NPC_FPGA_REMOTE
static const char *npc_cache_mapping_name(uint32_t value) {
  static const char *names[] = {"direct", "set-associative", "fully-associative"};
  return value < sizeof(names) / sizeof(names[0]) ? names[value] : "unknown";
}

static const char *npc_cache_replacement_name(uint32_t value) {
  static const char *names[] = {"LRU", "Tree-PLRU", "FIFO", "Random (deterministic)"};
  return value < sizeof(names) / sizeof(names[0]) ? names[value] : "unknown";
}

static const char *npc_cache_binary_policy_name(uint32_t value, const char *zero, const char *one) {
  return value == 0 ? zero : value == 1 ? one : "unknown";
}

static const char *npc_cache_storage_name(uint32_t value) {
  static const char *names[] = {"auto", "registers", "URAM"};
  return value < sizeof(names) / sizeof(names[0]) ? names[value] : "unknown";
}
#endif

static uint32_t npc_cache_environment_u32(const char *name) {
  const char *text = getenv(name);
  if (text == NULL || *text == '\0') return 0;
  char *end = NULL;
  errno = 0;
  const unsigned long value = strtoul(text, &end, 0);
  return errno == 0 && end != text && *end == '\0' && value <= UINT32_MAX
      ? (uint32_t)value : 0;
}

static bool npc_fill_cache_configuration_from_profile(PerformanceHtmlReport *report) {
  static const char *prefixes[] = {
    "NEMU_CACHE_ICACHE", "NEMU_CACHE_DCACHE", "NEMU_CACHE_L2CACHE",
  };
  const char *enabled = getenv("NEMU_CACHE_ICACHE_ENABLED");
  if (enabled == NULL) return false;
  for (uint32_t cache = 0; cache < PERFORMANCE_HTML_CACHE_COUNT; cache++) {
    PerformanceHtmlCacheConfiguration *configuration = &report->cache_configuration[cache];
    char name[96];
#define CACHE_PROFILE_VALUE(suffix) \
    (snprintf(name, sizeof(name), "%s_%s", prefixes[cache], suffix), getenv(name))
    configuration->enabled = npc_cache_environment_u32(
        (snprintf(name, sizeof(name), "%s_ENABLED", prefixes[cache]), name)) != 0;
    configuration->capacity_bytes = npc_cache_environment_u32(
        (snprintf(name, sizeof(name), "%s_CAPACITY_BYTES", prefixes[cache]), name));
    configuration->line_bytes = npc_cache_environment_u32(
        (snprintf(name, sizeof(name), "%s_LINE_BYTES", prefixes[cache]), name));
    configuration->ways = npc_cache_environment_u32(
        (snprintf(name, sizeof(name), "%s_WAYS", prefixes[cache]), name));
    configuration->sets = npc_cache_environment_u32(
        (snprintf(name, sizeof(name), "%s_SETS", prefixes[cache]), name));
    configuration->mapping = CACHE_PROFILE_VALUE("MAPPING");
    configuration->replacement = CACHE_PROFILE_VALUE("REPLACEMENT");
    configuration->read_miss = CACHE_PROFILE_VALUE("READ_MISS");
    configuration->write_policy = CACHE_PROFILE_VALUE("WRITE_POLICY");
    configuration->write_miss = CACHE_PROFILE_VALUE("WRITE_MISS");
    configuration->storage = CACHE_PROFILE_VALUE("STORAGE");
#undef CACHE_PROFILE_VALUE
  }
  report->instruction_buffer_enabled =
      npc_cache_environment_u32("NEMU_CACHE_INSTRUCTION_BUFFER_ENABLED") != 0;
  report->instruction_buffer_entries =
      npc_cache_environment_u32("NEMU_CACHE_INSTRUCTION_BUFFER_ENTRIES");
  return true;
}

#ifdef NPC_FPGA_REMOTE
static bool npc_fill_cache_configuration_from_hardware(PerformanceHtmlReport *report) {
  if (npc_get_cache_configuration == NULL) return false;
  bool available = false;
  for (uint32_t cache = 0; cache < PERFORMANCE_HTML_CACHE_COUNT; cache++) {
    PerformanceHtmlCacheConfiguration *configuration = &report->cache_configuration[cache];
    configuration->enabled = npc_get_cache_configuration(cache, NPC_CACHE_CONFIGURATION_ENABLED) != 0;
    configuration->capacity_bytes = npc_get_cache_configuration(cache, NPC_CACHE_CONFIGURATION_CAPACITY_BYTES);
    configuration->line_bytes = npc_get_cache_configuration(cache, NPC_CACHE_CONFIGURATION_LINE_BYTES);
    configuration->ways = npc_get_cache_configuration(cache, NPC_CACHE_CONFIGURATION_WAYS);
    configuration->sets = npc_get_cache_configuration(cache, NPC_CACHE_CONFIGURATION_SETS);
    configuration->mapping = npc_cache_mapping_name(
        npc_get_cache_configuration(cache, NPC_CACHE_CONFIGURATION_MAPPING));
    configuration->replacement = npc_cache_replacement_name(
        npc_get_cache_configuration(cache, NPC_CACHE_CONFIGURATION_REPLACEMENT));
    configuration->read_miss = npc_cache_binary_policy_name(
        npc_get_cache_configuration(cache, NPC_CACHE_CONFIGURATION_READ_MISS), "read-allocate", "read-bypass");
    configuration->write_policy = npc_cache_binary_policy_name(
        npc_get_cache_configuration(cache, NPC_CACHE_CONFIGURATION_WRITE_POLICY), "write-back", "write-through");
    configuration->write_miss = npc_cache_binary_policy_name(
        npc_get_cache_configuration(cache, NPC_CACHE_CONFIGURATION_WRITE_MISS), "write-allocate", "no-write-allocate");
    configuration->storage = npc_cache_storage_name(
        npc_get_cache_configuration(cache, NPC_CACHE_CONFIGURATION_STORAGE));
    available |= configuration->enabled;
  }
  report->instruction_buffer_enabled = npc_get_cache_configuration(
      0, NPC_CACHE_CONFIGURATION_INSTRUCTION_BUFFER_ENABLED) != 0;
  report->instruction_buffer_entries = npc_get_cache_configuration(
      0, NPC_CACHE_CONFIGURATION_INSTRUCTION_BUFFER_ENTRIES);
  return available;
}
#endif

static void npc_fill_cache_configuration(PerformanceHtmlReport *report) {
#ifdef NPC_FPGA_REMOTE
  if (npc_fill_cache_configuration_from_hardware(report)) {
    report->cache_configuration_available = true;
    return;
  }
#endif
  report->cache_configuration_available = npc_fill_cache_configuration_from_profile(report);
}
#endif

static const char *npc_memory_statistics_mode(void) {
  const char *mode = getenv("NEMU_MEMORY_STATISTICS_MODE");
  return mode != NULL && strcmp(mode, "ServiceOnly") == 0 ? "ServiceOnly" : "Split";
}

static void npc_finish_performance_html(void) {
  if (npc_performance_html_finished) return;
  npc_performance_html_finished = true;

#ifdef NPC_FPGA_REMOTE
  npc_import_fpga_trace();
#endif

  PerformanceHtmlTimingRow rows[NPC_TIMING_CLASS_COUNT] = {};
  for (uint32_t timing_class = 0; timing_class < NPC_TIMING_CLASS_COUNT; timing_class++) {
    rows[timing_class].name = npc_timing_class_names[timing_class];
    rows[timing_class].count = npc_get_timing_sample_count(timing_class);
    rows[timing_class].max_total = npc_get_timing_max_total_cycles(timing_class);
    rows[timing_class].total_latency = npc_get_timing_total_latency(timing_class);
    rows[timing_class].detailed = npc_is_detailed_timing_class(timing_class);
    for (uint32_t stage = 0; stage < NPC_TIMING_STAGE_COUNT; stage++) {
      rows[timing_class].stage_total[stage] = npc_get_timing_total_cycles(timing_class, stage);
      rows[timing_class].last_stage[stage] = npc_get_timing_last_stage_cycles(timing_class, stage);
    }
 #ifndef NPC_FPGA_REMOTE
    rows[timing_class].memory_queue_total =
        npc_get_timing_memory_queue_cycles(timing_class);
    rows[timing_class].last_memory_queue =
        npc_get_timing_last_memory_queue_cycles(timing_class);
    rows[timing_class].last_total_latency =
        npc_get_timing_last_total_latency(timing_class);
 #endif
    rows[timing_class].last_pc = npc_get_timing_last_pc(timing_class);
    rows[timing_class].last_instruction = npc_get_timing_last_instruction(timing_class);
  }

  uint64_t cycles = 0;
  uint64_t commits = 0;
  npc_read_counters(&cycles, &commits);
  const uint32_t last_class = npc_get_last_timing_class();
  const char *last_class_name = last_class < NPC_TIMING_CLASS_COUNT
      ? npc_timing_class_names[last_class] : "unknown";
  const char *outcome_text = "用户退出";
  PerformanceHtmlOutcome outcome = PERFORMANCE_HTML_OUTCOME_QUIT;
  if (nemu_state.state == NEMU_END) {
    if (nemu_state.halt_ret == 0) {
      outcome_text = "通过";
      outcome = PERFORMANCE_HTML_OUTCOME_GOOD;
    } else {
      outcome_text = "错误返回";
      outcome = PERFORMANCE_HTML_OUTCOME_BAD;
    }
  } else if (nemu_state.state == NEMU_ABORT) {
    outcome_text = "异常终止";
    outcome = PERFORMANCE_HTML_OUTCOME_ABORT;
  }

  PerformanceHtmlReport report = {
    .label = getenv("NEMU_RUNTIME_LABEL"),
#ifdef NPC_SOC
    .mode = "NPC-SoC",
#else
    .mode = "NPC",
#endif
#ifdef NPC_FPGA_REMOTE
    .memory_statistics_mode = "ServiceOnly",
#else
    .memory_statistics_mode = npc_memory_statistics_mode(),
#endif
    .outcome_text = outcome_text,
    .outcome = outcome,
    .clock_mhz = npc_clock_mhz,
    .cycles = cycles,
    .commits = commits,
    .host_time_us = g_timer,
    .guest_instructions = g_nr_guest_inst,
    .monitoring_available = npc_monitoring_available(),
#ifdef NPC_FPGA_REMOTE
    .trace_dropped = npc_runtime_trace_dropped(),
    .trace_saturated_records = npc_runtime_trace_saturated_records(),
    .latest_samples_are_trace_prefix = npc_runtime_trace_dropped() != 0,
#endif
    .pipeline_features = npc_get_pipeline_features(),
    .last_commit_valid = npc_last_instruction.valid,
    .last_class = last_class_name,
    .last_pc = npc_last_instruction.pc,
    .last_instruction = npc_last_instruction.inst,
    .last_interval = npc_last_instruction.valid
        ? npc_last_instruction.cycles_after - npc_last_instruction.cycles_before : 0,
    .last_commits_before = npc_last_instruction.commits_before,
    .last_commits_after = npc_last_instruction.commits_after,
    .timing_rows = rows,
    .timing_row_count = NPC_TIMING_CLASS_COUNT,
    .aggregate_row = NPC_TIMING_ALL,
  };
  for (uint32_t index = 0; index < NPC_PIPELINE_STALL_COUNT; index++) {
    report.stalls[index] = npc_get_pipeline_stall_count(index);
  }
#ifdef CONFIG_NPC_CACHE_HTML
  npc_fill_cache_configuration(&report);
  if (npc_get_cache_counter != NULL) {
    for (uint32_t cache = 0; cache < PERFORMANCE_HTML_CACHE_COUNT; cache++) {
      for (uint32_t counter = 0; counter < PERFORMANCE_HTML_CACHE_COUNTER_COUNT; counter++) {
        report.cache[cache][counter] = npc_get_cache_counter(cache, counter);
        report.cache_statistics_available |= report.cache[cache][counter] != 0;
      }
    }
  }
  for (uint32_t cache = 0; cache < PERFORMANCE_HTML_CACHE_COUNT; cache++) {
    report.cache_statistics_available |= report.cache_configuration[cache].enabled;
  }
#endif
  for (uint32_t stage = 0; stage < NPC_TIMING_STAGE_COUNT; stage++) {
    report.last_stage[stage] = npc_get_last_timing_stage_cycles(stage);
  }
#ifndef NPC_FPGA_REMOTE
  report.last_memory_queue = npc_get_last_timing_memory_queue_cycles();
#endif
  report.last_total_latency = npc_get_last_timing_total_cycles();

  const char *directory = getenv("NEMU_RUNTIME_OUTPUT_DIR");
  const char *base = directory == NULL || directory[0] == '\0' ? "." : directory;
  size_t path_size = strlen(base) + sizeof("/performance.html");
  char *path = malloc(path_size);
  char *instructions_path = malloc(strlen(base) + sizeof("/instructions.html"));
  char *pipeline_path = malloc(strlen(base) + sizeof("/pipeline.html"));
  char *cache_path = NULL;
#ifdef CONFIG_NPC_CACHE_HTML
  bool cache_enabled = false;
  for (uint32_t cache = 0; cache < PERFORMANCE_HTML_CACHE_COUNT; cache++) {
    cache_enabled |= report.cache_configuration[cache].enabled;
  }
  if (cache_enabled) cache_path = malloc(strlen(base) + sizeof("/cache.html"));
#endif
  if (path == NULL || instructions_path == NULL || pipeline_path == NULL ||
#ifdef CONFIG_NPC_CACHE_HTML
      (cache_enabled && cache_path == NULL)
#else
      false
#endif
  ) {
    fprintf(stderr, "无法分配 NEMU 性能 HTML 路径\n");
    free(path);
    free(instructions_path);
    free(pipeline_path);
    free(cache_path);
    return;
  }
  snprintf(path, path_size, "%s/performance.html", base);
  snprintf(instructions_path, strlen(base) + sizeof("/instructions.html"), "%s/instructions.html", base);
  snprintf(pipeline_path, strlen(base) + sizeof("/pipeline.html"), "%s/pipeline.html", base);
  report.instruction_html_available = access(instructions_path, R_OK) == 0;
  report.pipeline_html_available = access(pipeline_path, R_OK) == 0;
  free(instructions_path);
  free(pipeline_path);

#ifdef CONFIG_NPC_CACHE_HTML
  if (cache_path != NULL) {
    snprintf(cache_path, strlen(base) + sizeof("/cache.html"), "%s/cache.html", base);
    if (performance_html_write_cache(cache_path, &report) == 0) {
      report.cache_html_available = true;
      printf("NEMU 缓存 HTML：%s\n", cache_path);
    } else {
      fprintf(stderr, "写入 NEMU 缓存 HTML 失败：%s（%s）\n", cache_path, strerror(errno));
    }
  }
#endif
  free(cache_path);

  if (performance_html_write(path, &report) == 0) {
    printf("NEMU 性能 HTML：%s\n", path);
  } else {
    fprintf(stderr, "写入 NEMU 性能 HTML 失败：%s（%s）\n", path, strerror(errno));
  }
  free(path);
}
#endif

void npc_print_performance(void) {
  uint64_t cycles, commits;
  npc_read_counters(&cycles, &commits);

#ifdef NPC_SOC
  const char *mode = "NPC-SoC";
#else
  const char *mode = "NPC";
#endif

  if (!npc_monitoring_available()) {
    printf("[%s] hardware performance monitoring is unavailable: this xclbin does not expose the U55C v13 performance-monitor ABI.\n",
           mode);
    printf("[%s] mailbox counters: cycles=%" PRIu64 ", commits=%" PRIu64 "\n",
           mode, cycles, commits);
    return;
  }

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
  const uint64_t progress_started_at = get_time();

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
  // 逐周期推进，使 ready/valid 死锁能够回到 NEMU；交互命令和批处理共用此路径。
  while (npc_get_commit_count() == commits_before) {
    npc_step_cycle();
    if (npc_get_commit_count() != commits_before) {
      break;
    }

    const uint64_t now = get_time();
    if (now - progress_started_at >= NPC_PROGRESS_WATCHDOG_US) {
      npc_report_progress_watchdog(pc, cycles_before, commits_before,
                                   now - progress_started_at,
                                   npc_get_backpressure_reasons());
      set_nemu_state(NEMU_ABORT, pc, -1);
      return;
    }

    // 仿真器在提交前结束不是停顿超时，但同样无法形成有效的架构指令。
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
#ifdef CONFIG_NPC_PERFORMANCE_HTML
    npc_record_pipeline_html(s, commits_after, cycles_after);
#endif
  }

#ifdef CONFIG_NPC_DIFFTEST_NEMU
  // NPC self-difftest: run NEMU software in parallel and compare
  {
    Decode nemu_s;
    nemu_s.pc = pc;
    nemu_s.snpc = pc;
    const uint32_t reference_inst = paddr_read(pc, 4);
    const uint32_t reference_opcode = reference_inst & 0x7f;
    const bool reference_is_store = reference_opcode == 0x23;
    uint64_t expected_store_address = 0;
    uint64_t expected_store_data = 0;
    uint32_t expected_store_strobe = 0;
    uint32_t expected_store_word_bytes = sizeof(word_t);
    bool compare_store = false;
    if (reference_is_store) {
      const uint32_t funct3 = BITS(reference_inst, 14, 12);
      const uint32_t rs1 = BITS(reference_inst, 19, 15);
      const uint32_t rs2 = BITS(reference_inst, 24, 20);
      const int32_t immediate = ((int32_t)reference_inst >> 25 << 5) |
        (int32_t)BITS(reference_inst, 11, 7);
      const uint32_t access_bytes = 1u << (funct3 & 0x3);
      const word_t store_address = cpu.gpr[rs1] + immediate;
      const uint32_t byte_offset = store_address & (expected_store_word_bytes - 1);
      const uint64_t value_mask = access_bytes == 8 ? UINT64_MAX :
        ((UINT64_C(1) << (access_bytes * 8)) - 1);
      const uint64_t source = cpu.gpr[rs2];
      expected_store_address = store_address;
      expected_store_strobe = ((1u << access_bytes) - 1) << byte_offset;
      expected_store_data = (source & value_mask) << (byte_offset * 8);
      compare_store = in_pmem(store_address);
    }
    isa_exec_once(&nemu_s);
    // cpu.gpr[] has been updated by isa_exec_once
    const bool reference_terminated = nemu_state.state == NEMU_END;

    // Collect all architectural mismatches before printing anything.
    struct { char name[12]; uint64_t nemu_val; uint64_t npc_val; } mm[39];
    int nm = 0;
    for (int i = 0; i < 32; i++) {
      uint64_t npc_val = npc_get_reg(i);
      if (cpu.gpr[i] != npc_val) {
        snprintf(mm[nm].name, sizeof(mm[nm].name), "%s", isa_reg_idx2str(i));
        mm[nm].nemu_val = cpu.gpr[i];
        mm[nm].npc_val  = npc_val;
        nm++;
      }
    }
    if (compare_store) {
      const uint64_t store_sequence = npc_get_last_store_sequence();
      const uint64_t store_address = npc_get_last_store_address();
      const uint64_t store_data = npc_get_last_store_data();
      const uint32_t store_strobe = npc_get_last_store_strobe();
      const uint32_t store_word_bytes = npc_get_last_store_word_bytes();
      uint64_t store_data_mask = 0;
      for (uint32_t lane = 0; lane < store_word_bytes && lane < 8; lane++) {
        if (expected_store_strobe & (1u << lane)) {
          store_data_mask |= UINT64_C(0xff) << (lane * 8);
        }
      }
#define RECORD_STORE_MISMATCH(field, expected, actual) do { \
        if ((uint64_t)(expected) != (uint64_t)(actual)) { \
          snprintf(mm[nm].name, sizeof(mm[nm].name), "%s", (field)); \
          mm[nm].nemu_val = (uint64_t)(expected); \
          mm[nm].npc_val = (uint64_t)(actual); \
          nm++; \
        } \
      } while (0)
      RECORD_STORE_MISMATCH("store_seen", 1, store_sequence != 0);
      RECORD_STORE_MISMATCH("store_addr", expected_store_address, store_address);
      RECORD_STORE_MISMATCH("store_data", expected_store_data & store_data_mask,
        store_data & store_data_mask);
      RECORD_STORE_MISMATCH("store_strb", expected_store_strobe, store_strobe);
      RECORD_STORE_MISMATCH("store_bytes", expected_store_word_bytes, store_word_bytes);
#undef RECORD_STORE_MISMATCH
    }
    // 本地 AM 的 EBREAK 会把 NEMU 置为结束状态，其 dnpc 只是监视器结束哨兵，
    // 不代表还会提交的架构 PC。终止指令仍比较寄存器和内存副作用。
    if (!reference_terminated && nemu_s.dnpc != s->dnpc) {
      snprintf(mm[nm].name, sizeof(mm[nm].name), "next_pc");
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
  
  // Local NPC preserves the historical AM test convention. FPGA remote mode
  // ends only when its independent mailbox completion event arrives.
#ifndef NPC_FPGA_REMOTE
  if (s->isa.inst == 0x00100073) {
    extern void set_nemu_state(int state, vaddr_t pc, int halt_ret);
    extern uint64_t npc_get_reg(int idx);
    set_nemu_state(NEMU_END, s->pc, (int)npc_get_reg(10));
  }
#endif
  
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
    // cycle 级 watchdog 可能在 exec_once 填充 Decode 前中止；此时不能追踪或自查该不完整记录。
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
#ifdef NPC_FPGA_REMOTE
    if (npc_runtime_has_completed()) {
      cpu.pc = npc_runtime_completion_next_pc();
      set_nemu_state(NEMU_END, npc_runtime_completion_pc(),
                     (int)npc_runtime_completion_code());
      break;
    }
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
  const uint64_t cycles_before = npc_get_cycle_count();
  const bool interactive = npc_debug_is_interactive();
  struct sigaction action = {0};
  struct sigaction previous = {0};
  uint64_t last_progress_commits = commits_before;
  uint64_t progress_started_at = get_time();
  bool watchdog_fired = false;
  action.sa_handler = npc_interrupt_handler;
  sigemptyset(&action.sa_mask);
  npc_interrupt_requested = 0;
  sigaction(SIGINT, &action, &previous);

  if (interactive && npc_debug_resume() != 0) {
    fprintf(stderr, "FPGA resume failed: %s\n", strerror(errno));
    set_nemu_state(NEMU_ABORT, cpu.pc, -1);
  } else {
    while (!npc_is_finished() && !npc_interrupt_requested) {
      npc_step_cycle();
      if (npc_is_finished() || npc_interrupt_requested) break;

      const uint64_t commits = npc_get_commit_count();
      if (commits != last_progress_commits) {
        last_progress_commits = commits;
        progress_started_at = get_time();
        continue;
      }
      const uint64_t now = get_time();
      if (now - progress_started_at >= NPC_PROGRESS_WATCHDOG_US) {
        npc_report_progress_watchdog(cpu.pc, cycles_before, commits_before,
                                     now - progress_started_at,
                                     npc_get_backpressure_reasons());
        set_nemu_state(NEMU_ABORT, cpu.pc, -1);
        watchdog_fired = true;
        break;
      }
    }
    if (npc_interrupt_requested) {
      if (interactive) {
        if (npc_debug_halt() != 0) {
          fprintf(stderr, "FPGA halt after Ctrl-C failed: %s\n", strerror(errno));
          set_nemu_state(NEMU_ABORT, cpu.pc, -1);
        } else {
          cpu.pc = npc_debug_stop_pc();
          nemu_state.state = NEMU_STOP;
        }
      } else {
        // 批处理取消不能伪装成通过；atexit 清理会把 mailbox 控制的核心重新复位。
        set_nemu_state(NEMU_ABORT, npc_get_pc(), -1);
      }
    } else if (!watchdog_fired && npc_runtime_has_completed()) {
      cpu.pc = npc_runtime_completion_next_pc();
      set_nemu_state(NEMU_END, npc_runtime_completion_pc(),
                     (int)npc_runtime_completion_code());
    } else if (!watchdog_fired && npc_is_finished()) {
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
#if defined(NPC) && defined(CONFIG_NPC_PERFORMANCE_HTML)
  npc_finish_pipeline_html();
  npc_finish_performance_html();
#endif
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
    Log("NPC self-difftest: " ANSI_FMT("ON", ANSI_FG_GREEN)
        " (NEMU reference: GPR/next-PC/main-memory stores)");
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
  if (n == UINT64_MAX && !has_watchpoints())
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
