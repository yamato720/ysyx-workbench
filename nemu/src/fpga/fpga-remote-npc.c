#include "fpga-mailbox.h"

#include <errno.h>
#include <inttypes.h>
#include <limits.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sched.h>
#include <time.h>

#if defined(__has_include)
#if __has_include(<generated/autoconf.h>)
#include <generated/autoconf.h>
#endif
#endif

#ifdef CONFIG_FPGA_BACKEND_ZCU102
#include "fpga-zcu102-uio.h"
#endif
#ifdef CONFIG_FPGA_BACKEND_U55C
#include "fpga-u55c-xrt.h"
#endif

static bool runtime_finished;
static bool runtime_failed;
static bool runtime_completed;
static uint64_t runtime_completion_code;
static uint64_t runtime_completion_pc;
static uint64_t runtime_completion_next_pc;
static uint64_t observed_commits;
static bool runtime_started;
static bool runtime_interactive;
static uint32_t debug_capabilities;
static uint32_t debug_sequence;
static uint32_t debug_timeout_ms = 5000;
static bool runtime_trace_available;
static bool runtime_trace_loaded;
static struct npc_fpga_trace_record *runtime_trace_records;
static size_t runtime_trace_record_count;
static uint64_t runtime_trace_dropped;
static uint64_t runtime_trace_saturated_records;

enum {
  NEMU_FPGA_TRACE_CLASS_COUNT = 29,
  NEMU_FPGA_TRACE_ALL_CLASS = NEMU_FPGA_TRACE_CLASS_COUNT - 1,
};

struct runtime_trace_last_sample {
  bool valid;
  uint64_t pc;
  uint32_t instruction;
  uint64_t stage[NPC_FPGA_TRACE_STAGE_COUNT];
};

static struct runtime_trace_last_sample runtime_trace_last[NEMU_FPGA_TRACE_CLASS_COUNT];
#ifdef CONFIG_FPGA_BACKEND_ZCU102
static struct nemu_fpga_zcu102_uio zcu102;
#endif
#ifdef CONFIG_FPGA_BACKEND_U55C
static struct nemu_fpga_u55c_xrt u55c;
#endif
static struct nemu_fpga_mailbox_io *runtime_io;

extern bool sdb_is_batch_mode(void);
int npc_step_cycle(void);

static int capture_runtime_trace(void);

static void release_runtime_trace(void) {
  free(runtime_trace_records);
  runtime_trace_records = NULL;
  runtime_trace_record_count = 0;
  runtime_trace_dropped = 0;
  runtime_trace_saturated_records = 0;
  memset(runtime_trace_last, 0, sizeof(runtime_trace_last));
  runtime_trace_loaded = false;
}

static void classify_trace_instruction(uint32_t instruction,
                                       uint32_t *major_class,
                                       bool *has_detail,
                                       uint32_t *detail_class) {
  const uint32_t opcode = instruction & 0x7f;
  const uint32_t funct3 = (instruction >> 12) & 0x7;
  const uint32_t funct7 = (instruction >> 25) & 0x7f;

  *major_class = 0;
  *has_detail = false;
  *detail_class = 0;
  if (opcode == 0x03) {
    static const uint32_t load_classes[8] = {4, 5, 6, 7, 8, 9, 10, 4};
    *major_class = 1;
    *has_detail = true;
    *detail_class = load_classes[funct3];
  } else if (opcode == 0x23) {
    static const uint32_t store_classes[8] = {11, 12, 13, 14, 11, 11, 11, 11};
    *major_class = 2;
    *has_detail = true;
    *detail_class = store_classes[funct3];
  } else if ((opcode == 0x33 || opcode == 0x3b) && funct7 == 0x01) {
    static const uint32_t m_classes[8] = {15, 16, 17, 18, 19, 20, 21, 22};
    static const uint32_t m_word_classes[8] = {23, 23, 23, 23, 24, 25, 26, 27};
    *major_class = 3;
    *has_detail = true;
    *detail_class = opcode == 0x3b ? m_word_classes[funct3] : m_classes[funct3];
  }
}

static void cache_trace_last_sample(uint32_t timing_class,
                                    const struct npc_fpga_trace_record *record) {
  struct runtime_trace_last_sample *sample = &runtime_trace_last[timing_class];
  sample->valid = true;
  sample->pc = record->pc;
  sample->instruction = record->instruction;
  for (size_t stage = 0; stage < NPC_FPGA_TRACE_STAGE_COUNT; stage++)
    sample->stage[stage] = record->stage[stage];
}

static void cache_trace_record(const struct npc_fpga_trace_record *record) {
  uint32_t major_class;
  uint32_t detail_class;
  bool has_detail;
  classify_trace_instruction(record->instruction, &major_class, &has_detail, &detail_class);
  cache_trace_last_sample(major_class, record);
  if (has_detail) cache_trace_last_sample(detail_class, record);
  cache_trace_last_sample(NEMU_FPGA_TRACE_ALL_CLASS, record);
  if (record->saturation != 0) runtime_trace_saturated_records++;
}

