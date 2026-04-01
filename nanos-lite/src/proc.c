#include <proc.h>
#include <fs.h>

#define MAX_NR_PROC 4

static PCB pcb[MAX_NR_PROC] __attribute__((used)) = {};
static PCB pcb_boot = {};
PCB *current = NULL;

void switch_boot_pcb() {
  current = &pcb_boot;
}

void hello_fun(void *arg) {
  int j = 1;
  while (1) {
    Log("Hello World from Nanos-lite with arg '%p' for the %dth time!", arg, j);
    j ++;
    yield();
  }
}

void init_proc() {
  switch_boot_pcb();

  Log("Initializing processes...");

  // Load program: use PROG from make variable, or default to first file in ramdisk
#ifdef PROGRAM_NAME
  const char *prog = PROGRAM_NAME;
#else
  const char *prog = fs_first_file();
#endif
  Log("Loading %s", prog);
  naive_uload(NULL, prog);

}

Context* schedule(Context *prev) {
  return NULL;
}
