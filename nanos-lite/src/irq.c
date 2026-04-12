#include <common.h>

void do_syscall(Context *c);

static Context* do_event(Event e, Context* c) {
  switch (e.event) {
    case EVENT_YIELD:
      printf("Yielding...\n");
      printf("Current context: mcause = %lu, mepc = 0x%08lx\n", c->mcause, c->mepc);
      printf("finish yield test\n");
      break;
    case EVENT_SYSCALL:
      printf("Handling syscall...\n");
      do_syscall(c);
      printf("finish syscall test\n");
      break;
    default: panic("Unhandled event ID = %d", e.event);
  }

  return c;
}

void init_irq(void) {
  Log("Initializing interrupt/exception handler...");
  cte_init(do_event);
}