static uint64_t parse_environment_u64(const char *name, uint64_t default_value) {
  const char *text = getenv(name);
  if (text == NULL || *text == '\0') return default_value;
  char *end = NULL;
  errno = 0;
  const uint64_t value = strtoull(text, &end, 0);
  if (errno != 0 || end == text || *end != '\0') {
    fprintf(stderr, "invalid %s value: %s\n", name, text);
    exit(EXIT_FAILURE);
  }
  return value;
}

static uint32_t read32(uint32_t offset) {
  return runtime_io->read32(runtime_io->opaque, offset);
}

static uint64_t read64(uint32_t low, uint32_t high) {
  return nemu_fpga_runtime_read_counter(runtime_io, low, high);
}

static void select_register(unsigned gpr) {
  runtime_io->write32(runtime_io->opaque, NEMU_FPGA_RT_REGISTER_INDEX, gpr & 31);
}

static uint64_t monotonic_milliseconds(void) {
  struct timespec now;
  if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return 0;
  return (uint64_t)now.tv_sec * 1000 + (uint64_t)now.tv_nsec / 1000000;
}

static bool debug_is_halted(void) {
  return (read32(NEMU_FPGA_DEBUG_STATUS) & NEMU_FPGA_DEBUG_HALTED) != 0;
}

static int wait_for_debug_status(uint32_t sequence, bool wait_for_sequence) {
  const uint64_t started = monotonic_milliseconds();
  uint32_t last_status = 0;
  for (;;) {
    npc_step_cycle();
    if (runtime_failed) {
      errno = EIO;
      return -1;
    }
    const uint32_t status = read32(NEMU_FPGA_DEBUG_STATUS);
    last_status = status;
    if (status & NEMU_FPGA_DEBUG_ERROR) {
      errno = EPROTO;
      return -1;
    }
    if ((!wait_for_sequence ||
         read32(NEMU_FPGA_DEBUG_COMPLETED_SEQUENCE) == sequence) &&
        !(status & (NEMU_FPGA_DEBUG_HALTING | NEMU_FPGA_DEBUG_STEPPING))) {
      return 0;
    }
    if (monotonic_milliseconds() - started >= debug_timeout_ms) {
      fprintf(stderr,
              "FPGA debug state at timeout: debug-status=0x%08" PRIx32
              " runtime-status=0x%08" PRIx32 " control=0x%08" PRIx32
              " pc=0x%016" PRIx64 " commits=%" PRIu64 "\n",
              last_status, read32(NEMU_FPGA_RT_STATUS),
              read32(NEMU_FPGA_RT_CONTROL),
              read64(NEMU_FPGA_RT_CURRENT_PC_LOW, NEMU_FPGA_RT_CURRENT_PC_HIGH),
              read64(NEMU_FPGA_RT_COMMIT_COUNT_LOW, NEMU_FPGA_RT_COMMIT_COUNT_HIGH));
      errno = ETIMEDOUT;
      return -1;
    }
  }
}

static int issue_debug_command(enum nemu_fpga_debug_command command) {
  if (!runtime_interactive || runtime_io == NULL) {
    errno = ENOTSUP;
    return -1;
  }
  debug_sequence++;
  if (debug_sequence == 0) debug_sequence++;
  runtime_io->write32(runtime_io->opaque, NEMU_FPGA_DEBUG_COMMAND_SEQUENCE,
                      debug_sequence);
  runtime_io->write32(runtime_io->opaque, NEMU_FPGA_DEBUG_COMMAND, command);
  return wait_for_debug_status(debug_sequence, true);
}

bool npc_debug_is_interactive(void) { return runtime_interactive; }
bool npc_debug_is_halted(void) { return runtime_interactive && debug_is_halted(); }
int npc_debug_halt(void) {
  const int status = issue_debug_command(NEMU_FPGA_DEBUG_HALT);
  if (status == 0 && capture_runtime_trace() != 0) return -1;
  return status;
}
int npc_debug_resume(void) {
  const int status = issue_debug_command(NEMU_FPGA_DEBUG_RESUME);
  if (status == 0) release_runtime_trace();
  return status;
}
int npc_debug_step(void) { return issue_debug_command(NEMU_FPGA_DEBUG_STEP); }

uint32_t npc_debug_stop_reason(void) {
  return runtime_interactive ? read32(NEMU_FPGA_DEBUG_STOP_REASON) & 0xf : 0;
}

uint64_t npc_debug_stop_pc(void) {
  return runtime_interactive
      ? read64(NEMU_FPGA_DEBUG_STOP_PC_LOW, NEMU_FPGA_DEBUG_STOP_PC_HIGH)
      : read64(NEMU_FPGA_RT_COMMIT_NEXT_PC_LOW, NEMU_FPGA_RT_COMMIT_NEXT_PC_HIGH);
}

