#include "fpga-mailbox.h"
#include "fpga-zcu102-uio.h"

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "softfloat.h"

#define ARRAY_SIZE(array) (sizeof(array) / sizeof((array)[0]))

static void fail(const char *message) {
  fprintf(stderr, "fpga-mailbox-test: %s\n", message);
  exit(1);
}

static uint32_t instruction_with_rm(unsigned rm) { return (rm & 7) << 12; }

static struct nemu_fpga_fallback_response execute(uint8_t operation, unsigned rm,
                                                   uint8_t fcsr, uint64_t a,
                                                   uint64_t b, uint64_t c,
                                                   unsigned xlen) {
  const struct nemu_fpga_fallback_request request = {
    .sequence = 17,
    .instruction = instruction_with_rm(rm),
    .operand_a = a,
    .operand_b = b,
    .operand_c = c,
    .fcsr = fcsr,
    .operation = operation,
    .rounding_mode = rm == 7 ? (fcsr >> 5) & 7 : rm,
    .domain = NEMU_FPGA_DOMAIN_FLOATING,
    .fallback_reason = NEMU_FPGA_FALLBACK_FPO_RISCV_INCOMPATIBLE,
  };
  struct nemu_fpga_fallback_response response;
  nemu_fpga_fallback_execute(&request, xlen, &response);
  return response;
}

static struct nemu_fpga_fallback_response execute_integer(uint8_t operation,
                                                           uint64_t a, uint64_t b,
                                                           unsigned xlen) {
  const struct nemu_fpga_fallback_request request = {
    .sequence = 17,
    .operand_a = a,
    .operand_b = b,
    .operation = operation,
    .domain = NEMU_FPGA_DOMAIN_INTEGER,
    .fallback_reason = NEMU_FPGA_FALLBACK_VENDOR_IP_UNAVAILABLE,
  };
  struct nemu_fpga_fallback_response response;
  nemu_fpga_fallback_execute(&request, xlen, &response);
  return response;
}

static void expect_response(const char *name, struct nemu_fpga_fallback_response response,
                            uint64_t result, unsigned flags, bool illegal) {
  if (response.sequence != 17 || response.result != result ||
      response.exception_flags != flags || response.illegal != illegal) {
    fprintf(stderr,
            "%s: seq=%u result=%016llx flags=%02x illegal=%u; "
            "expected result=%016llx flags=%02x illegal=%u\n",
            name, response.sequence, (unsigned long long)response.result,
            response.exception_flags, response.illegal,
            (unsigned long long)result, flags, illegal);
    exit(1);
  }
}

struct mock_mailbox {
  uint32_t registers[0x100 / 4];
  unsigned sequence_reads;
  unsigned status_reads;
  bool change_sequence;
  bool reset_request;
};

static uint32_t mock_read32(void *opaque, uint32_t offset) {
  struct mock_mailbox *mock = opaque;
  if (offset / 4 >= ARRAY_SIZE(mock->registers)) fail("mock read outside register map");
  if (offset == NEMU_FPGA_MB_REQUEST_SEQUENCE) {
    mock->sequence_reads++;
    if (mock->change_sequence && mock->sequence_reads == 2) {
      mock->registers[offset / 4]++;
    }
  }
  if (offset == NEMU_FPGA_MB_STATUS) {
    mock->status_reads++;
    if (mock->reset_request && mock->status_reads == 2) mock->registers[offset / 4] = 0;
  }
  return mock->registers[offset / 4];
}

static void mock_write32(void *opaque, uint32_t offset, uint32_t value) {
  struct mock_mailbox *mock = opaque;
  if (offset / 4 >= ARRAY_SIZE(mock->registers)) fail("mock write outside register map");
  mock->registers[offset / 4] = value;
  if (offset == NEMU_FPGA_MB_RESPONSE_COMMIT && (value & 1)) {
    if (mock->registers[NEMU_FPGA_MB_RESPONSE_SEQUENCE / 4] ==
        mock->registers[NEMU_FPGA_MB_REQUEST_SEQUENCE / 4]) {
      mock->registers[NEMU_FPGA_MB_STATUS / 4] |= NEMU_FPGA_MB_RESPONSE_PENDING;
    } else {
      mock->registers[NEMU_FPGA_MB_STATUS / 4] |= NEMU_FPGA_MB_PROTOCOL_ERROR;
    }
  }
  if (offset == NEMU_FPGA_RT_CONTROL) {
    mock->registers[offset / 4] = value & NEMU_FPGA_RT_CORE_RESET;
    if (value & NEMU_FPGA_RT_CORE_RESET) {
      mock->registers[NEMU_FPGA_RT_STATUS / 4] &= ~NEMU_FPGA_RT_RUNNING;
    } else {
      mock->registers[NEMU_FPGA_RT_STATUS / 4] |= NEMU_FPGA_RT_RUNNING;
    }
    if (value & NEMU_FPGA_RT_CLEAR_HALT) {
      mock->registers[NEMU_FPGA_RT_STATUS / 4] &= ~NEMU_FPGA_RT_HALTED;
    }
    if (value & NEMU_FPGA_RT_ACK_PUTCH) {
      mock->registers[NEMU_FPGA_RT_STATUS / 4] &= ~NEMU_FPGA_RT_PUTCH_PENDING;
    }
  }
}

