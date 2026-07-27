#include "fpga-mailbox.h"

#include <stdatomic.h>

static uint64_t join_u64(uint32_t low, uint32_t high) {
  return (uint64_t)low | ((uint64_t)high << 32);
}

int nemu_fpga_runtime_hold_reset(const struct nemu_fpga_mailbox_io *io) {
  if (io == NULL || io->write32 == NULL) return -1;
  io->write32(io->opaque, NEMU_FPGA_RT_CONTROL,
              NEMU_FPGA_RT_CORE_RESET | NEMU_FPGA_RT_CLEAR_HALT);
  atomic_thread_fence(memory_order_seq_cst);
  return 0;
}

int nemu_fpga_runtime_start(const struct nemu_fpga_mailbox_io *io) {
  if (io == NULL || io->write32 == NULL) return -1;
  atomic_thread_fence(memory_order_release);
  io->write32(io->opaque, NEMU_FPGA_RT_CONTROL, NEMU_FPGA_RT_CLEAR_HALT);
  return 0;
}

int nemu_fpga_runtime_start_halted(const struct nemu_fpga_mailbox_io *io) {
  if (io == NULL || io->write32 == NULL) return -1;
  atomic_thread_fence(memory_order_release);
  io->write32(io->opaque, NEMU_FPGA_RT_CONTROL, 0);
  return 0;
}

uint64_t nemu_fpga_runtime_read_counter(const struct nemu_fpga_mailbox_io *io,
                                        uint32_t low_offset, uint32_t high_offset) {
  if (io == NULL || io->read32 == NULL) return 0;
  uint32_t high_before;
  uint32_t high_after;
  uint32_t low;
  do {
    high_before = io->read32(io->opaque, high_offset);
    low = io->read32(io->opaque, low_offset);
    high_after = io->read32(io->opaque, high_offset);
  } while (high_before != high_after);
  return join_u64(low, high_after);
}

struct nemu_fpga_runtime_event
nemu_fpga_runtime_service_once(const struct nemu_fpga_mailbox_io *io) {
  struct nemu_fpga_runtime_event event = { .type = NEMU_FPGA_RT_EVENT_NONE };
  if (io == NULL || io->read32 == NULL || io->write32 == NULL) {
    event.type = NEMU_FPGA_RT_EVENT_ERROR;
    event.error = -1;
    return event;
  }

  const uint32_t status = io->read32(io->opaque, NEMU_FPGA_RT_STATUS);
  if (status & NEMU_FPGA_RT_PROTOCOL_ERROR) {
    event.type = NEMU_FPGA_RT_EVENT_ERROR;
    event.error = -2;
  } else if (status & NEMU_FPGA_RT_PUTCH_PENDING) {
    event.type = NEMU_FPGA_RT_EVENT_PUTCH;
    event.value = io->read32(io->opaque, NEMU_FPGA_RT_PUTCH_DATA) & 0xff;
    const uint32_t control = io->read32(io->opaque, NEMU_FPGA_RT_CONTROL);
    io->write32(io->opaque, NEMU_FPGA_RT_CONTROL,
                (control & NEMU_FPGA_RT_CORE_RESET) | NEMU_FPGA_RT_ACK_PUTCH);
  } else if ((status & NEMU_FPGA_RT_HALTED) &&
             (io->read32(io->opaque, NEMU_FPGA_DEBUG_PROTOCOL) !=
                  NEMU_FPGA_DEBUG_PROTOCOL_V5 ||
              (io->read32(io->opaque, NEMU_FPGA_DEBUG_STOP_REASON) & 0xf) ==
                  NEMU_FPGA_STOP_EBREAK)) {
    event.type = NEMU_FPGA_RT_EVENT_HALT;
    event.value = nemu_fpga_runtime_read_counter(io, NEMU_FPGA_RT_HALT_CODE_LOW,
                                                 NEMU_FPGA_RT_HALT_CODE_HIGH);
  }
  return event;
}