static int configure_u55c_runtime_trace(void) {
#ifdef CONFIG_FPGA_BACKEND_U55C
  const uint32_t capability = read32(NEMU_FPGA_TRACE_CAPABILITY);
  if (capability == 0) return 0;
  if (capability != NEMU_FPGA_TRACE_ENABLED ||
      read32(NEMU_FPGA_TRACE_FORMAT) != NEMU_FPGA_TRACE_FORMAT_V2 ||
      read32(NEMU_FPGA_TRACE_RECORD_BYTES) != NEMU_FPGA_TRACE_RECORD_BYTES_V2 ||
      read32(NEMU_FPGA_TRACE_MAX_RECORDS) != NEMU_FPGA_TRACE_MAX_RECORDS_V2) {
    errno = EPROTO;
    return -1;
  }
  if (nemu_fpga_u55c_xrt_allocate_trace(&u55c, 1,
                                        NEMU_FPGA_TRACE_BUFFER_BYTES_V2) != 0) {
    return -1;
  }
  const uint64_t trace_address = nemu_fpga_u55c_xrt_trace_address(&u55c);
  if (trace_address == 0) {
    errno = EIO;
    return -1;
  }
  runtime_io->write32(runtime_io->opaque, NEMU_FPGA_TRACE_HOST_BASE_LOW,
                      (uint32_t)trace_address);
  runtime_io->write32(runtime_io->opaque, NEMU_FPGA_TRACE_HOST_BASE_HIGH,
                      (uint32_t)(trace_address >> 32));
  if (nemu_fpga_u55c_xrt_failed(&u55c)) return -1;
  runtime_trace_available = true;
  return 0;
#else
  return 0;
#endif
}

static int capture_runtime_trace(void) {
  if (!runtime_trace_available || runtime_trace_loaded) return 0;
#ifndef CONFIG_FPGA_BACKEND_U55C
  errno = ENOTSUP;
  return -1;
#else
  const uint64_t started = monotonic_milliseconds();
  while ((read32(NEMU_FPGA_TRACE_FLAGS) & NEMU_FPGA_TRACE_DRAINED) == 0) {
    if (nemu_fpga_u55c_xrt_failed(&u55c)) {
      errno = EIO;
      return -1;
    }
    if (monotonic_milliseconds() - started >= debug_timeout_ms) {
      errno = ETIMEDOUT;
      return -1;
    }
    sched_yield();
  }

  const uint64_t records = read64(NEMU_FPGA_TRACE_RECORDS_LOW,
                                  NEMU_FPGA_TRACE_RECORDS_HIGH);
  const uint64_t dropped = read64(NEMU_FPGA_TRACE_DROPPED_LOW,
                                  NEMU_FPGA_TRACE_DROPPED_HIGH);
  if (records > NEMU_FPGA_TRACE_MAX_RECORDS_V2 ||
      records > SIZE_MAX / sizeof(*runtime_trace_records)) {
    errno = EPROTO;
    return -1;
  }
  const size_t record_count = (size_t)records;
  const size_t bytes = record_count * sizeof(*runtime_trace_records);
  if (bytes > u55c.trace_size) {
    errno = EPROTO;
    return -1;
  }

  struct npc_fpga_trace_record *records_buffer = NULL;
  if (record_count != 0) {
    records_buffer = calloc(record_count, sizeof(*records_buffer));
    if (records_buffer == NULL) {
      errno = ENOMEM;
      return -1;
    }
    if (nemu_fpga_u55c_xrt_read_trace(&u55c, 0, records_buffer, bytes) != 0) {
      free(records_buffer);
      return -1;
    }
    if (nemu_fpga_runtime_validate_trace_records(records_buffer, record_count) != 0) {
      free(records_buffer);
      return -1;
    }
  }
  for (size_t index = 0; index < record_count; index++) cache_trace_record(&records_buffer[index]);
  runtime_trace_records = records_buffer;
  runtime_trace_record_count = record_count;
  runtime_trace_dropped = dropped;
  runtime_trace_loaded = true;
  return 0;
#endif
}

bool npc_hardware_monitoring_available(void) { return runtime_trace_available; }

int npc_runtime_visit_trace(npc_fpga_trace_visitor visitor, void *opaque) {
  if (!runtime_trace_available || visitor == NULL) {
    errno = runtime_trace_available ? EINVAL : ENOTSUP;
    return -1;
  }
  if (!runtime_trace_loaded) {
    errno = EBUSY;
    return -1;
  }
  for (size_t index = 0; index < runtime_trace_record_count; index++) {
    if (visitor(&runtime_trace_records[index], index + 1, opaque) != 0) return -1;
  }
  return 0;
}

uint64_t npc_runtime_trace_dropped(void) {
  return runtime_trace_available && runtime_trace_loaded ? runtime_trace_dropped : 0;
}

uint64_t npc_runtime_trace_saturated_records(void) {
  return runtime_trace_available && runtime_trace_loaded ? runtime_trace_saturated_records : 0;
}