static struct mock_mailbox pending_add_request(void) {
  struct mock_mailbox mock = {0};
  mock.registers[NEMU_FPGA_MB_STATUS / 4] =
      NEMU_FPGA_MB_REQUEST_PENDING | NEMU_FPGA_MB_CORE_BUSY;
  mock.registers[NEMU_FPGA_MB_REQUEST_SEQUENCE / 4] = 41;
  mock.registers[NEMU_FPGA_MB_REQUEST_INSTRUCTION / 4] = instruction_with_rm(0);
  mock.registers[NEMU_FPGA_MB_REQUEST_OPERATION_RM / 4] = NEMU_FPGA_FADD;
  mock.registers[NEMU_FPGA_MB_REQUEST_DOMAIN_REASON / 4] =
      NEMU_FPGA_DOMAIN_FLOATING | (NEMU_FPGA_FALLBACK_FPO_RISCV_INCOMPATIBLE << 1);
  mock.registers[NEMU_FPGA_MB_REQUEST_A_LOW / 4] = 0x3f800000;
  mock.registers[NEMU_FPGA_MB_REQUEST_B_LOW / 4] = 0x40000000;
  return mock;
}

static struct nemu_fpga_mailbox_io mock_io(struct mock_mailbox *mock) {
  return (struct nemu_fpga_mailbox_io) {
    .opaque = mock,
    .read32 = mock_read32,
    .write32 = mock_write32,
    .max_request_cycles = 1000,
  };
}

static void test_executor(void) {
  softfloat_roundingMode = softfloat_round_max;
  softfloat_detectTininess = softfloat_tininess_beforeRounding;
  softfloat_exceptionFlags = 0x12;
  expect_response("fadd", execute(NEMU_FPGA_FADD, 0, 0,
                                  0x3f800000, 0x40000000, 0, 32),
                  0x40400000, 0, false);
  expect_response("divide-by-zero", execute(NEMU_FPGA_FDIV, 0, 0,
                                            0x3f800000, 0, 0, 32),
                  0x7f800000, 0x08, false);
  expect_response("dynamic-rmm", execute(NEMU_FPGA_FADD, 7, 4 << 5,
                                         0x3f800000, 0x33800000, 0, 32),
                  0x3f800001, 0x01, false);
  expect_response("illegal-rm", execute(NEMU_FPGA_FADD, 5, 0,
                                        0x3f800000, 0x40000000, 0, 32),
                  0, 0, true);
  expect_response("rv64-nan-box", execute(NEMU_FPGA_FMUL, 0, 0,
                                          UINT64_C(0xffffffff3f800000),
                                          UINT64_C(0xffffffff40000000), 0, 64),
                  UINT64_C(0xffffffff40000000), 0, false);
  expect_response("rv64-unboxed-source", execute(NEMU_FPGA_FADD, 0, 0,
                                                 0x3f800000,
                                                 UINT64_C(0xffffffff3f800000), 0, 64),
                  UINT64_C(0xffffffff7fc00000), 0, false);
  expect_response("fcvt-wu-sign-extension", execute(NEMU_FPGA_FCVT_WU, 0, 0,
                                                    UINT64_C(0xffffffff4f000000), 0, 0, 64),
                  UINT64_C(0xffffffff80000000), 0, false);
  expect_response("rv32-reject-long", execute(NEMU_FPGA_FCVT_L, 0, 0,
                                              0x3f800000, 0, 0, 32),
                  0, 0, true);
  if (softfloat_roundingMode != softfloat_round_max ||
      softfloat_detectTininess != softfloat_tininess_beforeRounding ||
      softfloat_exceptionFlags != 0x12) {
    fail("executor leaked SoftFloat global state");
  }

  struct nemu_fpga_fallback_request inconsistent = {
    .sequence = 17,
    .instruction = instruction_with_rm(7),
    .operand_a = 0x3f800000,
    .operand_b = 0x40000000,
    .fcsr = 4 << 5,
    .operation = NEMU_FPGA_FADD,
    .rounding_mode = 0,
    .domain = NEMU_FPGA_DOMAIN_FLOATING,
    .fallback_reason = NEMU_FPGA_FALLBACK_FPO_RISCV_INCOMPATIBLE,
  };
  struct nemu_fpga_fallback_response response;
  nemu_fpga_fallback_execute(&inconsistent, 32, &response);
  expect_response("inconsistent-rm-metadata", response, 0, 0, true);
}

