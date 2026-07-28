#include "fpga-mailbox.h"
#include "fpga-zcu102-uio.h"

#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define ARRAY_SIZE(array) (sizeof(array) / sizeof((array)[0]))

struct mock_runtime {
  uint32_t registers[0x200 / 4];
};

static void fail(const char *message) {
  fprintf(stderr, "fpga-runtime-test: %s\n", message);
  exit(1);
}

static uint32_t mock_read32(void *opaque, uint32_t offset) {
  struct mock_runtime *runtime = opaque;
  if (offset / 4 >= ARRAY_SIZE(runtime->registers)) fail("mock read outside register map");
  return runtime->registers[offset / 4];
}

static void mock_write32(void *opaque, uint32_t offset, uint32_t value) {
  struct mock_runtime *runtime = opaque;
  if (offset / 4 >= ARRAY_SIZE(runtime->registers)) fail("mock write outside register map");
  runtime->registers[offset / 4] = value;
  if (offset != NEMU_FPGA_RT_CONTROL) return;
  runtime->registers[offset / 4] = value & NEMU_FPGA_RT_CORE_RESET;
  if (value & NEMU_FPGA_RT_CORE_RESET)
    runtime->registers[NEMU_FPGA_RT_STATUS / 4] &= ~NEMU_FPGA_RT_RUNNING;
  else
    runtime->registers[NEMU_FPGA_RT_STATUS / 4] |= NEMU_FPGA_RT_RUNNING;
  if (value & NEMU_FPGA_RT_START)
    runtime->registers[NEMU_FPGA_RT_STATUS / 4] &= ~NEMU_FPGA_RT_HALTED;
  if (value & NEMU_FPGA_RT_ACK_PUTCH)
    runtime->registers[NEMU_FPGA_RT_STATUS / 4] &= ~NEMU_FPGA_RT_PUTCH_PENDING;
  if (value & NEMU_FPGA_RT_ACK_COMPLETION)
    runtime->registers[NEMU_FPGA_RT_STATUS / 4] &= ~NEMU_FPGA_RT_COMPLETION_PENDING;
}

static struct nemu_fpga_mailbox_io mock_io(struct mock_runtime *runtime) {
  return (struct nemu_fpga_mailbox_io) {
    .opaque = runtime,
    .read32 = mock_read32,
    .write32 = mock_write32,
  };
}

static void test_runtime_service(void) {
  struct mock_runtime runtime = {0};
  struct nemu_fpga_mailbox_io io = mock_io(&runtime);
  runtime.registers[NEMU_FPGA_RT_GUEST_INTERRUPT / 4] =
      NEMU_FPGA_RT_GUEST_INTERRUPT_MEIP;
  runtime.registers[NEMU_FPGA_RT_STATUS / 4] =
      NEMU_FPGA_RT_PUTCH_PENDING | NEMU_FPGA_RT_COMPLETION_PENDING;
  if (nemu_fpga_runtime_hold_reset(&io) != 0 ||
      runtime.registers[NEMU_FPGA_RT_CONTROL / 4] != NEMU_FPGA_RT_CORE_RESET)
    fail("runtime did not assert core reset");
  if (runtime.registers[NEMU_FPGA_RT_GUEST_INTERRUPT / 4] != 0)
    fail("runtime reset did not clear the guest interrupt");
  if (runtime.registers[NEMU_FPGA_RT_STATUS / 4] &
      (NEMU_FPGA_RT_PUTCH_PENDING | NEMU_FPGA_RT_COMPLETION_PENDING))
    fail("runtime reset did not clear pending mailbox events");
  if (nemu_fpga_runtime_start(&io) != 0 ||
      !(runtime.registers[NEMU_FPGA_RT_STATUS / 4] & NEMU_FPGA_RT_RUNNING))
    fail("runtime did not release core reset");

  runtime.registers[NEMU_FPGA_RT_STATUS / 4] |= NEMU_FPGA_RT_PUTCH_PENDING;
  runtime.registers[NEMU_FPGA_RT_PUTCH_DATA / 4] = 'A';
  struct nemu_fpga_runtime_event event = nemu_fpga_runtime_service_once(&io);
  if (event.type != NEMU_FPGA_RT_EVENT_PUTCH || event.value != 'A' ||
      (runtime.registers[NEMU_FPGA_RT_STATUS / 4] & NEMU_FPGA_RT_PUTCH_PENDING))
    fail("runtime did not acknowledge putch");

  runtime.registers[NEMU_FPGA_RT_STATUS / 4] |= NEMU_FPGA_RT_COMPLETION_PENDING;
  runtime.registers[NEMU_FPGA_RT_COMPLETION_CODE_LOW / 4] = UINT32_C(0x89abcdef);
  runtime.registers[NEMU_FPGA_RT_COMPLETION_CODE_HIGH / 4] = UINT32_C(0x01234567);
  event = nemu_fpga_runtime_service_once(&io);
  if (event.type != NEMU_FPGA_RT_EVENT_COMPLETION ||
      event.value != UINT64_C(0x0123456789abcdef))
    fail("runtime did not report completion");

  mock_write32(&runtime, NEMU_FPGA_RT_CONTROL, NEMU_FPGA_RT_ACK_COMPLETION);
  event = nemu_fpga_runtime_service_once(&io);
  if (event.type != NEMU_FPGA_RT_EVENT_NONE)
    fail("completion acknowledgement was ignored");

  runtime.registers[NEMU_FPGA_RT_STATUS / 4] |= NEMU_FPGA_RT_PROTOCOL_ERROR;
  event = nemu_fpga_runtime_service_once(&io);
  if (event.type != NEMU_FPGA_RT_EVENT_ERROR)
    fail("runtime did not report protocol error");
}