void npc_init(void) {
  runtime_interactive = !sdb_is_batch_mode();
  runtime_trace_available = false;
  release_runtime_trace();
  const uint64_t timeout = parse_environment_u64("NEMU_FPGA_DEBUG_TIMEOUT_MS", 5000);
  if (timeout == 0 || timeout > UINT32_MAX) {
    fprintf(stderr, "NEMU_FPGA_DEBUG_TIMEOUT_MS must be in range 1..%u\n", UINT32_MAX);
    exit(EXIT_FAILURE);
  }
  debug_timeout_ms = (uint32_t)timeout;
#ifdef CONFIG_FPGA_BACKEND_ZCU102
  const char *control_device = getenv("NEMU_FPGA_UIO");
  const char *memory_device = getenv("NEMU_FPGA_DDR_DEVICE");
  if (control_device == NULL) control_device = "/dev/uio0";
  if (memory_device == NULL) memory_device = "/dev/mem";
  const uint64_t control_size = parse_environment_u64("NEMU_FPGA_UIO_SIZE", 4096);
  const uint64_t memory_physical = parse_environment_u64("NEMU_FPGA_DDR_PHYS", 0x70000000);
  const uint64_t memory_size = parse_environment_u64("NEMU_FPGA_DDR_SIZE", 0x08000000);
  if (control_size > SIZE_MAX || memory_size > SIZE_MAX) {
    fprintf(stderr, "ZCU102 FPGA mapping parameter is too large for this host\n");
    exit(EXIT_FAILURE);
  }
  if (nemu_fpga_zcu102_uio_open(&zcu102, control_device, (size_t)control_size,
                                memory_device, memory_physical, (size_t)memory_size) != 0) {
    fprintf(stderr, "cannot open ZCU102 FPGA runtime: %s\n", strerror(errno));
    exit(EXIT_FAILURE);
  }
  runtime_io = &zcu102.mailbox;
  if (zcu102.guest_memory_size < CONFIG_MSIZE) {
    fprintf(stderr, "ZCU102 DDR window is smaller than CONFIG_MSIZE\n");
    exit(EXIT_FAILURE);
  }
  if (nemu_fpga_runtime_hold_reset(runtime_io) != 0) {
    fprintf(stderr, "cannot hold ZCU102 NPC in reset\n");
    exit(EXIT_FAILURE);
  }
#elif defined(CONFIG_FPGA_BACKEND_U55C)
  const char *xclbin = getenv("NEMU_FPGA_XCLBIN");
  const char *kernel = getenv("NEMU_FPGA_KERNEL");
  if (xclbin == NULL || *xclbin == '\0') {
    fprintf(stderr, "NEMU_FPGA_XCLBIN must name the U55C xclbin\n");
    exit(EXIT_FAILURE);
  }
  if (kernel == NULL || *kernel == '\0')
    kernel = "NpcFpgaKernel:NpcFpgaKernel_1";
  const uint64_t device_index = parse_environment_u64("NEMU_FPGA_DEVICE_INDEX", 0);
  const uint64_t memory_group = parse_environment_u64("NEMU_FPGA_HBM_BANK", 0);
  const uint64_t memory_size = parse_environment_u64("NEMU_FPGA_HBM_SIZE", 0x08000000);
  if (device_index > UINT_MAX || memory_group > UINT_MAX || memory_size > SIZE_MAX) {
    fprintf(stderr, "U55C FPGA runtime parameter is too large for this host\n");
    exit(EXIT_FAILURE);
  }
  if (nemu_fpga_u55c_xrt_open(&u55c, (unsigned)device_index, xclbin, kernel,
                              (unsigned)memory_group, (size_t)memory_size) != 0) {
    fprintf(stderr, "cannot open U55C FPGA runtime: %s\n",
            nemu_fpga_u55c_xrt_error(&u55c));
    exit(EXIT_FAILURE);
  }
  runtime_io = &u55c.mailbox;
  if (u55c.memory_size < CONFIG_MSIZE) {
    fprintf(stderr, "U55C HBM window is smaller than CONFIG_MSIZE\n");
    exit(EXIT_FAILURE);
  }
  if (configure_u55c_runtime_trace() != 0) {
    fprintf(stderr, "cannot configure U55C v13 performance monitor: %s\n",
            nemu_fpga_u55c_xrt_error(&u55c));
    exit(EXIT_FAILURE);
  }
  if (nemu_fpga_runtime_hold_reset(runtime_io) != 0 ||
      nemu_fpga_u55c_xrt_failed(&u55c)) {
    fprintf(stderr, "cannot hold U55C NPC in reset: %s\n",
            nemu_fpga_u55c_xrt_error(&u55c));
    exit(EXIT_FAILURE);
  }
#endif
  if (runtime_interactive) {
    const uint32_t protocol = read32(NEMU_FPGA_DEBUG_PROTOCOL);
    debug_capabilities = read32(NEMU_FPGA_DEBUG_CAPABILITIES);
    const uint32_t required = NEMU_FPGA_DEBUG_CAP_HALT_STEP |
        NEMU_FPGA_DEBUG_CAP_TARGET_MEMORY | NEMU_FPGA_DEBUG_CAP_CSR_SNAPSHOT;
    if (protocol != NEMU_FPGA_DEBUG_PROTOCOL_V6 ||
        (debug_capabilities & required) != required) {
      fprintf(stderr,
              "interactive FPGA debugging requires a v6 bitstream/xclbin; rebuild=1 is required\n");
      exit(EXIT_FAILURE);
    }
    debug_sequence = read32(NEMU_FPGA_DEBUG_COMPLETED_SEQUENCE);
  }
}

