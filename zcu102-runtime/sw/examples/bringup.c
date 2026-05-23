#include "zcu102_npc_debugger.h"

#include <stdint.h>
#include <stdio.h>

#ifndef ZNPC_REGS_BASE
#define ZNPC_REGS_BASE  0xA0000000u
#endif

#ifndef ZNPC_GUEST_BASE
#define ZNPC_GUEST_BASE 0x80000000u
#endif

#ifndef ZNPC_GUEST_SIZE
#define ZNPC_GUEST_SIZE (1024u * 1024u)
#endif

extern const uint8_t image_start[];
extern const uint8_t image_end[];

int main(void) {
  znpc_runtime_t rt = {
    .regs_base = ZNPC_REGS_BASE,
    .guest_base = ZNPC_GUEST_BASE,
    .guest_size = ZNPC_GUEST_SIZE,
  };

  const size_t image_size = (size_t)(image_end - image_start);

  znpc_reset(&rt);
  znpc_load_image(&rt, image_start, image_size);
  znpc_start(&rt, 0x80000000u, 1);

  for (;;) {
    uint32_t status = znpc_poll(&rt);
    if (status & ZNPC_STATUS_HALTED) {
      break;
    }
  }

  printf("\nNPC exit code: %u\n", znpc_exit_code(&rt));
  return (int)znpc_exit_code(&rt);
}
