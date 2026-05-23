#include "zcu102_npc_debugger.h"

#include <stdint.h>
#include <stdio.h>

#ifndef ZNPC_REGS_BASE
#define ZNPC_REGS_BASE 0xA0000000u
#endif

int main(void) {
  znpc_runtime_t rt = {
    .regs_base = ZNPC_REGS_BASE,
    .guest_base = 0,
    .guest_size = 0,
  };

  znpc_reset(&rt);
  znpc_write_reg(&rt, ZNPC_REG_BOOT_PC, 0x80000000u);
  znpc_write_reg(&rt, ZNPC_REG_CONTROL,
                 ZNPC_CONTROL_RUN |
                 ZNPC_CONTROL_TRACE_ENABLE |
                 ZNPC_CONTROL_CLEAR_STATUS |
                 ZNPC_CONTROL_CLEAR_TRACE |
                 ZNPC_CONTROL_CLEAR_PUTCH);

  for (;;) {
    uint32_t status = znpc_poll(&rt);
    if (status & ZNPC_STATUS_HALTED) {
      break;
    }
  }

  uint32_t exit_code = znpc_exit_code(&rt);
  uint32_t instret_low = znpc_read_reg(&rt, ZNPC_REG_INSTRET_LOW);
  uint32_t last_pc = znpc_read_reg(&rt, ZNPC_REG_LAST_COMMIT_PC_LOW);

  printf("selftest exit=%u instret=%u last_pc=0x%08x\n",
         exit_code, instret_low, last_pc);

  return exit_code == 0 ? 0 : 1;
}