void npc_load_image(const void *image, size_t image_size) {
  release_runtime_trace();
#ifdef CONFIG_FPGA_BACKEND_ZCU102
  if (nemu_fpga_zcu102_uio_load(&zcu102, 0, image, image_size) != 0) {
    fprintf(stderr, "cannot load image into ZCU102 reserved DDR: %s\n", strerror(errno));
    exit(EXIT_FAILURE);
  }
#elif defined(CONFIG_FPGA_BACKEND_U55C)
  if (nemu_fpga_u55c_xrt_load(&u55c, 0, image, image_size) != 0) {
    fprintf(stderr, "cannot load image into U55C HBM: %s\n",
            nemu_fpga_u55c_xrt_error(&u55c));
    exit(EXIT_FAILURE);
  }
#endif
  runtime_finished = false;
  runtime_failed = false;
  runtime_completed = false;
  runtime_completion_code = 0;
  runtime_completion_pc = 0;
  runtime_completion_next_pc = 0;
  const int start_result = runtime_interactive
      ? nemu_fpga_runtime_start_halted(runtime_io)
      : nemu_fpga_runtime_start(runtime_io);
  if (start_result != 0) {
    fprintf(stderr, "cannot release FPGA NPC reset\n");
    exit(EXIT_FAILURE);
  }
  runtime_started = true;
  observed_commits = read64(NEMU_FPGA_RT_COMMIT_COUNT_LOW, NEMU_FPGA_RT_COMMIT_COUNT_HIGH);
  if (runtime_interactive && wait_for_debug_status(0, false) != 0) {
    fprintf(stderr, "FPGA failed to reach its initial halted state: %s\n", strerror(errno));
    exit(EXIT_FAILURE);
  }
}

int npc_step_cycle(void) {
  if (!runtime_started || runtime_finished || runtime_failed) return 0;
#ifdef CONFIG_FPGA_BACKEND_U55C
  if (nemu_fpga_u55c_xrt_failed(&u55c)) {
    fprintf(stderr, "U55C XRT access failed: %s\n", nemu_fpga_u55c_xrt_error(&u55c));
    runtime_failed = true;
    return 0;
  }
#endif
  const struct nemu_fpga_runtime_event event =
      nemu_fpga_runtime_service_once(runtime_io);
  if (event.type == NEMU_FPGA_RT_EVENT_PUTCH) {
    putchar((int)(event.value & 0xff));
    fflush(stdout);
  } else if (event.type == NEMU_FPGA_RT_EVENT_COMPLETION) {
    runtime_completed = true;
    runtime_completion_code = event.value;
    runtime_completion_pc = read64(NEMU_FPGA_RT_COMPLETION_PC_LOW,
                                   NEMU_FPGA_RT_COMPLETION_PC_HIGH);
    runtime_completion_next_pc = read64(NEMU_FPGA_RT_COMPLETION_NEXT_PC_LOW,
                                        NEMU_FPGA_RT_COMPLETION_NEXT_PC_HIGH);
    runtime_finished = true;
    if (capture_runtime_trace() != 0) {
      fprintf(stderr, "cannot read U55C runtime trace: %s\n", strerror(errno));
      runtime_completed = false;
      runtime_failed = true;
    }
  } else if (event.type == NEMU_FPGA_RT_EVENT_ERROR) {
    fprintf(stderr, "FPGA runtime protocol failure: %d\n", event.error);
    runtime_failed = true;
  }
  const uint64_t commits = read64(NEMU_FPGA_RT_COMMIT_COUNT_LOW,
                                  NEMU_FPGA_RT_COMMIT_COUNT_HIGH);
  const bool changed = commits != observed_commits;
  observed_commits = commits;
  if (!changed) {
#ifdef CONFIG_FPGA_BACKEND_ZCU102
    if (nemu_fpga_zcu102_uio_wait_interrupt(&zcu102, 1) < 0) {
      fprintf(stderr, "ZCU102 UIO interrupt wait failed: %s\n", strerror(errno));
      runtime_failed = true;
      return 0;
    }
#else
    /* XRT 当前没有可由宿主消费的 IRQ 文件描述符，保留显式轮询。 */
    sched_yield();
#endif
  }
  return changed;
}

void npc_single_run(void) {
  const uint64_t before = observed_commits;
  while (!runtime_finished && !runtime_failed && observed_commits == before) npc_step_cycle();
}

void npc_getvalue(void) {}

uint64_t npc_get_pc(void) {
  if (runtime_completed) return runtime_completion_next_pc;
  return npc_debug_is_halted() ? npc_debug_stop_pc()
                               : read64(NEMU_FPGA_RT_COMMIT_NEXT_PC_LOW,
                                        NEMU_FPGA_RT_COMMIT_NEXT_PC_HIGH);
}

