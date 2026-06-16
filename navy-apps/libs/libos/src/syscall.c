#include <unistd.h>
#include <errno.h>
#include <signal.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/resource.h>
#include <sys/wait.h>
#include <assert.h>
#include <string.h>
#include <time.h>
#include "syscall.h"

int _stat(const char *fname, struct stat *buf);

// helper macros
#define _concat(x, y) x ## y
#define concat(x, y) _concat(x, y)
#define _args(n, list) concat(_arg, n) list
#define _arg0(a0, ...) a0
#define _arg1(a0, a1, ...) a1
#define _arg2(a0, a1, a2, ...) a2
#define _arg3(a0, a1, a2, a3, ...) a3
#define _arg4(a0, a1, a2, a3, a4, ...) a4
#define _arg5(a0, a1, a2, a3, a4, a5, ...) a5

// extract an argument from the macro array
#define SYSCALL  _args(0, ARGS_ARRAY)
#define GPR1 _args(1, ARGS_ARRAY)
#define GPR2 _args(2, ARGS_ARRAY)
#define GPR3 _args(3, ARGS_ARRAY)
#define GPR4 _args(4, ARGS_ARRAY)
#define GPRx _args(5, ARGS_ARRAY)

// ISA-depedent definitions
#if defined(__ISA_X86__)
# define ARGS_ARRAY ("int $0x80", "eax", "ebx", "ecx", "edx", "eax")
#elif defined(__ISA_MIPS32__)
# define ARGS_ARRAY ("syscall", "v0", "a0", "a1", "a2", "v0")
#elif defined(__riscv)
#ifdef __riscv_e
# define ARGS_ARRAY ("ecall", "a5", "a0", "a1", "a2", "a0")
#else
# define ARGS_ARRAY ("ecall", "a7", "a0", "a1", "a2", "a0")
#endif
#elif defined(__ISA_AM_NATIVE__)
# define ARGS_ARRAY ("call *0x100000", "rdi", "rsi", "rdx", "rcx", "rax")
#elif defined(__ISA_X86_64__)
# define ARGS_ARRAY ("int $0x80", "rdi", "rsi", "rdx", "rcx", "rax")
#elif defined(__ISA_LOONGARCH32R__)
# define ARGS_ARRAY ("syscall 0", "a7", "a0", "a1", "a2", "a0")
#else
#error _syscall_ is not implemented
#endif

intptr_t _syscall_(intptr_t type, intptr_t a0, intptr_t a1, intptr_t a2) {
  register intptr_t _gpr1 asm (GPR1) = type;
  register intptr_t _gpr2 asm (GPR2) = a0;
  register intptr_t _gpr3 asm (GPR3) = a1;
  register intptr_t _gpr4 asm (GPR4) = a2;
  register intptr_t ret asm (GPRx);
  asm volatile (SYSCALL : "=r" (ret) : "r"(_gpr1), "r"(_gpr2), "r"(_gpr3), "r"(_gpr4));
  return ret;
}

void _exit(int status) {
  _syscall_(SYS_exit, status, 0, 0);
  while (1);
}

int _open(const char *path, int flags, mode_t mode) {
  return _syscall_(SYS_open, (intptr_t)path, flags, mode);
}

int _write(int fd, void *buf, size_t count) {
  return _syscall_(SYS_write, fd, (intptr_t)buf, count);
}

/* Minimal sbrk: initialize break at _end (linker-defined end of BSS). */
extern char _end[];
static intptr_t _brk_cur = 0;

void *_sbrk(intptr_t increment) {
  if (_brk_cur == 0) {
    _brk_cur = (intptr_t)_end;
  }
  intptr_t old = _brk_cur;
  if (increment != 0) {
    intptr_t new_brk = _brk_cur + increment;
    if (_syscall_(SYS_brk, new_brk, 0, 0) != 0) {
      return (void *)-1;
    }
    _brk_cur = new_brk;
  }
  return (void *)old;
}

int _read(int fd, void *buf, size_t count) {
  return _syscall_(SYS_read, fd, (intptr_t)buf, count);
}

int _close(int fd) {
  return _syscall_(SYS_close, fd, 0, 0);
}

off_t _lseek(int fd, off_t offset, int whence) {
  return _syscall_(SYS_lseek, fd, offset, whence);
}

int _gettimeofday(struct timeval *tv, struct timezone *tz) {
  return _syscall_(SYS_gettimeofday, (intptr_t)tv, (intptr_t)tz, 0);
}

int getrlimit(int resource, struct rlimit *rlim) {
  if (rlim == NULL || resource < 0 || resource >= RLIMIT_NLIMITS) {
    return -1;
  }
  rlim->rlim_cur = RLIM_INFINITY;
  rlim->rlim_max = RLIM_INFINITY;
  return 0;
}

int setrlimit(int resource, const struct rlimit *rlim) {
  if (rlim == NULL || resource < 0 || resource >= RLIMIT_NLIMITS) {
    return -1;
  }
  return 0;
}

