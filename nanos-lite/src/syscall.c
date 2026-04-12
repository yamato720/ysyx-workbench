#include <common.h>
#include <fs.h>
#include <memory.h>
#include "syscall.h"

#ifdef STRACE
static const char *syscall_names[] = {
  [SYS_exit]        = "exit",
  [SYS_yield]       = "yield",
  [SYS_open]        = "open",
  [SYS_read]        = "read",
  [SYS_write]       = "write",
  [SYS_kill]        = "kill",
  [SYS_getpid]      = "getpid",
  [SYS_close]       = "close",
  [SYS_lseek]       = "lseek",
  [SYS_brk]         = "brk",
  [SYS_fstat]       = "fstat",
  [SYS_time]        = "time",
  [SYS_signal]      = "signal",
  [SYS_execve]      = "execve",
  [SYS_fork]        = "fork",
  [SYS_link]        = "link",
  [SYS_unlink]      = "unlink",
  [SYS_wait]        = "wait",
  [SYS_times]       = "times",
  [SYS_gettimeofday]= "gettimeofday",
};
#define NR_SYSCALLS (sizeof(syscall_names) / sizeof(syscall_names[0]))
#endif

void do_syscall(Context *c) {
  uintptr_t a[4];
  a[0] = c->GPR1; // syscall number (a7)
  a[1] = c->GPR2; // arg0 (a0)
  a[2] = c->GPR3; // arg1 (a1)
  a[3] = c->GPR4; // arg2 (a2)

#ifdef STRACE
  const char *name = (a[0] < NR_SYSCALLS && syscall_names[a[0]])
                     ? syscall_names[a[0]] : "unknown";
  Log("[strace] %s(%lu, %lu, %lu)", name, a[1], a[2], a[3]);
#endif

  switch (a[0]) {
    case SYS_exit:
      halt(a[1]);
      break;

    case SYS_yield:
      yield();
      c->GPRx = 0;
      break;

    case SYS_open:
      c->GPRx = fs_open((const char *)a[1], (int)a[2], (int)a[3]);
      break;

    case SYS_read:
      c->GPRx = fs_read((int)a[1], (void *)a[2], (size_t)a[3]);
      break;

    case SYS_write:
      c->GPRx = fs_write((int)a[1], (const void *)a[2], (size_t)a[3]);
      break;

    case SYS_close:
      c->GPRx = fs_close((int)a[1]);
      break;

    case SYS_lseek:
      c->GPRx = fs_lseek((int)a[1], (intptr_t)a[2], (int)a[3]);
      break;

    case SYS_brk:
      c->GPRx = mm_brk((uintptr_t)a[1]);
      break;

    case SYS_getpid:
      c->GPRx = 0;
      break;

    case SYS_gettimeofday: {
      // arg0: struct timeval *tv  {long tv_sec; long tv_usec;}
      // arg1: struct timezone *tz (ignored)
      if (a[1] != 0) {
        uint64_t us = io_read(AM_TIMER_UPTIME).us;
        long *tv = (long *)a[1];
        tv[0] = (long)(us / 1000000); // tv_sec
        tv[1] = (long)(us % 1000000); // tv_usec
      }
      c->GPRx = 0;
      break;
    }

    case SYS_time: {
      // returns seconds since some epoch (use uptime as approximation)
      uint64_t us = io_read(AM_TIMER_UPTIME).us;
      c->GPRx = (uintptr_t)(us / 1000000);
      break;
    }

    case SYS_fstat:
      // not implemented; return -1 to signal unsupported
      c->GPRx = (uintptr_t)-1;
      break;

    default: panic("Unhandled syscall ID = %lu", a[0]);
  }

#ifdef STRACE
  Log("[strace]   => %ld", (intptr_t)c->GPRx);
#endif
}