uint32_t npc_get_inst(void) {
  if (runtime_completed) return UINT32_C(0x00100073);
  return read32(NEMU_FPGA_RT_COMMIT_INSTRUCTION);
}

uint64_t npc_get_last_commit_pc(void) {
  if (runtime_completed) return runtime_completion_pc;
  return read64(NEMU_FPGA_RT_COMMIT_PC_LOW, NEMU_FPGA_RT_COMMIT_PC_HIGH);
}

uint64_t npc_get_current_pc(void) {
  if (runtime_completed) return runtime_completion_next_pc;
  return npc_debug_is_halted() ? npc_debug_stop_pc()
                               : read64(NEMU_FPGA_RT_CURRENT_PC_LOW,
                                        NEMU_FPGA_RT_CURRENT_PC_HIGH);
}

uint32_t npc_get_frontend_instruction(void) {
  return read32(NEMU_FPGA_RT_FRONTEND_INSTRUCTION);
}

uint64_t npc_get_reg(int index) {
  if (index < 0 || index >= 32) return 0;
  select_register((unsigned)index);
  return read64(NEMU_FPGA_RT_GPR_LOW, NEMU_FPGA_RT_GPR_HIGH);
}

uint64_t npc_get_mstatus(void) {
  if (!runtime_interactive)
    return read64(NEMU_FPGA_RT_MSTATUS_LOW, NEMU_FPGA_RT_MSTATUS_HIGH);
  runtime_io->write32(runtime_io->opaque, NEMU_FPGA_DEBUG_CSR_INDEX,
                      NEMU_FPGA_CSR_MSTATUS);
  return read64(NEMU_FPGA_DEBUG_CSR_LOW, NEMU_FPGA_DEBUG_CSR_HIGH);
}

static uint64_t read_debug_csr(enum nemu_fpga_debug_csr csr) {
  if (!runtime_interactive || !(debug_capabilities & NEMU_FPGA_DEBUG_CAP_CSR_SNAPSHOT))
    return 0;
  runtime_io->write32(runtime_io->opaque, NEMU_FPGA_DEBUG_CSR_INDEX, csr);
  return read64(NEMU_FPGA_DEBUG_CSR_LOW, NEMU_FPGA_DEBUG_CSR_HIGH);
}

uint64_t npc_get_mcause(void) { return read_debug_csr(NEMU_FPGA_CSR_MCAUSE); }
uint64_t npc_get_mepc(void) { return read_debug_csr(NEMU_FPGA_CSR_MEPC); }
uint64_t npc_get_mtvec(void) { return read_debug_csr(NEMU_FPGA_CSR_MTVEC); }

int npc_read_memory(uint64_t guest_address, void *destination, size_t size) {
  if (!runtime_interactive || !(debug_capabilities & NEMU_FPGA_DEBUG_CAP_TARGET_MEMORY) ||
      !debug_is_halted() || destination == NULL || guest_address < CONFIG_MBASE ||
      guest_address - CONFIG_MBASE > CONFIG_MSIZE ||
      size > CONFIG_MSIZE - (size_t)(guest_address - CONFIG_MBASE)) {
    errno = guest_address < CONFIG_MBASE || guest_address - CONFIG_MBASE > CONFIG_MSIZE
        ? EFAULT : EBUSY;
    return -1;
  }
  const size_t offset = (size_t)(guest_address - CONFIG_MBASE);
#ifdef CONFIG_FPGA_BACKEND_ZCU102
  return nemu_fpga_zcu102_uio_read(&zcu102, offset, destination, size);
#elif defined(CONFIG_FPGA_BACKEND_U55C)
  return nemu_fpga_u55c_xrt_read(&u55c, offset, destination, size);
#else
  errno = ENOTSUP;
  return -1;
#endif
}

uint64_t npc_get_cycle_count(void) {
  if (runtime_trace_available)
    return read64(NEMU_FPGA_TRACE_CYCLES_LOW, NEMU_FPGA_TRACE_CYCLES_HIGH);
  return read64(NEMU_FPGA_RT_CYCLE_LOW, NEMU_FPGA_RT_CYCLE_HIGH);
}

uint64_t npc_get_commit_count(void) {
  if (runtime_trace_available)
    return read64(NEMU_FPGA_TRACE_COMMITS_LOW, NEMU_FPGA_TRACE_COMMITS_HIGH);
  return read64(NEMU_FPGA_RT_COMMIT_COUNT_LOW, NEMU_FPGA_RT_COMMIT_COUNT_HIGH);
}

uint32_t npc_get_backpressure_reasons(void) {
  return read32(NEMU_FPGA_RT_BACKPRESSURE) & 0x1ff;
}