static void test_integer_executor(void) {
  expect_response("rv32-mul", execute_integer(NEMU_FPGA_MUL, 6, 7, 32), 42, 0, false);
  expect_response("rv32-mulh", execute_integer(NEMU_FPGA_MULH, UINT32_MAX, 1, 32),
                  UINT32_MAX, 0, false);
  expect_response("rv32-mulhsu", execute_integer(NEMU_FPGA_MULHSU, UINT32_MAX, 2, 32),
                  UINT32_MAX, 0, false);
  expect_response("rv32-mulhu", execute_integer(NEMU_FPGA_MULHU, UINT32_MAX, 2, 32),
                  1, 0, false);
  expect_response("rv32-div", execute_integer(NEMU_FPGA_DIV, UINT32_C(0xfffffff7), 2, 32),
                  UINT32_C(0xfffffffc), 0, false);
  expect_response("rv32-divu", execute_integer(NEMU_FPGA_DIVU, 9, 2, 32), 4, 0, false);
  expect_response("rv32-rem", execute_integer(NEMU_FPGA_REM, UINT32_C(0xfffffff7), 2, 32),
                  UINT32_MAX, 0, false);
  expect_response("rv32-remu", execute_integer(NEMU_FPGA_REMU, 9, 2, 32), 1, 0, false);
  expect_response("rv32-div-overflow",
                  execute_integer(NEMU_FPGA_DIV, UINT32_C(0x80000000), UINT32_MAX, 32),
                  UINT32_C(0x80000000), 0, false);
  expect_response("rv32-div-zero", execute_integer(NEMU_FPGA_DIV, 5, 0, 32),
                  UINT32_MAX, 0, false);
  expect_response("rv32-rem-zero", execute_integer(NEMU_FPGA_REM, 5, 0, 32), 5, 0, false);

  expect_response("rv64-mul", execute_integer(NEMU_FPGA_MUL, 6, 7, 64), 42, 0, false);
  expect_response("rv64-mulh", execute_integer(NEMU_FPGA_MULH, UINT64_MAX, 1, 64),
                  UINT64_MAX, 0, false);
  expect_response("rv64-mulhsu", execute_integer(NEMU_FPGA_MULHSU, UINT64_MAX, 2, 64),
                  UINT64_MAX, 0, false);
  expect_response("rv64-mulhu", execute_integer(NEMU_FPGA_MULHU, UINT64_MAX, 2, 64),
                  1, 0, false);
  expect_response("rv64-div", execute_integer(NEMU_FPGA_DIV, UINT64_C(0xfffffffffffffff7), 2, 64),
                  UINT64_C(0xfffffffffffffffc), 0, false);
  expect_response("rv64-divu", execute_integer(NEMU_FPGA_DIVU, 9, 2, 64), 4, 0, false);
  expect_response("rv64-rem", execute_integer(NEMU_FPGA_REM, UINT64_C(0xfffffffffffffff7), 2, 64),
                  UINT64_MAX, 0, false);
  expect_response("rv64-remu", execute_integer(NEMU_FPGA_REMU, 9, 2, 64), 1, 0, false);
  expect_response("rv64-div-overflow",
                  execute_integer(NEMU_FPGA_DIV, UINT64_C(0x8000000000000000), UINT64_MAX, 64),
                  UINT64_C(0x8000000000000000), 0, false);
  expect_response("rv64-div-zero", execute_integer(NEMU_FPGA_DIV, 5, 0, 64),
                  UINT64_MAX, 0, false);
  expect_response("rv64-rem-zero", execute_integer(NEMU_FPGA_REM, 5, 0, 64), 5, 0, false);

  expect_response("rv64-mulw", execute_integer(NEMU_FPGA_MULW, UINT32_C(0xfffffffd), 4, 64),
                  UINT64_C(0xfffffffffffffff4), 0, false);
  expect_response("rv64-divw", execute_integer(NEMU_FPGA_DIVW, UINT32_C(0xfffffff7), 2, 64),
                  UINT64_C(0xfffffffffffffffc), 0, false);
  expect_response("rv64-divuw", execute_integer(NEMU_FPGA_DIVUW, 9, 2, 64), 4, 0, false);
  expect_response("rv64-remw", execute_integer(NEMU_FPGA_REMW, UINT32_C(0xfffffff7), 2, 64),
                  UINT64_MAX, 0, false);
  expect_response("rv64-remuw", execute_integer(NEMU_FPGA_REMUW, 9, 2, 64), 1, 0, false);
  expect_response("rv64-divw-overflow",
                  execute_integer(NEMU_FPGA_DIVW, UINT32_C(0x80000000), UINT32_MAX, 64),
                  UINT64_C(0xffffffff80000000), 0, false);
  expect_response("rv64-divuw-zero", execute_integer(NEMU_FPGA_DIVUW, 5, 0, 64),
                  UINT64_MAX, 0, false);
}

