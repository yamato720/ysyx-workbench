#include <common.h>

static Context* do_event(Event e, Context* c) {
  switch (e.event) {
    case EVENT_YIELD:
      printf("Yielding...\n");
      printf("Current context: mcause = %lu, mepc = 0x%08lx\n", c->mcause, c->mepc);
      printf("finish yield test\n");
      break;
    default: panic("Unhandled event ID = %d", e.event);
  }

  return c;
}

void init_irq(void) {
  Log("Initializing interrupt/exception handler...");
  cte_init(do_event);
}