int sigaction(int signum, const struct sigaction *act, struct sigaction *oldact) {
  (void)signum;
  if (oldact != NULL) {
    oldact->sa_handler = SIG_DFL;
    sigemptyset(&oldact->sa_mask);
    oldact->sa_flags = 0;
  }
  (void)act;
  return 0;
}

int sigprocmask(int how, const sigset_t *set, sigset_t *oldset) {
  (void)how;
  (void)set;
  if (oldset != NULL) {
    sigemptyset(oldset);
  }
  return 0;
}

int sigsuspend(const sigset_t *sigmask) {
  (void)sigmask;
  errno = EINTR;
  return -1;
}

pid_t waitpid(pid_t pid, int *status, int options) {
  (void)pid;
  (void)options;
  if (status != NULL) {
    *status = 0;
  }
  errno = ECHILD;
  return -1;
}

uid_t getuid(void) {
  return 0;
}

uid_t geteuid(void) {
  return 0;
}

gid_t getgid(void) {
  return 0;
}

gid_t getegid(void) {
  return 0;
}

pid_t getppid(void) {
  return 0;
}

int getgroups(int gidsetsize, gid_t grouplist[]) {
  if (gidsetsize > 0 && grouplist != NULL) {
    grouplist[0] = 0;
    return 1;
  }
  return 0;
}

static mode_t current_umask = 022;

mode_t umask(mode_t cmask) {
  mode_t old = current_umask;
  current_umask = cmask & 0777;
  return old;
}

int chdir(const char *path) {
  (void)path;
  return 0;
}

int lstat(const char *path, struct stat *buf) {
  return _stat(path, buf);
}

long sysconf(int name) {
  switch (name) {
    case _SC_CLK_TCK:
      return 100;
    case _SC_PAGESIZE:
      return 4096;
    case _SC_OPEN_MAX:
      return 16;
    case _SC_NGROUPS_MAX:
      return 1;
    default:
      errno = EINVAL;
      return -1;
  }
}

int getdents(int fd, void *dp, int count) {
  (void)fd;
  (void)dp;
  (void)count;
  return 0;
}

double __strtod_nan(const char *s, char **endptr, char fmt) {
  (void)fmt;
  if (endptr != NULL) {
    *endptr = (char *)s;
  }
  return 0.0 / 0.0;
}

float __strtof_nan(const char *s, char **endptr, char fmt) {
  (void)fmt;
  if (endptr != NULL) {
    *endptr = (char *)s;
  }
  return 0.0f / 0.0f;
}

int _execve(const char *fname, char * const argv[], char *const envp[]) {
  _exit(SYS_execve);
  return 0;
}

// Syscalls below are not used in Nanos-lite.
// But to pass linking, they are defined as dummy functions.

int _fstat(int fd, struct stat *buf) {
  if (buf == NULL) {
    errno = EINVAL;
    return -1;
  }
  memset(buf, 0, sizeof(*buf));
  if (fd >= 0 && fd <= 2) {
    buf->st_mode = S_IFCHR | S_IRUSR | S_IWUSR;
    return 0;
  }
  buf->st_mode = S_IFREG | S_IRUSR | S_IWUSR | S_IXUSR;
  return -1;
}

int _stat(const char *fname, struct stat *buf) {
  (void)fname;
  if (buf == NULL) {
    errno = EINVAL;
    return -1;
  }
  memset(buf, 0, sizeof(*buf));
  buf->st_mode = S_IFREG | S_IRUSR | S_IWUSR | S_IXUSR;
  return -1;
}

int _kill(int pid, int sig) {
  _exit(-SYS_kill);
  return -1;
}

pid_t _getpid() {
  return 1;
}

pid_t _fork() {
  errno = ENOSYS;
  return -1;
}

pid_t vfork() {
  errno = ENOSYS;
  return -1;
}

int _link(const char *d, const char *n) {
  (void)d;
  (void)n;
  errno = ENOSYS;
  return -1;
}

int _unlink(const char *n) {
  (void)n;
  errno = ENOSYS;
  return -1;
}

pid_t _wait(int *status) {
  (void)status;
  errno = ECHILD;
  return -1;
}

clock_t _times(void *buf) {
  (void)buf;
  errno = ENOSYS;
  return 0;
}

int pipe(int pipefd[2]) {
  (void)pipefd;
  errno = ENOSYS;
  return -1;
}

int dup(int oldfd) {
  (void)oldfd;
  errno = ENOSYS;
  return -1;
}

int dup2(int oldfd, int newfd) {
  return -1;
}

unsigned int sleep(unsigned int seconds) {
  return seconds;
}

ssize_t readlink(const char *pathname, char *buf, size_t bufsiz) {
  (void)pathname;
  (void)buf;
  (void)bufsiz;
  errno = ENOSYS;
  return -1;
}

int symlink(const char *target, const char *linkpath) {
  (void)target;
  (void)linkpath;
  errno = ENOSYS;
  return -1;
}

int ioctl(int fd, unsigned long request, ...) {
  return -1;
}