uint64_t npc_get_cache_counter(uint32_t cache, uint32_t counter) {
  static const uint32_t low_offsets[2][5] = {
    {NEMU_FPGA_CACHE_ICACHE_HITS_LOW, NEMU_FPGA_CACHE_ICACHE_MISSES_LOW,
     NEMU_FPGA_CACHE_ICACHE_REFILLS_LOW, NEMU_FPGA_CACHE_ICACHE_WRITEBACKS_LOW,
     NEMU_FPGA_CACHE_ICACHE_EVICTIONS_LOW},
    {NEMU_FPGA_CACHE_DCACHE_HITS_LOW, NEMU_FPGA_CACHE_DCACHE_MISSES_LOW,
     NEMU_FPGA_CACHE_DCACHE_REFILLS_LOW, NEMU_FPGA_CACHE_DCACHE_WRITEBACKS_LOW,
     NEMU_FPGA_CACHE_DCACHE_EVICTIONS_LOW},
  };
  static const uint32_t high_offsets[2][5] = {
    {NEMU_FPGA_CACHE_ICACHE_HITS_HIGH, NEMU_FPGA_CACHE_ICACHE_MISSES_HIGH,
     NEMU_FPGA_CACHE_ICACHE_REFILLS_HIGH, NEMU_FPGA_CACHE_ICACHE_WRITEBACKS_HIGH,
     NEMU_FPGA_CACHE_ICACHE_EVICTIONS_HIGH},
    {NEMU_FPGA_CACHE_DCACHE_HITS_HIGH, NEMU_FPGA_CACHE_DCACHE_MISSES_HIGH,
     NEMU_FPGA_CACHE_DCACHE_REFILLS_HIGH, NEMU_FPGA_CACHE_DCACHE_WRITEBACKS_HIGH,
     NEMU_FPGA_CACHE_DCACHE_EVICTIONS_HIGH},
  };
  if (!runtime_trace_available || cache >= 2 || counter >= 5) return 0;
  return read64(low_offsets[cache][counter], high_offsets[cache][counter]);
}

uint32_t npc_get_cache_configuration(uint32_t cache, uint32_t field) {
  if (!runtime_trace_available || cache >= 2) return 0;
  const uint32_t capability = read32(NEMU_FPGA_CACHE_CAPABILITY);
  const bool instruction = cache == 0;
  const uint32_t policy = read32(instruction ? NEMU_FPGA_CACHE_ICACHE_POLICY
                                             : NEMU_FPGA_CACHE_DCACHE_POLICY);
  switch (field) {
    case 0: return (capability >> cache) & 1u;
    case 1: return read32(instruction ? NEMU_FPGA_CACHE_ICACHE_CAPACITY_BYTES
                                      : NEMU_FPGA_CACHE_DCACHE_CAPACITY_BYTES);
    case 2: return read32(instruction ? NEMU_FPGA_CACHE_ICACHE_LINE_BYTES
                                      : NEMU_FPGA_CACHE_DCACHE_LINE_BYTES);
    case 3: return read32(instruction ? NEMU_FPGA_CACHE_ICACHE_WAYS
                                      : NEMU_FPGA_CACHE_DCACHE_WAYS);
    case 4: return read32(instruction ? NEMU_FPGA_CACHE_ICACHE_SETS
                                      : NEMU_FPGA_CACHE_DCACHE_SETS);
    case 5: return (policy >> NEMU_FPGA_CACHE_POLICY_MAPPING_SHIFT) & 0x3u;
    case 6: return (policy >> NEMU_FPGA_CACHE_POLICY_REPLACEMENT_SHIFT) & 0x3u;
    case 7: return (policy >> NEMU_FPGA_CACHE_POLICY_READ_MISS_SHIFT) & 0x1u;
    case 8: return (policy >> NEMU_FPGA_CACHE_POLICY_WRITE_POLICY_SHIFT) & 0x1u;
    case 9: return (policy >> NEMU_FPGA_CACHE_POLICY_WRITE_MISS_SHIFT) & 0x1u;
    case 10: return (policy >> NEMU_FPGA_CACHE_POLICY_STORAGE_SHIFT) & 0x3u;
    case 11: return (capability & NEMU_FPGA_CACHE_INSTRUCTION_BUFFER_ENABLED) != 0;
    case 12: return read32(NEMU_FPGA_CACHE_INSTRUCTION_BUFFER_ENTRIES);
    default: return 0;
  }
}

static bool select_trace_class(uint32_t timing_class) {
  if (!runtime_trace_available || timing_class >= NEMU_FPGA_TRACE_CLASS_COUNT) return false;
  runtime_io->write32(runtime_io->opaque, NEMU_FPGA_TRACE_CLASS_SELECTOR, timing_class);
  return true;
}

static bool select_trace_stage(uint32_t stage) {
  if (!runtime_trace_available || stage >= 5) return false;
  runtime_io->write32(runtime_io->opaque, NEMU_FPGA_TRACE_STAGE_SELECTOR, stage);
  return true;
}

uint32_t npc_get_pipeline_features(void) {
  return runtime_trace_available ? read32(NEMU_FPGA_TRACE_PIPELINE_FEATURES) & 0x7 : 0;
}

uint64_t npc_get_pipeline_stall_count(uint32_t counter) {
  if (!runtime_trace_available || counter >= 5) return 0;
  runtime_io->write32(runtime_io->opaque, NEMU_FPGA_TRACE_STALL_SELECTOR, counter);
  return read64(NEMU_FPGA_TRACE_STALL_LOW, NEMU_FPGA_TRACE_STALL_HIGH);
}