static void test_floating_operation_coverage(void) {
  const uint64_t one = UINT64_C(0xffffffff3f800000);
  const uint64_t two = UINT64_C(0xffffffff40000000);
  const uint64_t three = UINT64_C(0xffffffff40400000);
  const uint64_t four = UINT64_C(0xffffffff40800000);

  expect_response("fsub", execute(NEMU_FPGA_FSUB, 0, 0, two, one, 0, 64), one, 0, false);
  expect_response("fmul", execute(NEMU_FPGA_FMUL, 0, 0, two, three, 0, 64),
                  UINT64_C(0xffffffff40c00000), 0, false);
  expect_response("fdiv", execute(NEMU_FPGA_FDIV, 0, 0, three, two, 0, 64),
                  UINT64_C(0xffffffff3fc00000), 0, false);
  expect_response("fsqrt", execute(NEMU_FPGA_FSQRT, 0, 0, four, 0, 0, 64), two, 0, false);
  expect_response("fmadd", execute(NEMU_FPGA_FMADD, 0, 0, two, three, four, 64),
                  UINT64_C(0xffffffff41200000), 0, false);
  expect_response("fmsub", execute(NEMU_FPGA_FMSUB, 0, 0, two, three, four, 64), two, 0, false);
  expect_response("fnmsub", execute(NEMU_FPGA_FNMSUB, 0, 0, two, three, four, 64),
                  UINT64_C(0xffffffffc0000000), 0, false);
  expect_response("fnmadd", execute(NEMU_FPGA_FNMADD, 0, 0, two, three, four, 64),
                  UINT64_C(0xffffffffc1200000), 0, false);

  expect_response("fsgnj", execute(NEMU_FPGA_FSGNJ, 0, 0, one, UINT64_C(0xffffffffc0000000), 0, 64),
                  UINT64_C(0xffffffffbf800000), 0, false);
  expect_response("fsgnjn", execute(NEMU_FPGA_FSGNJN, 0, 0, one, UINT64_C(0xffffffffc0000000), 0, 64),
                  one, 0, false);
  expect_response("fsgnjx", execute(NEMU_FPGA_FSGNJX, 0, 0, one, UINT64_C(0xffffffffc0000000), 0, 64),
                  UINT64_C(0xffffffffbf800000), 0, false);
  expect_response("fmin", execute(NEMU_FPGA_FMIN, 0, 0, one, UINT64_C(0xffffffffc0000000), 0, 64),
                  UINT64_C(0xffffffffc0000000), 0, false);
  expect_response("fmax", execute(NEMU_FPGA_FMAX, 0, 0, one, UINT64_C(0xffffffffc0000000), 0, 64),
                  one, 0, false);
  expect_response("feq", execute(NEMU_FPGA_FEQ, 0, 0, one, one, 0, 64), 1, 0, false);
  expect_response("flt", execute(NEMU_FPGA_FLT, 0, 0, one, two, 0, 64), 1, 0, false);
  expect_response("fle", execute(NEMU_FPGA_FLE, 0, 0, one, one, 0, 64), 1, 0, false);
  expect_response("fmv-x-w", execute(NEMU_FPGA_FMV_X_W, 0, 0,
                                       UINT64_C(0xffffffff80000001), 0, 0, 64),
                  UINT64_C(0xffffffff80000001), 0, false);
  expect_response("fclass", execute(NEMU_FPGA_FCLASS, 0, 0, one, 0, 0, 64), 1u << 6, 0, false);
  expect_response("fmv-w-x", execute(NEMU_FPGA_FMV_W_X, 0, 0, 1, 0, 0, 64),
                  UINT64_C(0xffffffff00000001), 0, false);

  expect_response("fcvt-w", execute(NEMU_FPGA_FCVT_W, 0, 0, UINT64_C(0xffffffff3fc00000), 0, 0, 64),
                  2, 1, false);
  expect_response("fcvt-wu", execute(NEMU_FPGA_FCVT_WU, 0, 0, UINT64_C(0xffffffff3fc00000), 0, 0, 64),
                  2, 1, false);
  expect_response("fcvt-l", execute(NEMU_FPGA_FCVT_L, 0, 0, UINT64_C(0xffffffff3fc00000), 0, 0, 64),
                  2, 1, false);
  expect_response("fcvt-lu", execute(NEMU_FPGA_FCVT_LU, 0, 0, UINT64_C(0xffffffff3fc00000), 0, 0, 64),
                  2, 1, false);
  expect_response("fcvt-s-w", execute(NEMU_FPGA_FCVT_S_W, 0, 0, 1, 0, 0, 64), one, 0, false);
  expect_response("fcvt-s-wu", execute(NEMU_FPGA_FCVT_S_WU, 0, 0, 1, 0, 0, 64), one, 0, false);
  expect_response("fcvt-s-l", execute(NEMU_FPGA_FCVT_S_L, 0, 0, 1, 0, 0, 64), one, 0, false);
  expect_response("fcvt-s-lu", execute(NEMU_FPGA_FCVT_S_LU, 0, 0, 1, 0, 0, 64), one, 0, false);
}

