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
  };
  struct nemu_fpga_fallback_response response;
  nemu_fpga_fallback_execute(&inconsistent, 32, &response);
  expect_response("inconsistent-rm-metadata", response, 0, 0, true);
}

static void test_mailbox_service(void) {
  struct mock_mailbox mock = pending_add_request();
  struct nemu_fpga_mailbox_io io = mock_io(&mock);
  if (nemu_fpga_mailbox_service_once(&io, 32) != NEMU_FPGA_MB_RESPONDED) {
    fail("valid request was not serviced");
  }
  if (mock.registers[NEMU_FPGA_MB_RESPONSE_SEQUENCE / 4] != 41 ||
      mock.registers[NEMU_FPGA_MB_RESPONSE_RESULT_LOW / 4] != 0x40400000 ||
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

  mock.registers[NEMU_FPGA_DEBUG_PROTOCOL / 4] = NEMU_FPGA_DEBUG_PROTOCOL_V2;
  mock.registers[NEMU_FPGA_DEBUG_STOP_REASON / 4] = NEMU_FPGA_STOP_HALT_REQUEST;
  event = nemu_fpga_runtime_service_once(&io, 64);
  if (event.type != NEMU_FPGA_RT_EVENT_NONE) {
    fail("v2 host-requested halt was mistaken for program completion");
  }

  mock.registers[NEMU_FPGA_DEBUG_STOP_REASON / 4] = NEMU_FPGA_STOP_STEP;
  event = nemu_fpga_runtime_service_once(&io, 64);
  if (event.type != NEMU_FPGA_RT_EVENT_NONE) {
    fail("v2 single-step halt was mistaken for program completion");
  }

  mock.registers[NEMU_FPGA_DEBUG_STOP_REASON / 4] = NEMU_FPGA_STOP_EBREAK;
  event = nemu_fpga_runtime_service_once(&io, 64);
  if (event.type != NEMU_FPGA_RT_EVENT_HALT) {
    fail("v2 ebreak halt was not reported as program completion");
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
  test_mailbox_service();
  test_runtime_service();
  test_zcu102_memory_bounds();
  puts("FPGA mailbox fallback tests passed");
  return 0;
}
