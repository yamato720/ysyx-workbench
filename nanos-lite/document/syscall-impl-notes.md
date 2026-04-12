# 系统调用全链路实现笔记

> 本文记录从 `navy-apps` 用户程序到 `nanos-lite` 内核，系统调用完整链路的实现过程，
> 以及调试中遇到的问题和修复方法。

---

## 一、整体数据流

```
用户程序 (navy-apps/tests/hello)
  │
  │  write(1, "Hello World!\n", 13)   或   printf(...)
  ▼
newlib libc
  │  write() → _write() → _syscall_(SYS_write, fd, buf, len)
  │  ──→ li a7, 4(SYS_write); li a0, fd; li a1, buf; li a2, len; ecall
  ▼
NEMU 硬件模拟层
  │  执行 ecall 指令：isa_raise_intr(mcause=11, epc=ecall的PC)
  │  跳转到 mtvec = __am_asm_trap（由 cte_init 设置）
  ▼
Abstract-Machine CTE (__am_irq_handle)
  │  mcause=11, a7=4(≠-1) → ev.event = EVENT_SYSCALL
  │  mepc += 4（让 mret 后跳过 ecall 指令，继续执行）
  ▼
nanos-lite irq.c (do_event)
  │  case EVENT_SYSCALL: do_syscall(c)
  ▼
nanos-lite syscall.c (do_syscall)
  │  a[0]=c->GPR1=a7=4=SYS_write
  │  case SYS_write: fs_write(fd, buf, len)
  ▼
nanos-lite fs.c (fs_write)
  │  fd=1(stdout) → file_table[1].write = serial_write
  ▼
nanos-lite device.c (serial_write)
  │  for each byte: putch(c)
  ▼
AM putch → outb(SERIAL_PORT, c) → NEMU 串口设备 → 输出到终端
```
  │
  │  case EVENT_SYSCALL: do_syscall(c)
  ▼
nanos-lite syscall.c (do_syscall)
  │
  │  a[0]=c->GPR1=a7=syscall号
  │  a[1..3]=c->GPR2..4=参数
  │  处理后写 c->GPRx=返回值
  ▼
  (mret 回到用户程序，a0=返回值)
```

---

## 二、寄存器约定 (RISC-V64, M-mode ecall)

| 寄存器 | 宏    | 用途           |
|--------|-------|----------------|
| `a7`   | GPR1  | 系统调用号     |
| `a0`   | GPR2  | 参数0 / 返回值 |
| `a1`   | GPR3  | 参数1          |
| `a2`   | GPR4  | 参数2          |
| `a0`   | GPRx  | 写返回值       |

`mcause=11` 表示 M 模式下的 ecall。AM 的 `yield()` 使用 `a7=-1` 的 ecall 触发
`EVENT_YIELD`，其余 ecall 触发 `EVENT_SYSCALL`。

---

## 三、修改的文件清单

### 3.1 `navy-apps/scripts/riscv/common.mk`

**问题**：工具链前缀不匹配。

```
# 修改前
CROSS_COMPILE = riscv64-linux-gnu-

# 修改后
CROSS_COMPILE = riscv64-unknown-linux-gnu-
```

`/opt/riscv-13.2/bin/` 下的编译器命名为 `riscv64-unknown-linux-gnu-gcc`，
原脚本的 `riscv64-linux-gnu-` 前缀找不到该工具链，导致 `make ISA=riscv64` 报错。

---

### 3.2 `navy-apps/libs/libc/Makefile`

**问题**：新版 GCC 13+ 默认将隐式函数声明当作错误（`-Wimplicit-function-declaration`），
导致 newlib/libc 中的旧式 BSD 代码（如 `getpass.c`）编译失败。

```makefile
# 新增一行
CFLAGS += -Wno-implicit-function-declaration
```

---

### 3.3 `navy-apps/libs/libc/src/syscalls/sysisatty.c`

**问题**：`isatty()` 调用 `_isatty(fd)`，但 GCC 13+ 要求显式声明。

```c
/* 新增前向声明 */
int _isatty(int fd);
```

> `_isatty` 的实现本身在 `navy-apps/libs/libc/src/posix/_isatty.c` 里已经存在，
> 只需声明即可，不要在其他地方重复定义。

---

### 3.4 `navy-apps/libs/libos/src/syscall.c` ★

**问题（最关键）**：libos 里所有 syscall 函数都是占位 stub，实际上调用
`_exit(SYS_xxx)` 退出程序，而不是发出真正的系统调用。

例如：
```c
// 错误的 stub（导致 BAD TRAP）
int _write(int fd, void *buf, size_t count) {
  _exit(SYS_write);   // _exit(4) → halt(4) → HIT BAD TRAP !!!
  return 0;
}
```

**修复**：将所有 stub 替换为真正调用 `_syscall_()` 的实现：

```c
int _write(int fd, void *buf, size_t count) {
  return _syscall_(SYS_write, fd, (intptr_t)buf, count);
}