uint64_t npc_get_timing_sample_count(uint32_t timing_class) {
  return select_trace_class(timing_class)
      ? read64(NEMU_FPGA_TRACE_CLASS_COUNT_LOW, NEMU_FPGA_TRACE_CLASS_COUNT_HIGH) : 0;
}

uint64_t npc_get_timing_total_cycles(uint32_t timing_class, uint32_t stage) {
  return select_trace_class(timing_class) && select_trace_stage(stage)
      ? read64(NEMU_FPGA_TRACE_STAGE_TOTAL_LOW, NEMU_FPGA_TRACE_STAGE_TOTAL_HIGH) : 0;
}

uint64_t npc_get_timing_max_total_cycles(uint32_t timing_class) {
  return select_trace_class(timing_class)
      ? read64(NEMU_FPGA_TRACE_CLASS_MAX_LOW, NEMU_FPGA_TRACE_CLASS_MAX_HIGH) : 0;
}

uint64_t npc_get_timing_total_latency(uint32_t timing_class) {
  uint64_t total = 0;
  for (uint32_t stage = 0; stage < NPC_FPGA_TRACE_STAGE_COUNT; stage++)
    total += npc_get_timing_total_cycles(timing_class, stage);
  return total;
}

uint64_t npc_get_timing_last_pc(uint32_t timing_class) {
  if (!select_trace_class(timing_class)) return 0;
  if (runtime_trace_loaded) return runtime_trace_last[timing_class].valid
      ? runtime_trace_last[timing_class].pc : 0;
  return read64(NEMU_FPGA_TRACE_CLASS_LAST_PC_LOW, NEMU_FPGA_TRACE_CLASS_LAST_PC_HIGH);
}

uint32_t npc_get_timing_last_instruction(uint32_t timing_class) {
  if (!select_trace_class(timing_class)) return 0;
  if (runtime_trace_loaded) return runtime_trace_last[timing_class].valid
      ? runtime_trace_last[timing_class].instruction : 0;
  return read32(NEMU_FPGA_TRACE_CLASS_LAST_INSTRUCTION);
}

uint64_t npc_get_timing_last_stage_cycles(uint32_t timing_class, uint32_t stage) {
  if (!select_trace_class(timing_class) || !select_trace_stage(stage)) return 0;
  if (runtime_trace_loaded) return runtime_trace_last[timing_class].valid
      ? runtime_trace_last[timing_class].stage[stage] : 0;
  return read64(NEMU_FPGA_TRACE_CLASS_LAST_STAGE_LOW, NEMU_FPGA_TRACE_CLASS_LAST_STAGE_HIGH);
}

uint64_t npc_get_timing_last_total_latency(uint32_t timing_class) {
  uint64_t total = 0;
  for (uint32_t stage = 0; stage < NPC_FPGA_TRACE_STAGE_COUNT; stage++)
    total += npc_get_timing_last_stage_cycles(timing_class, stage);
  return total;
}

uint32_t npc_get_last_timing_class(void) {
  return runtime_trace_available ? read32(NEMU_FPGA_TRACE_LAST_CLASS) & 0x1f : 0;
}

uint64_t npc_get_last_timing_stage_cycles(uint32_t stage) {
  if (!select_trace_stage(stage)) return 0;
  return read64(NEMU_FPGA_TRACE_CLASS_LAST_STAGE_LOW, NEMU_FPGA_TRACE_CLASS_LAST_STAGE_HIGH);
}

uint64_t npc_get_last_timing_total_cycles(void) {
  return runtime_trace_available
      ? read64(NEMU_FPGA_TRACE_LAST_TOTAL_LOW, NEMU_FPGA_TRACE_LAST_TOTAL_HIGH) : 0;
}

int npc_is_finished(void) { return runtime_finished || runtime_failed; }
bool npc_runtime_has_failed(void) { return runtime_failed; }
bool npc_runtime_has_completed(void) { return runtime_completed && !runtime_failed; }
uint64_t npc_runtime_completion_code(void) { return runtime_completion_code; }
uint64_t npc_runtime_completion_pc(void) { return runtime_completion_pc; }
uint64_t npc_runtime_completion_next_pc(void) { return runtime_completion_next_pc; }
void npc_record_mem_access(void) {}
void npc_display_mem_access(void) { puts("FPGA memory accesses are handled in local DDR/HBM"); }

void npc_cleanup(void) {
  if (runtime_interactive && runtime_started && !runtime_failed && !runtime_completed &&
      !debug_is_halted())
    (void)npc_debug_halt();
  if (runtime_io != NULL)
    (void)nemu_fpga_runtime_hold_reset(runtime_io);
#ifdef CONFIG_FPGA_BACKEND_ZCU102
  nemu_fpga_zcu102_uio_close(&zcu102);
#elif defined(CONFIG_FPGA_BACKEND_U55C)
  nemu_fpga_u55c_xrt_close(&u55c);
#endif
  release_runtime_trace();
}