static void test_mailbox_service(void) {
  struct mock_mailbox mock = pending_add_request();
  struct nemu_fpga_mailbox_io io = mock_io(&mock);
  if (nemu_fpga_mailbox_service_once(&io, 32) != NEMU_FPGA_MB_RESPONDED) {
    fail("valid request was not serviced");
  }
  if (mock.registers[NEMU_FPGA_MB_RESPONSE_SEQUENCE / 4] != 41 ||
      mock.registers[NEMU_FPGA_MB_RESPONSE_RESULT_LOW / 4] != 0x40400000 ||
      mock.registers[NEMU_FPGA_MB_RESPONSE_DOMAIN_REASON / 4] !=
          (NEMU_FPGA_DOMAIN_FLOATING | (NEMU_FPGA_FALLBACK_FPO_RISCV_INCOMPATIBLE << 1)) ||
      !(mock.registers[NEMU_FPGA_MB_STATUS / 4] & NEMU_FPGA_MB_RESPONSE_PENDING)) {
    fail("valid response was not atomically committed");
  }

  mock = pending_add_request();
  mock.change_sequence = true;
  io = mock_io(&mock);
  if (nemu_fpga_mailbox_service_once(&io, 32) != NEMU_FPGA_MB_STALE ||
      mock.registers[NEMU_FPGA_MB_RESPONSE_COMMIT / 4] != 0) {
    fail("stale sequence was committed");
  }

  mock = pending_add_request();
  mock.reset_request = true;
  io = mock_io(&mock);
  if (nemu_fpga_mailbox_service_once(&io, 32) != NEMU_FPGA_MB_STALE ||
      mock.registers[NEMU_FPGA_MB_RESPONSE_COMMIT / 4] != 0) {
    fail("request removed by reset was committed");
  }

  mock = pending_add_request();
  mock.registers[NEMU_FPGA_MB_TIMEOUT_CYCLES / 4] = 1001;
  io = mock_io(&mock);
  if (nemu_fpga_mailbox_service_once(&io, 32) != NEMU_FPGA_MB_TIMEOUT ||
      mock.registers[NEMU_FPGA_MB_RESPONSE_COMMIT / 4] != 0) {
    fail("timed-out request was committed");
  }

  memset(&mock, 0, sizeof(mock));
  io = mock_io(&mock);
  if (nemu_fpga_mailbox_service_once(&io, 32) != NEMU_FPGA_MB_IDLE) {
    fail("idle mailbox did not remain idle");
  }
}

