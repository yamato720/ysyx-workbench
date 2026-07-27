#include "fpga-mailbox.h"
#include "fpga-zcu102-uio.h"

#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define ARRAY_SIZE(array) (sizeof(array) / sizeof((array)[0]))

struct mock_runtime {
  uint32_t registers[0x100 / 4];
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
  if (value & NEMU_FPGA_RT_CLEAR_HALT)
    runtime->registers[NEMU_FPGA_RT_STATUS / 4] &= ~NEMU_FPGA_RT_HALTED;
  if (value & NEMU_FPGA_RT_ACK_PUTCH)
    runtime->registers[NEMU_FPGA_RT_STATUS / 4] &= ~NEMU_FPGA_RT_PUTCH_PENDING;
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
  if (nemu_fpga_runtime_hold_reset(&io) != 0 ||
      runtime.registers[NEMU_FPGA_RT_CONTROL / 4] != NEMU_FPGA_RT_CORE_RESET)
    fail("runtime did not assert core reset");
  if (nemu_fpga_runtime_start(&io) != 0 ||
      !(runtime.registers[NEMU_FPGA_RT_STATUS / 4] & NEMU_FPGA_RT_RUNNING))
    fail("runtime did not release core reset");

  runtime.registers[NEMU_FPGA_RT_STATUS / 4] |= NEMU_FPGA_RT_PUTCH_PENDING;
  runtime.registers[NEMU_FPGA_RT_PUTCH_DATA / 4] = 'A';
  struct nemu_fpga_runtime_event event = nemu_fpga_runtime_service_once(&io);
  if (event.type != NEMU_FPGA_RT_EVENT_PUTCH || event.value != 'A' ||
      (runtime.registers[NEMU_FPGA_RT_STATUS / 4] & NEMU_FPGA_RT_PUTCH_PENDING))
    fail("runtime did not acknowledge putch");

  runtime.registers[NEMU_FPGA_RT_STATUS / 4] |= NEMU_FPGA_RT_HALTED;
  runtime.registers[NEMU_FPGA_RT_HALT_CODE_LOW / 4] = UINT32_C(0x89abcdef);
  runtime.registers[NEMU_FPGA_RT_HALT_CODE_HIGH / 4] = UINT32_C(0x01234567);
  runtime.registers[NEMU_FPGA_DEBUG_PROTOCOL / 4] = NEMU_FPGA_DEBUG_PROTOCOL_V5;
  runtime.registers[NEMU_FPGA_DEBUG_STOP_REASON / 4] = NEMU_FPGA_STOP_EBREAK;
  event = nemu_fpga_runtime_service_once(&io);
  if (event.type != NEMU_FPGA_RT_EVENT_HALT ||
      event.value != UINT64_C(0x0123456789abcdef))
    fail("runtime did not report ebreak halt");

  runtime.registers[NEMU_FPGA_DEBUG_STOP_REASON / 4] = NEMU_FPGA_STOP_STEP;
  event = nemu_fpga_runtime_service_once(&io);
  if (event.type != NEMU_FPGA_RT_EVENT_NONE)
    fail("single-step halt was mistaken for program completion");

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
  test_zcu102_memory_bounds();
  puts("FPGA runtime v5 tests passed");
  return 0;
}
