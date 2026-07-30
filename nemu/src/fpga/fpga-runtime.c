#include "fpga-mailbox.h"

#include <errno.h>
#include <stdatomic.h>

static uint64_t join_u64(uint32_t low, uint32_t high) {
  return (uint64_t)low | ((uint64_t)high << 32);
}

int nemu_fpga_runtime_hold_reset(const struct nemu_fpga_mailbox_io *io) {
  if (io == NULL || io->write32 == NULL) return -1;
  io->write32(io->opaque, NEMU_FPGA_RT_CONTROL,
              NEMU_FPGA_RT_CORE_RESET | NEMU_FPGA_RT_START |
              NEMU_FPGA_RT_ACK_PUTCH | NEMU_FPGA_RT_ACK_COMPLETION);
  // The mailbox itself remains live while the core is reset. Re-arm it for
  // the next host by clearing pending events and the guest interrupt.
  io->write32(io->opaque, NEMU_FPGA_RT_GUEST_INTERRUPT, 0);
  atomic_thread_fence(memory_order_seq_cst);
  return 0;
}

int nemu_fpga_runtime_start(const struct nemu_fpga_mailbox_io *io) {
  if (io == NULL || io->write32 == NULL) return -1;
  atomic_thread_fence(memory_order_release);
  io->write32(io->opaque, NEMU_FPGA_RT_CONTROL, NEMU_FPGA_RT_START);
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

int nemu_fpga_runtime_validate_trace_records(
    const struct npc_fpga_trace_record *records, size_t count) {
  if (count != 0 && records == NULL) {
    errno = EINVAL;
    return -1;
  }
  for (size_t index = 0; index < count; index++) {
    if ((records[index].flags & ~UINT8_C(0x07)) != 0 ||
        (records[index].saturation & ~UINT8_C(0x1f)) != 0) {
      errno = EPROTO;
      return -1;
    }
  }
  return 0;
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
  } else if (status & NEMU_FPGA_RT_COMPLETION_PENDING) {
    event.type = NEMU_FPGA_RT_EVENT_COMPLETION;
    event.value = nemu_fpga_runtime_read_counter(io, NEMU_FPGA_RT_COMPLETION_CODE_LOW,
                                                 NEMU_FPGA_RT_COMPLETION_CODE_HIGH);
  }
  return event;
}