static void test_counter_read(void) {
  struct mock_runtime runtime = {0};
  struct nemu_fpga_mailbox_io io = mock_io(&runtime);
  runtime.registers[NEMU_FPGA_RT_CYCLE_LOW / 4] = 0x12345678;
  runtime.registers[NEMU_FPGA_RT_CYCLE_HIGH / 4] = 0x9abcdef0;
  if (nemu_fpga_runtime_read_counter(&io, NEMU_FPGA_RT_CYCLE_LOW,
                                     NEMU_FPGA_RT_CYCLE_HIGH) !=
      UINT64_C(0x9abcdef012345678))
    fail("runtime counter snapshot mismatch");
}

static void test_trace_abi_registers(void) {
  struct mock_runtime runtime = {0};
  struct nemu_fpga_mailbox_io io = mock_io(&runtime);
  runtime.registers[NEMU_FPGA_TRACE_RECORDS_LOW / 4] = UINT32_C(0x89abcdef);
  runtime.registers[NEMU_FPGA_TRACE_RECORDS_HIGH / 4] = UINT32_C(0x01234567);
  runtime.registers[NEMU_FPGA_TRACE_DROPPED_LOW / 4] = UINT32_C(0x76543210);
  runtime.registers[NEMU_FPGA_TRACE_DROPPED_HIGH / 4] = UINT32_C(0xfedcba98);
  runtime.registers[NEMU_FPGA_TRACE_FLAGS / 4] =
      NEMU_FPGA_TRACE_ENABLED | NEMU_FPGA_TRACE_DRAINED;
  if (nemu_fpga_runtime_read_counter(&io, NEMU_FPGA_TRACE_RECORDS_LOW,
                                     NEMU_FPGA_TRACE_RECORDS_HIGH) !=
      UINT64_C(0x0123456789abcdef))
    fail("trace record counter ABI mismatch");
  if (nemu_fpga_runtime_read_counter(&io, NEMU_FPGA_TRACE_DROPPED_LOW,
                                     NEMU_FPGA_TRACE_DROPPED_HIGH) !=
      UINT64_C(0xfedcba9876543210))
    fail("trace dropped counter ABI mismatch");
  if ((runtime.registers[NEMU_FPGA_TRACE_FLAGS / 4] &
       (NEMU_FPGA_TRACE_ENABLED | NEMU_FPGA_TRACE_DRAINED)) !=
      (NEMU_FPGA_TRACE_ENABLED | NEMU_FPGA_TRACE_DRAINED))
    fail("trace flags ABI mismatch");
  struct npc_fpga_trace_record record = {
    .sequence = 1,
    .pc = UINT64_C(0x80000000),
    .instruction = UINT32_C(0x00000013),
    .commit_cycle = 7,
    .stage = {1, 1, 2, 2, 1},
  };
  if (sizeof(record) != NEMU_FPGA_TRACE_RECORD_BYTES_V1 || record.sequence != 1 ||
      record.stage[NPC_FPGA_TRACE_STAGE_COUNT - 1] != 1)
    fail("trace record layout mismatch");
  if (nemu_fpga_runtime_validate_trace_records(&record, 1) != 0)
    fail("valid trace record was rejected");
  record.sequence = 2;
  errno = 0;
  if (nemu_fpga_runtime_validate_trace_records(&record, 1) == 0 || errno != EPROTO)
    fail("corrupt trace record was accepted");
}

static void test_zcu102_memory_bounds(void) {
  uint8_t guest_memory[8] = {0x10, 0x21, 0x32, 0x43, 0x54, 0x65, 0x76, 0x87};
  uint8_t output[4] = {0};
  const uint8_t input[2] = {0xaa, 0xbb};
  struct nemu_fpga_zcu102_uio uio = {
    .guest_memory = guest_memory,
    .guest_memory_size = sizeof(guest_memory),
    .memory_mapping = guest_memory,
    .memory_mapping_size = sizeof(guest_memory),
  };
  if (nemu_fpga_zcu102_uio_read(&uio, 2, output, sizeof(output)) != 0 ||
      memcmp(output, guest_memory + 2, sizeof(output)) != 0)
    fail("ZCU102 target-memory read returned incorrect bytes");
  errno = 0;
  if (nemu_fpga_zcu102_uio_read(&uio, 7, output, 2) == 0 || errno != EINVAL)
    fail("ZCU102 target-memory read accepted an out-of-range span");
  errno = 0;
  if (nemu_fpga_zcu102_uio_write(&uio, 7, input, sizeof(input)) == 0 || errno != EINVAL)
    fail("ZCU102 target-memory write accepted an out-of-range span");
}

int main(void) {
  test_runtime_service();
  test_counter_read();
  test_trace_abi_registers();
  test_zcu102_memory_bounds();
  puts("FPGA runtime mailbox tests passed");
  return 0;
}
