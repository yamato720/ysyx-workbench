#include <stdio.h>

#ifndef ZCU102_RUNTIME
#error "ps_nemu_sdb.c must be built through zcu102-runtime with ZCU102_RUNTIME defined"
#endif

void init_monitor(int argc, char *argv[]);
void engine_start(void);
int is_exit_status_bad(void);

int main(int argc, char **argv) {
  setvbuf(stdout, NULL, _IONBF, 0);
  setvbuf(stderr, NULL, _IONBF, 0);
  puts("ZCU102 PS NEMU SDB");
  init_monitor(argc, argv);
  engine_start();
  return is_exit_status_bad();
}