static void test_runtime_service(void) {
  struct mock_mailbox mock = {0};
  struct nemu_fpga_mailbox_io io = mock_io(&mock);
  if (nemu_fpga_runtime_hold_reset(&io) != 0 ||
      mock.registers[NEMU_FPGA_RT_CONTROL / 4] != NEMU_FPGA_RT_CORE_RESET) {
    fail("runtime did not assert core reset");
  }
  if (nemu_fpga_runtime_start(&io) != 0 ||
      !(mock.registers[NEMU_FPGA_RT_STATUS / 4] & NEMU_FPGA_RT_RUNNING)) {
    fail("runtime did not release core reset");
  }

  mock.registers[NEMU_FPGA_RT_STATUS / 4] |= NEMU_FPGA_RT_PUTCH_PENDING;
  mock.registers[NEMU_FPGA_RT_PUTCH_DATA / 4] = 'A';
  struct nemu_fpga_runtime_event event = nemu_fpga_runtime_service_once(&io, 32);
  if (event.type != NEMU_FPGA_RT_EVENT_PUTCH || event.value != 'A' ||
      (mock.registers[NEMU_FPGA_RT_STATUS / 4] & NEMU_FPGA_RT_PUTCH_PENDING)) {
    fail("runtime did not consume putch event");
  }

  mock.registers[NEMU_FPGA_RT_STATUS / 4] |= NEMU_FPGA_RT_HALTED;
  mock.registers[NEMU_FPGA_RT_HALT_CODE_LOW / 4] = 0x89abcdef;
  mock.registers[NEMU_FPGA_RT_HALT_CODE_HIGH / 4] = 0x01234567;
  event = nemu_fpga_runtime_service_once(&io, 64);
  if (event.type != NEMU_FPGA_RT_EVENT_HALT ||
      event.value != UINT64_C(0x0123456789abcdef)) {
    fail("runtime did not report halt snapshot");
  }

  mock.registers[NEMU_FPGA_DEBUG_PROTOCOL / 4] = NEMU_FPGA_DEBUG_PROTOCOL_V3;
  mock.registers[NEMU_FPGA_DEBUG_STOP_REASON / 4] = NEMU_FPGA_STOP_HALT_REQUEST;
  event = nemu_fpga_runtime_service_once(&io, 64);
  if (event.type != NEMU_FPGA_RT_EVENT_NONE) {
    fail("v3 host-requested halt was mistaken for program completion");
  }

  mock.registers[NEMU_FPGA_DEBUG_STOP_REASON / 4] = NEMU_FPGA_STOP_STEP;
  event = nemu_fpga_runtime_service_once(&io, 64);
  if (event.type != NEMU_FPGA_RT_EVENT_NONE) {
    fail("v3 single-step halt was mistaken for program completion");
  }

  mock.registers[NEMU_FPGA_DEBUG_STOP_REASON / 4] = NEMU_FPGA_STOP_EBREAK;
  event = nemu_fpga_runtime_service_once(&io, 64);
  if (event.type != NEMU_FPGA_RT_EVENT_HALT) {
    fail("v3 ebreak halt was not reported as program completion");
  }
}

static void test_zcu102_memory_bounds(void) {
  uint8_t guest_memory[8] = {0x10, 0x21, 0x32, 0x43, 0x54, 0x65, 0x76, 0x87};
  uint8_t output[4] = {0};
  struct nemu_fpga_zcu102_uio uio = {
    .guest_memory = guest_memory,
    .guest_memory_size = sizeof(guest_memory),
  };

  if (nemu_fpga_zcu102_uio_read(&uio, 2, output, sizeof(output)) != 0 ||
      memcmp(output, guest_memory + 2, sizeof(output)) != 0) {
    fail("ZCU102 target-memory read returned incorrect bytes");
  }
  errno = 0;
  if (nemu_fpga_zcu102_uio_read(&uio, 7, output, 2) == 0 || errno != EINVAL) {
    fail("ZCU102 target-memory read accepted an out-of-range span");
  }
  errno = 0;
  if (nemu_fpga_zcu102_uio_read(&uio, 9, output, 0) == 0 || errno != EINVAL) {
    fail("ZCU102 target-memory read accepted an out-of-range offset");
  }
}

int main(void) {
  test_executor();
  test_integer_executor();
  test_floating_operation_coverage();
  test_mailbox_service();
  test_runtime_service();
  test_zcu102_memory_bounds();
  puts("FPGA mailbox fallback tests passed");
  return 0;
}
