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
static uint64_t observed_commits;
static bool runtime_started;
static bool runtime_interactive;
static uint32_t debug_capabilities;
static uint32_t debug_sequence;
static uint32_t debug_timeout_ms = 5000;
#ifdef CONFIG_RV64
static const unsigned runtime_xlen = 64;
#else
static const unsigned runtime_xlen = 32;
#endif
#ifdef CONFIG_FPGA_BACKEND_ZCU102
static struct nemu_fpga_zcu102_uio zcu102;
#endif
#ifdef CONFIG_FPGA_BACKEND_U55C
static struct nemu_fpga_u55c_xrt u55c;
#endif
static struct nemu_fpga_mailbox_io *runtime_io;

extern bool sdb_is_batch_mode(void);
int npc_step_cycle(void);

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

static void select_registers(unsigned gpr, unsigned fpr) {
  runtime_io->write32(runtime_io->opaque, NEMU_FPGA_RT_REGISTER_INDEX,
                      (gpr & 31) | ((fpr & 31) << 8));
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
  for (;;) {
    npc_step_cycle();
    if (runtime_failed) {
      errno = EIO;
      return -1;
    }
    const uint32_t status = read32(NEMU_FPGA_DEBUG_STATUS);
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
int npc_debug_halt(void) { return issue_debug_command(NEMU_FPGA_DEBUG_HALT); }
int npc_debug_resume(void) { return issue_debug_command(NEMU_FPGA_DEBUG_RESUME); }
int npc_debug_step(void) { return issue_debug_command(NEMU_FPGA_DEBUG_STEP); }

uint32_t npc_debug_stop_reason(void) {
  return runtime_interactive ? read32(NEMU_FPGA_DEBUG_STOP_REASON) & 0xf : 0;
}

uint64_t npc_debug_stop_pc(void) {
  return runtime_interactive
      ? read64(NEMU_FPGA_DEBUG_STOP_PC_LOW, NEMU_FPGA_DEBUG_STOP_PC_HIGH)
      : read64(NEMU_FPGA_RT_COMMIT_NEXT_PC_LOW, NEMU_FPGA_RT_COMMIT_NEXT_PC_HIGH);
}

void npc_init(void) {
  nemu_fpga_fallback_summary_reset();
  runtime_interactive = !sdb_is_batch_mode();
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
  const uint64_t max_request_cycles =
      parse_environment_u64("NEMU_FPGA_MAILBOX_MAX_CYCLES", 300000000);
  if (control_size > SIZE_MAX || memory_size > SIZE_MAX || max_request_cycles > UINT32_MAX) {
    fprintf(stderr, "ZCU102 FPGA mapping parameter is too large for this host\n");
    exit(EXIT_FAILURE);
  }
  if (nemu_fpga_zcu102_uio_open(&zcu102, control_device, (size_t)control_size,
                                memory_device, memory_physical, (size_t)memory_size,
                                (uint32_t)max_request_cycles) != 0) {
    fprintf(stderr, "cannot open ZCU102 FPGA runtime: %s\n", strerror(errno));
    exit(EXIT_FAILURE);
  }
  runtime_io = &zcu102.mailbox;
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
  const uint64_t max_request_cycles =
      parse_environment_u64("NEMU_FPGA_MAILBOX_MAX_CYCLES", 300000000);
  if (device_index > UINT_MAX || memory_group > UINT_MAX || memory_size > SIZE_MAX ||
      max_request_cycles > UINT32_MAX) {
    fprintf(stderr, "U55C FPGA runtime parameter is too large for this host\n");
    exit(EXIT_FAILURE);
  }
  if (nemu_fpga_u55c_xrt_open(&u55c, (unsigned)device_index, xclbin, kernel,
                              (unsigned)memory_group, (size_t)memory_size,
                              (uint32_t)max_request_cycles) != 0) {
    fprintf(stderr, "cannot open U55C FPGA runtime: %s\n",
            nemu_fpga_u55c_xrt_error(&u55c));
    exit(EXIT_FAILURE);
  }
  runtime_io = &u55c.mailbox;
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
    if (protocol != NEMU_FPGA_DEBUG_PROTOCOL_V3 ||
        (debug_capabilities & required) != required) {
      fprintf(stderr,
              "interactive FPGA debugging requires a v3 bitstream/xclbin; rebuild the selected FPGA artifact\n");
      exit(EXIT_FAILURE);
    }
    debug_sequence = read32(NEMU_FPGA_DEBUG_COMPLETED_SEQUENCE);
  }
}

void npc_load_image(const void *image, size_t image_size) {
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
      nemu_fpga_runtime_service_once(runtime_io, runtime_xlen);
  if (event.type == NEMU_FPGA_RT_EVENT_PUTCH) {
    putchar((int)(event.value & 0xff));
    fflush(stdout);
  } else if (event.type == NEMU_FPGA_RT_EVENT_HALT) {
    runtime_finished = true;
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
  return npc_debug_is_halted() ? npc_debug_stop_pc()
                               : read64(NEMU_FPGA_RT_COMMIT_NEXT_PC_LOW,
                                        NEMU_FPGA_RT_COMMIT_NEXT_PC_HIGH);
}

uint32_t npc_get_inst(void) {
  return read32(NEMU_FPGA_RT_COMMIT_INSTRUCTION);
}

uint64_t npc_get_last_commit_pc(void) {
  return read64(NEMU_FPGA_RT_COMMIT_PC_LOW, NEMU_FPGA_RT_COMMIT_PC_HIGH);
}

uint64_t npc_get_current_pc(void) {
  return npc_debug_is_halted() ? npc_debug_stop_pc()
                               : read64(NEMU_FPGA_RT_CURRENT_PC_LOW,
                                        NEMU_FPGA_RT_CURRENT_PC_HIGH);
}

uint32_t npc_get_frontend_instruction(void) {
  return read32(NEMU_FPGA_RT_FRONTEND_INSTRUCTION);
}

uint64_t npc_get_reg(int index) {
  if (index < 0 || index >= 32) return 0;
  select_registers((unsigned)index, 0);
  return read64(NEMU_FPGA_RT_GPR_LOW, NEMU_FPGA_RT_GPR_HIGH);
}

uint64_t npc_get_freg(int index) {
  if (index < 0 || index >= 32) return 0;
  select_registers(0, (unsigned)index);
  return read64(NEMU_FPGA_RT_FPR_LOW, NEMU_FPGA_RT_FPR_HIGH);
}

uint32_t npc_get_fcsr(void) {
  return read32(NEMU_FPGA_RT_FCSR) & 0xff;
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
  return read64(NEMU_FPGA_RT_CYCLE_LOW, NEMU_FPGA_RT_CYCLE_HIGH);
}

uint64_t npc_get_commit_count(void) {
  return read64(NEMU_FPGA_RT_COMMIT_COUNT_LOW, NEMU_FPGA_RT_COMMIT_COUNT_HIGH);
}

uint32_t npc_get_backpressure_reasons(void) {
  return read32(NEMU_FPGA_RT_BACKPRESSURE) & 0x1ff;
}

uint32_t npc_get_pipeline_features(void) { return 7; }
uint64_t npc_get_pipeline_stall_count(uint32_t counter) { (void)counter; return 0; }
uint64_t npc_get_timing_sample_count(uint32_t timing_class) { (void)timing_class; return 0; }
uint64_t npc_get_timing_total_cycles(uint32_t timing_class, uint32_t stage) {
  (void)timing_class; (void)stage; return 0;
}
uint64_t npc_get_timing_max_total_cycles(uint32_t timing_class) { (void)timing_class; return 0; }
uint64_t npc_get_timing_last_pc(uint32_t timing_class) { (void)timing_class; return 0; }
uint32_t npc_get_timing_last_instruction(uint32_t timing_class) { (void)timing_class; return 0; }
uint64_t npc_get_timing_last_stage_cycles(uint32_t timing_class, uint32_t stage) {
  (void)timing_class; (void)stage; return 0;
}
uint32_t npc_get_last_timing_class(void) { return 0; }
uint64_t npc_get_last_timing_stage_cycles(uint32_t stage) { (void)stage; return 0; }
uint64_t npc_get_last_timing_total_cycles(void) { return 0; }

int npc_is_finished(void) { return runtime_finished || runtime_failed; }
void npc_record_mem_access(void) {}
void npc_display_mem_access(void) { puts("FPGA memory accesses are handled in local DDR/HBM"); }

void npc_cleanup(void) {
  if (runtime_interactive && runtime_started && !runtime_failed && !debug_is_halted())
    (void)npc_debug_halt();
  nemu_fpga_fallback_summary_print();
#ifdef CONFIG_FPGA_BACKEND_ZCU102
  nemu_fpga_zcu102_uio_close(&zcu102);
#elif defined(CONFIG_FPGA_BACKEND_U55C)
  nemu_fpga_u55c_xrt_close(&u55c);
#endif
}
