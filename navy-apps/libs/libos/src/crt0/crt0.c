#include <stdint.h>
#include <stdlib.h>
#include <assert.h>

int main(int argc, char *argv[], char *envp[]);
extern char **environ;

const char *__navy_argv0 __attribute__((weak)) = "program";

void call_main(uintptr_t *args) {
  char *argv[] = { (char *)__navy_argv0, NULL };
  static char *envp[] = { NULL };
  environ = envp;
  exit(main(1, argv, envp));
  assert(0);
}
