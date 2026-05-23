#include "zcu102_npc_debugger.h"

#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>

void znpc_reset(const znpc_runtime_t *rt) {
  znpc_write_reg(rt, ZNPC_REG_CONTROL,
                 ZNPC_CONTROL_RESET_CPU |
                 ZNPC_CONTROL_CLEAR_TRACE |
                 ZNPC_CONTROL_CLEAR_PUTCH |
                 ZNPC_CONTROL_CLEAR_STATUS);
}

void znpc_load_image(const znpc_runtime_t *rt, const void *image, size_t size) {
  if (size > rt->guest_size) {
    size = rt->guest_size;
  }
  memcpy((void *)rt->guest_base, image, size);
}

void znpc_start(const znpc_runtime_t *rt, uint32_t boot_pc, int trace_enable) {
  uint32_t control = ZNPC_CONTROL_RUN | ZNPC_CONTROL_CLEAR_STATUS;
  if (trace_enable) {
    control |= ZNPC_CONTROL_TRACE_ENABLE;
  }
  znpc_write_reg(rt, ZNPC_REG_BOOT_PC, boot_pc);
  znpc_write_reg(rt, ZNPC_REG_CONTROL, control);
}

uint32_t znpc_poll(const znpc_runtime_t *rt) {
  uint32_t status = znpc_read_reg(rt, ZNPC_REG_STATUS);
  znpc_drain_putch(rt);
  return status;
}

void znpc_drain_putch(const znpc_runtime_t *rt) {
  while (znpc_read_reg(rt, ZNPC_REG_PUTCH_STATUS) & ZNPC_STATUS_PUTCH_VALID) {
    uint32_t ch = znpc_read_reg(rt, ZNPC_REG_PUTCH_DATA) & 0xffu;
    putchar((int)ch);
  }
}

uint32_t znpc_exit_code(const znpc_runtime_t *rt) {
  return znpc_read_reg(rt, ZNPC_REG_EXIT_CODE);
}