int _read(int fd, void *buf, size_t count) {
  return _syscall_(SYS_read, fd, (intptr_t)buf, count);
}

int _open(const char *path, int flags, mode_t mode) {
  return _syscall_(SYS_open, (intptr_t)path, flags, mode);
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
```

**`_sbrk` 的特殊处理**：

`_sbrk` 是 newlib `malloc` 的底层接口，用于扩展堆。
实现时不能依赖 `SYS_brk(0)` 查询当前 break（因为 nanos-lite 的 `mm_brk` 总返回 0），
而应使用链接器自动提供的 `_end` 符号（= BSS 段末尾 = 堆的起始地址）：

```c
extern char _end[];          // 链接器定义，指向 BSS 段末尾
static intptr_t _brk_cur = 0;

void *_sbrk(intptr_t increment) {
  if (_brk_cur == 0) {
    _brk_cur = (intptr_t)_end;   // 第一次调用时以 _end 为堆起点
  }
  intptr_t old = _brk_cur;
  if (increment != 0) {
    intptr_t new_brk = _brk_cur + increment;
    if (_syscall_(SYS_brk, new_brk, 0, 0) != 0) {  // 0=成功
      return (void *)-1;   // 失败：返回 -1 (ENOMEM)
    }
    _brk_cur = new_brk;
  }
  return (void *)old;       // 返回扩展前的 break 地址
}
```

---

### 3.5 `nanos-lite/include/fs.h`

新增内核文件系统接口声明：

```c
int      fs_open (const char *pathname, int flags, int mode);
ssize_t  fs_read (int fd, void *buf, size_t len);
ssize_t  fs_write(int fd, const void *buf, size_t len);
int      fs_close(int fd);
intptr_t fs_lseek(int fd, intptr_t offset, int whence);
```

---

### 3.6 `nanos-lite/include/memory.h`

新增 `mm_brk` 声明供 `syscall.c` 使用：

```c
int mm_brk(uintptr_t brk);
```

---

### 3.7 `nanos-lite/src/fs.c` ★

**核心改动**：在 `Finfo` 结构体末尾增加 `open_offset` 字段（追加到末尾，
不影响 `files.h` 的位置初始化），实现完整的文件系统操作。

```c
typedef struct {
  char   *name;
  size_t  size;
  size_t  disk_offset;
  ReadFn  read;        // NULL → 使用 ramdisk
  WriteFn write;       // NULL → 使用 ramdisk；serial_write → 串口输出
  size_t  open_offset; // 当前 seek 位置（追加到末尾，files.h 无需修改）
} Finfo;
```

**文件描述符映射**：

| fd | 名称   | write         | 说明              |
|----|--------|---------------|-------------------|
| 0  | stdin  | invalid_write | 不可写            |
| 1  | stdout | serial_write  | → putch → 串口    |
| 2  | stderr | serial_write  | → putch → 串口    |
| 3+ | 普通文件 | NULL → ramdisk | files.h 生成 |

**`fs_open`**：线性扫描文件表，按文件名匹配，返回下标作为 fd。

**`fs_read/fs_write`**：
- 若 `read/write != NULL`：调用设备回调（如 serial_write）
- 若 == NULL：直接用 `ramdisk_read/ramdisk_write`

**`fs_lseek`**：支持 `SEEK_SET / SEEK_CUR / SEEK_END`，更新 `open_offset`。

---

### 3.8 `nanos-lite/src/device.c`

实现 `serial_write`，将字节逐一通过 AM `putch` 输出到串口：

```c
size_t serial_write(const void *buf, size_t offset, size_t len) {
  const char *p = (const char *)buf;
  for (size_t i = 0; i < len; i++) putch(p[i]);
  return len;
}
```

---

### 3.9 `nanos-lite/src/syscall.c` ★

实现所有内核侧系统调用处理：

```c
switch (a[0]) {
  case SYS_exit:   halt(a[1]);                                  break;
  case SYS_yield:  yield(); c->GPRx = 0;                        break;
  case SYS_open:   c->GPRx = fs_open((char*)a[1], a[2], a[3]); break;
  case SYS_read:   c->GPRx = fs_read(a[1], (void*)a[2], a[3]); break;
  case SYS_write:  c->GPRx = fs_write(a[1],(void*)a[2], a[3]); break;
  case SYS_close:  c->GPRx = fs_close(a[1]);                    break;
  case SYS_lseek:  c->GPRx = fs_lseek(a[1], a[2], a[3]);       break;
  case SYS_brk:    c->GPRx = mm_brk(a[1]);                      break;
  case SYS_getpid: c->GPRx = 0;                                  break;
  case SYS_gettimeofday: {
    uint64_t us = io_read(AM_TIMER_UPTIME).us;
    long *tv = (long *)a[1];
    if (tv) { tv[0] = us/1000000; tv[1] = us%1000000; }
    c->GPRx = 0;
    break;
  }
  case SYS_time:   c->GPRx = io_read(AM_TIMER_UPTIME).us/1e6;  break;
  case SYS_fstat:  c->GPRx = -1;                                 break;
}
```

---

### 3.10 `nanos-lite/src/loader.c` ★

**问题（隐蔽 bug）**：原始 `loader` 函数硬编码从 ramdisk 偏移 0 读取，
**完全忽略 `filename` 参数**：

```c
// 错误：始终读 offset=0，加载第一个文件，filename 形同虚设
ramdisk_read(&ehdr, 0, sizeof(ehdr));
```

这导致：
- 即使 `proc.c` 写 `naive_uload(NULL, "/bin/hello")`，实际加载的也是 ramdisk 第一个文件
- hello 的 `printf` 永远不会被执行，当然看不到输出

**修复**：改用文件系统接口按文件名定位：

```c
#include <fs.h>

static uintptr_t loader(PCB *pcb, const char *filename) {
  int fd = fs_open(filename, 0, 0);   // 按名字找到文件的 disk_offset
  assert(fd >= 0);

  Elf_Ehdr ehdr;
  fs_read(fd, &ehdr, sizeof(ehdr));
  assert(*(uint32_t *)ehdr.e_ident == 0x464c457f);

  Elf_Phdr phdr;
  for (int i = 0; i < ehdr.e_phnum; i++) {
    fs_lseek(fd, ehdr.e_phoff + i * ehdr.e_phentsize, SEEK_SET);
    fs_read(fd, &phdr, sizeof(phdr));
    if (phdr.p_type == PT_LOAD) {
      fs_lseek(fd, phdr.p_offset, SEEK_SET);
      fs_read(fd, (void *)(uintptr_t)phdr.p_vaddr, phdr.p_filesz);
      memset((void *)(uintptr_t)(phdr.p_vaddr + phdr.p_filesz), 0,
             phdr.p_memsz - phdr.p_filesz);
    }
  }
  fs_close(fd);
  return ehdr.e_entry;
}
```

`fs_open("/bin/hello", ...)` 在 `file_table` 中找到 hello 的条目
（`disk_offset = 41528`），后续 `fs_read/fs_lseek` 都基于该偏移进行，
正确加载 hello 的 ELF 到内存。

同时将 `proc.c` 中加载目标改为 `/bin/hello`：
```c
naive_uload(NULL, "/bin/hello");
```

---

### 3.11 软链接问题

nanos-lite 依赖以下两个文件，它们是 navy-apps 构建后生成的：

| nanos-lite 路径 | 链接目标 |
|---|---|
| `src/files.h` | `navy-apps/build/ramdisk.h` |
| `src/syscall.h` | `navy-apps/libs/libos/src/syscall.h` |
| `build/ramdisk.img` | `navy-apps/build/ramdisk.img` |

这些链接由 nanos-lite Makefile 在 `make ARCH=riscv64-nemu` 时通过 `ln -sf` 自动建立，
但如果 `NAVY_HOME` 未设置或 navy-apps 尚未构建，Makefile 会用 `touch` 创建空文件。

**手动修复方法**：
```bash
# 在 nanos-lite 目录下
cd src
ln -sf $NAVY_HOME/libs/libos/src/syscall.h syscall.h
ln -sf $NAVY_HOME/build/ramdisk.h files.h
cd ../build
ln -sf $NAVY_HOME/build/ramdisk.img ramdisk.img
```

或直接在 shell 中 `export NAVY_HOME=...` 后运行 `make ARCH=riscv64-nemu`，
Makefile 会自动处理。

---

## 四、调试过程中的关键线索

### 线索 1：`HIT BAD TRAP`

```
nemu: HIT BAD TRAP at pc = 0x0000000080000ba4
```

`0x80000ba4` 是 nanos-lite 的 `halt()` 函数内的 `ebreak` 指令。
NEMU 规定 `ebreak` 时 `a0 == 0` 为正常退出（GOOD TRAP），`a0 != 0` 为异常（BAD TRAP）。

**触发原因**：用户程序调用 `_write`，但 `_write` 的 stub 执行的是 `_exit(SYS_write)` = `_exit(4)` → `halt(4)` → BAD TRAP。

### 线索 2：`Raising interrupt NO = 11`

这是 NEMU 的 `isa_raise_intr` 函数在每次 ecall 时打印的调试信息，属于正常输出，不是报错。
`NO=11` = RISC-V 异常码 11 = M 模式 ecall。

### 线索 3：`files.h` 为空文件

文件表为空时 `fs_open` 对任何文件名都会 `panic`。
ramdisk.img 为空时加载的是全零数据，ELF magic 校验失败 → `assert` 触发 → halt(1) → BAD TRAP。

### 线索 4：`hello` 有 `printf` 但看不到输出

**根因**：`loader.c` 硬编码从 ramdisk 偏移 0 读取，`filename` 参数被忽略。
无论 `proc.c` 写什么文件名，始终加载第一个文件（`/bin/dummy`）。
dummy 没有 `printf`，自然没有输出。

**修复**：loader 改用 `fs_open/fs_read/fs_lseek`，按文件名在文件表中查找正确偏移，
再从 ramdisk 相应位置加载 ELF（见 §3.10）。

---

加载 `/bin/hello` 后，`make ARCH=riscv64-nemu run` 输出：

```
[nanos-lite] 'Hello World!' from Nanos-lite
[nanos-lite] Initializing processes...
[loader] Jump to entry = 0x8300565c      ← hello 的入口（非 dummy）
Handling syscall...                       ← hello 调用 write(1,...)  → SYS_write
Hello World!                              ← serial_write → putch 输出 ✓
Handling syscall...                       ← printf 内部的 write
Handling syscall...
Hello World from Navy-apps for the 2th time!
Handling syscall...
Hello World from Navy-apps for the 3th time!
...
```

hello 进入无限循环，不会退出（需要 Ctrl+C 终止）。
若想测试正常退出流程，加载 `/bin/dummy`（调用 SYS_yield 后 exit(0) → GOOD TRAP）。

---

## 六、后续扩展方向

| 功能 | 涉及文件 | 说明 |
|---|---|---|
| `/dev/fb` 帧缓冲 | `device.c`, `fs.c` | 实现 `fb_write`，挂载到 `[FD_FB]` |
| `mm_brk` 真实页分配 | `mm.c` | 用 `new_page()` 实际分配物理页 |
| 多程序支持 | `proc.c`, `mm.c` | 开启 `#define MULTIPROGRAM`，实现调度器 |
| 虚拟内存 | `mm.c` | 开启 `#define HAS_VME`，实现页表管理 |
| `fs_open` 文件名匹配失败 | `fs.c` | 改为返回 -1 而非 panic |
