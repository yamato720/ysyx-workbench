#include <common.h>
#include "syscall.h"

void do_syscall(Context *c) {
  uintptr_t a[4];
  a[0] = c->GPR1; // syscall number (a7)
  a[1] = c->GPR2; // arg0 (a0)
  a[2] = c->GPR3; // arg1 (a1)
  a[3] = c->GPR4; // arg2 (a2)

  switch (a[0]) {
    case SYS_yield:
      yield();
      printf("Yielding...\n");
      c->GPRx = 0;
      break;
    case SYS_exit:
      printf("Exiting with code %lu...\n", a[1]);
      halt(a[1]);
      break;
    default: panic("Unhandled syscall ID = %lu", a[0]);
  }
}
