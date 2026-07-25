#ifndef NEMU_FPGA_MAILBOX_H
#define NEMU_FPGA_MAILBOX_H

#include <stddef.h>
#include <stdint.h>

enum nemu_fpga_runtime_register {
  NEMU_FPGA_DEBUG_CAPABILITIES = 0x3c,
  NEMU_FPGA_DEBUG_COMMAND_SEQUENCE = 0x40,
  NEMU_FPGA_DEBUG_COMMAND = 0x44,
  NEMU_FPGA_DEBUG_COMPLETED_SEQUENCE = 0x48,
  NEMU_FPGA_DEBUG_STATUS = 0x4c,
  NEMU_FPGA_DEBUG_STOP_PC_LOW = 0x50,
  NEMU_FPGA_DEBUG_STOP_PC_HIGH = 0x54,
  NEMU_FPGA_DEBUG_STOP_REASON = 0x58,
  NEMU_FPGA_DEBUG_CSR_INDEX = 0x5c,
  NEMU_FPGA_RT_CONTROL = 0x80,
  NEMU_FPGA_RT_STATUS = 0x84,
  NEMU_FPGA_RT_INFO = 0x88,
  NEMU_FPGA_RT_REGISTER_INDEX = 0x8c,
  NEMU_FPGA_RT_GPR_LOW = 0x90,
  NEMU_FPGA_RT_GPR_HIGH = 0x94,
  NEMU_FPGA_RT_FPR_LOW = 0x98,
  NEMU_FPGA_RT_FPR_HIGH = 0x9c,
  NEMU_FPGA_RT_FCSR = 0xa0,
  NEMU_FPGA_RT_MSTATUS_LOW = 0xa4,
  NEMU_FPGA_RT_MSTATUS_HIGH = 0xa8,
  NEMU_FPGA_RT_CURRENT_PC_LOW = 0xac,
  NEMU_FPGA_RT_CURRENT_PC_HIGH = 0xb0,
  NEMU_FPGA_RT_COMMIT_PC_LOW = 0xb4,
  NEMU_FPGA_RT_COMMIT_PC_HIGH = 0xb8,
  NEMU_FPGA_RT_COMMIT_INSTRUCTION = 0xbc,
  NEMU_FPGA_RT_COMMIT_NEXT_PC_LOW = 0xc0,
  NEMU_FPGA_RT_COMMIT_NEXT_PC_HIGH = 0xc4,
  NEMU_FPGA_RT_CYCLE_LOW = 0xc8,
  NEMU_FPGA_RT_CYCLE_HIGH = 0xcc,
  NEMU_FPGA_RT_COMMIT_COUNT_LOW = 0xd0,
  NEMU_FPGA_RT_COMMIT_COUNT_HIGH = 0xd4,
  NEMU_FPGA_RT_HALT_CODE_LOW = 0xd8,
  NEMU_FPGA_RT_HALT_CODE_HIGH = 0xdc,
  NEMU_FPGA_RT_BACKPRESSURE = 0xe0,
  NEMU_FPGA_RT_FRONTEND_INSTRUCTION = 0xe4,
  NEMU_FPGA_RT_PUTCH_DATA = 0xe8,
  NEMU_FPGA_DEBUG_CSR_LOW = 0xec,
  NEMU_FPGA_RT_MEMORY_HOST_BASE_LOW = 0xf0,
  NEMU_FPGA_RT_MEMORY_HOST_BASE_HIGH = 0xf4,
  NEMU_FPGA_DEBUG_CSR_HIGH = 0xf8,
  NEMU_FPGA_DEBUG_PROTOCOL = 0xfc,
};

enum nemu_fpga_runtime_control_bits {
  NEMU_FPGA_RT_CORE_RESET = 1u << 0,
  NEMU_FPGA_RT_CLEAR_HALT = 1u << 1,
  NEMU_FPGA_RT_ACK_PUTCH = 1u << 2,
};

enum nemu_fpga_runtime_status_bits {
  NEMU_FPGA_RT_RUNNING = 1u << 0,
  NEMU_FPGA_RT_HALTED = 1u << 1,
  NEMU_FPGA_RT_PUTCH_PENDING = 1u << 2,
  NEMU_FPGA_RT_PROTOCOL_ERROR = 1u << 3,
};

#define NEMU_FPGA_DEBUG_PROTOCOL_V5 UINT32_C(0x4e504305)

enum nemu_fpga_debug_capability_bits {
  NEMU_FPGA_DEBUG_CAP_HALT_STEP = 1u << 0,
  NEMU_FPGA_DEBUG_CAP_TARGET_MEMORY = 1u << 1,
  NEMU_FPGA_DEBUG_CAP_CSR_SNAPSHOT = 1u << 2,
};

enum nemu_fpga_debug_command {
  NEMU_FPGA_DEBUG_HALT = 1,
  NEMU_FPGA_DEBUG_RESUME = 2,
  NEMU_FPGA_DEBUG_STEP = 3,
};

enum nemu_fpga_debug_status_bits {
  NEMU_FPGA_DEBUG_RUNNING = 1u << 0,
  NEMU_FPGA_DEBUG_HALTED = 1u << 1,
  NEMU_FPGA_DEBUG_HALTING = 1u << 2,
  NEMU_FPGA_DEBUG_STEPPING = 1u << 3,
  NEMU_FPGA_DEBUG_IN_RESET = 1u << 4,
  NEMU_FPGA_DEBUG_ERROR = 1u << 5,
};

enum nemu_fpga_debug_stop_reason {
  NEMU_FPGA_STOP_NONE = 0,
  NEMU_FPGA_STOP_HALT_REQUEST = 1,
  NEMU_FPGA_STOP_STEP = 2,
  NEMU_FPGA_STOP_EBREAK = 3,
};

enum nemu_fpga_debug_csr {
  NEMU_FPGA_CSR_MSTATUS = 0,
  NEMU_FPGA_CSR_MCAUSE = 1,
  NEMU_FPGA_CSR_MEPC = 2,
  NEMU_FPGA_CSR_MTVEC = 3,
  NEMU_FPGA_CSR_FCSR = 4,
  NEMU_FPGA_CSR_PC = 5,
};

struct nemu_fpga_mailbox_io {
  void *opaque;
  uint32_t (*read32)(void *opaque, uint32_t offset);
  void (*write32)(void *opaque, uint32_t offset, uint32_t value);
};

enum nemu_fpga_runtime_event_type {
  NEMU_FPGA_RT_EVENT_NONE = 0,
  NEMU_FPGA_RT_EVENT_PUTCH,
  NEMU_FPGA_RT_EVENT_HALT,
  NEMU_FPGA_RT_EVENT_ERROR,
};

struct nemu_fpga_runtime_event {
  enum nemu_fpga_runtime_event_type type;
  uint64_t value;
  int error;
};

int nemu_fpga_runtime_hold_reset(const struct nemu_fpga_mailbox_io *io);
int nemu_fpga_runtime_start(const struct nemu_fpga_mailbox_io *io);
int nemu_fpga_runtime_start_halted(const struct nemu_fpga_mailbox_io *io);
struct nemu_fpga_runtime_event
nemu_fpga_runtime_service_once(const struct nemu_fpga_mailbox_io *io);
uint64_t nemu_fpga_runtime_read_counter(const struct nemu_fpga_mailbox_io *io,
                                        uint32_t low_offset, uint32_t high_offset);

#endif
