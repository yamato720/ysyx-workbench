#ifndef NEMU_FPGA_MAILBOX_H
#define NEMU_FPGA_MAILBOX_H

#include <stdbool.h>
#include <stdint.h>

enum nemu_fpga_floating_operation {
  NEMU_FPGA_FADD = 0,
  NEMU_FPGA_FSUB,
  NEMU_FPGA_FMUL,
  NEMU_FPGA_FDIV,
  NEMU_FPGA_FSQRT,
  NEMU_FPGA_FMADD,
  NEMU_FPGA_FMSUB,
  NEMU_FPGA_FNMSUB,
  NEMU_FPGA_FNMADD,
  NEMU_FPGA_FSGNJ,
  NEMU_FPGA_FSGNJN,
  NEMU_FPGA_FSGNJX,
  NEMU_FPGA_FMIN,
  NEMU_FPGA_FMAX,
  NEMU_FPGA_FEQ,
  NEMU_FPGA_FLT,
  NEMU_FPGA_FLE,
  NEMU_FPGA_FCVT_W,
  NEMU_FPGA_FCVT_WU,
  NEMU_FPGA_FCVT_L,
  NEMU_FPGA_FCVT_LU,
  NEMU_FPGA_FCVT_S_W,
  NEMU_FPGA_FCVT_S_WU,
  NEMU_FPGA_FCVT_S_L,
  NEMU_FPGA_FCVT_S_LU,
  NEMU_FPGA_FMV_X_W,
  NEMU_FPGA_FCLASS,
  NEMU_FPGA_FMV_W_X,
};

enum nemu_fpga_mailbox_register {
  NEMU_FPGA_MB_STATUS = 0x00,
  NEMU_FPGA_MB_REQUEST_SEQUENCE = 0x04,
  NEMU_FPGA_MB_REQUEST_PC_LOW = 0x08,
  NEMU_FPGA_MB_REQUEST_PC_HIGH = 0x0c,
  NEMU_FPGA_MB_REQUEST_INSTRUCTION = 0x10,
  NEMU_FPGA_MB_REQUEST_OPERATION_RM = 0x14,
  NEMU_FPGA_MB_REQUEST_FCSR = 0x18,
  NEMU_FPGA_MB_REQUEST_A_LOW = 0x20,
  NEMU_FPGA_MB_REQUEST_A_HIGH = 0x24,
  NEMU_FPGA_MB_REQUEST_B_LOW = 0x28,
  NEMU_FPGA_MB_REQUEST_B_HIGH = 0x2c,
  NEMU_FPGA_MB_REQUEST_C_LOW = 0x30,
  NEMU_FPGA_MB_REQUEST_C_HIGH = 0x34,
  NEMU_FPGA_MB_TIMEOUT_CYCLES = 0x38,
  NEMU_FPGA_DEBUG_CAPABILITIES = 0x3c,
  NEMU_FPGA_DEBUG_COMMAND_SEQUENCE = 0x40,
  NEMU_FPGA_DEBUG_COMMAND = 0x44,
  NEMU_FPGA_DEBUG_COMPLETED_SEQUENCE = 0x48,
  NEMU_FPGA_DEBUG_STATUS = 0x4c,
  NEMU_FPGA_DEBUG_STOP_PC_LOW = 0x50,
  NEMU_FPGA_DEBUG_STOP_PC_HIGH = 0x54,
  NEMU_FPGA_DEBUG_STOP_REASON = 0x58,
  NEMU_FPGA_DEBUG_CSR_INDEX = 0x5c,
  NEMU_FPGA_MB_RESPONSE_SEQUENCE = 0x60,
  NEMU_FPGA_MB_RESPONSE_RESULT_LOW = 0x64,
  NEMU_FPGA_MB_RESPONSE_RESULT_HIGH = 0x68,
  NEMU_FPGA_MB_RESPONSE_FLAGS = 0x6c,
  NEMU_FPGA_MB_RESPONSE_COMMIT = 0x70,
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

enum nemu_fpga_mailbox_status_bits {
  NEMU_FPGA_MB_REQUEST_PENDING = 1u << 0,
  NEMU_FPGA_MB_RESPONSE_PENDING = 1u << 1,
  NEMU_FPGA_MB_CORE_BUSY = 1u << 2,
  NEMU_FPGA_MB_PROTOCOL_ERROR = 1u << 3,
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

#define NEMU_FPGA_DEBUG_PROTOCOL_V2 UINT32_C(0x4e504302)

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

struct nemu_fpga_fallback_request {
  uint32_t sequence;
  uint64_t pc;
  uint32_t instruction;
  uint64_t operand_a;
  uint64_t operand_b;
  uint64_t operand_c;
  uint8_t fcsr;
  uint8_t operation;
  uint8_t rounding_mode;
};

struct nemu_fpga_fallback_response {
  uint32_t sequence;
  uint64_t result;
  uint8_t exception_flags;
  bool illegal;
};

struct nemu_fpga_mailbox_io {
  void *opaque;
  uint32_t (*read32)(void *opaque, uint32_t offset);
  void (*write32)(void *opaque, uint32_t offset, uint32_t value);
  uint32_t max_request_cycles;
};

enum nemu_fpga_mailbox_service_result {
  NEMU_FPGA_MB_IDLE = 0,
  NEMU_FPGA_MB_RESPONDED = 1,
  NEMU_FPGA_MB_STALE = 2,
  NEMU_FPGA_MB_TIMEOUT = -1,
  NEMU_FPGA_MB_IO_ERROR = -2,
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

void nemu_fpga_fallback_execute(const struct nemu_fpga_fallback_request *request,
                                unsigned xlen,
                                struct nemu_fpga_fallback_response *response);

enum nemu_fpga_mailbox_service_result
nemu_fpga_mailbox_service_once(const struct nemu_fpga_mailbox_io *io, unsigned xlen);

int nemu_fpga_runtime_hold_reset(const struct nemu_fpga_mailbox_io *io);
int nemu_fpga_runtime_start(const struct nemu_fpga_mailbox_io *io);
int nemu_fpga_runtime_start_halted(const struct nemu_fpga_mailbox_io *io);
struct nemu_fpga_runtime_event
nemu_fpga_runtime_service_once(const struct nemu_fpga_mailbox_io *io, unsigned xlen);
uint64_t nemu_fpga_runtime_read_counter(const struct nemu_fpga_mailbox_io *io,
                                        uint32_t low_offset, uint32_t high_offset);

#endif
