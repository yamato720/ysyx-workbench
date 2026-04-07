# Nanos-lite 系统调用实现文档

## 架构概览

```
用户程序 (navy-apps)
  └─ libos: _write(), _open(), ... → _syscall_(SYS_xxx, a0, a1, a2)
                                         │ ecall (RISC-V a7/a0/a1/a2)
                                         ▼
nanos-lite 内核
  └─ irq.c → do_syscall(Context *c)
       └─ fs.c   fs_open / fs_read / fs_write / fs_close / fs_lseek
       └─ mm.c   mm_brk
       └─ device.c  serial_write → putch
```

---

## 寄存器约定 (RISC-V64)

| 寄存器 | 宏     | 含义             |
|--------|--------|------------------|
| a7     | GPR1   | 系统调用号       |
| a0     | GPR2   | 参数 0 / 返回值  |
| a1     | GPR3   | 参数 1           |
| a2     | GPR4   | 参数 2           |
| a0     | GPRx   | 写返回值         |

`do_syscall` 从 `Context *c` 读取这些寄存器，处理完后将返回值写回 `c->GPRx`。

---

## 系统调用表

| 编号 | 名称               | 用户侧函数       | 内核实现           | 说明                                 |
|------|--------------------|------------------|--------------------|--------------------------------------|
| 0    | SYS_exit           | `_exit(status)`  | `halt(a1)`         | 终止程序，传递退出码给 AM             |
| 1    | SYS_yield          | —                | `yield()`          | 主动让出 CPU，返回 0                  |
| 2    | SYS_open           | `_open(path, flags, mode)` | `fs_open()` | 按文件名查表，返回 fd（表下标）       |
| 3    | SYS_read           | `_read(fd, buf, n)` | `fs_read()`    | 从 fd 读取数据到 buf                  |
| 4    | SYS_write          | `_write(fd, buf, n)` | `fs_write()`  | 向 fd 写数据（stdout/stderr→串口）   |
| 5    | SYS_kill           | —                | —（未使用）        | 未实现                               |
| 6    | SYS_getpid         | —                | 返回常量 0         | 单进程，始终返回 0                    |
| 7    | SYS_close          | `_close(fd)`     | `fs_close()`       | 重置 seek 偏移，返回 0               |
| 8    | SYS_lseek          | `_lseek(fd, off, whence)` | `fs_lseek()` | 移动文件偏移指针                    |
| 9    | SYS_brk            | `_sbrk()`        | `mm_brk(brk)`      | 堆扩展（当前 stub，始终返回 0）       |
| 10   | SYS_fstat          | `_fstat()`       | 返回 -1            | 未实现，返回错误                      |
| 11   | SYS_time           | —                | AM `TIMER_UPTIME`  | 返回运行秒数                         |
| 12   | SYS_signal         | —                | —（未使用）        | 未实现                               |
| 13   | SYS_execve         | `_execve()`      | —（未使用）        | 未实现                               |
| 14   | SYS_fork           | —                | —（未使用）        | 未实现                               |
| 15   | SYS_link           | —                | —（未使用）        | 未实现                               |
| 16   | SYS_unlink         | —                | —（未使用）        | 未实现                               |
| 17   | SYS_wait           | —                | —（未使用）        | 未实现                               |
| 18   | SYS_times          | —                | —（未使用）        | 未实现                               |
| 19   | SYS_gettimeofday   | `_gettimeofday(tv, tz)` | AM `TIMER_UPTIME` | 填写 timeval 结构体         |

---

## 文件系统实现细节 (`nanos-lite/src/fs.c`)

### Finfo 结构体

```c
typedef struct {
  char   *name;         // 文件路径（绝对路径）
  size_t  size;         // 文件大小（字节）
  size_t  disk_offset;  // 在 ramdisk 中的起始偏移
  ReadFn  read;         // 设备读取回调（NULL → 使用 ramdisk）
  WriteFn write;        // 设备写入回调（NULL → 使用 ramdisk）
  size_t  open_offset;  // 当前 seek 位置（运行时）
} Finfo;
```

### 文件描述符分配

| fd  | 名称    | read          | write          |
|-----|---------|---------------|----------------|
| 0   | stdin   | invalid_read  | invalid_write  |
| 1   | stdout  | invalid_read  | serial_write   |
| 2   | stderr  | invalid_read  | serial_write   |
| 3+  | /dev/fb | fb_read (TODO)| fb_write (TODO)|
| N+  | 普通文件| NULL→ramdisk  | NULL→ramdisk   |

普通文件条目来自 `files.h`（由 navy-apps 构建生成，符号链接到 `navy-apps/build/ramdisk.h`）。

### fs_open

```c
int fs_open(const char *pathname, int flags, int mode);
```
线性扫描 `file_table`，找到同名条目后将 `open_offset` 清零并返回下标作为 fd。
找不到则 `panic`（nanos-lite 不支持动态创建文件）。

### fs_read

```c
ssize_t fs_read(int fd, void *buf, size_t len);
```
- **设备文件**（`read != NULL`）：调用 `f->read(buf, open_offset, len)`
- **普通文件**：调用 `ramdisk_read`，读取范围自动截断到文件大小

两种情况都会推进 `open_offset`。

### fs_write

```c
ssize_t fs_write(int fd, const void *buf, size_t len);
```
- **stdout / stderr**：`write = serial_write`，逐字节调用 `putch()` 输出到串口
- **普通文件**：调用 `ramdisk_write`

### fs_close

```c
int fs_close(int fd);
```
将 `open_offset` 重置为 0，返回 0。
（nanos-lite 为单进程，不需要真正释放资源。）

### fs_lseek

```c
intptr_t fs_lseek(int fd, intptr_t offset, int whence);
```

| whence    | 计算方式                         |
|-----------|----------------------------------|
| SEEK_SET  | `open_offset = offset`           |
| SEEK_CUR  | `open_offset += offset`          |
| SEEK_END  | `open_offset = size + offset`    |

返回新的 `open_offset`。

---

## 时间相关系统调用

`SYS_gettimeofday` 和 `SYS_time` 均通过 AM 接口读取系统上电后的运行时间：

```c
uint64_t us = io_read(AM_TIMER_UPTIME).us;  // 微秒
tv->tv_sec  = us / 1000000;
tv->tv_usec = us % 1000000;
```

这是 **相对时间**（从启动算起），并非真实 UTC 时间，但对 libc 内部的计时操作（如 `clock()` 等）足够使用。

---

## 堆管理 (`SYS_brk`)

```c
// mm.c
int mm_brk(uintptr_t brk) { return 0; }  // stub：暂不实际分配
```

当前实现始终返回 0（成功），不实际移动堆指针。
`newlib` 的 `malloc` 通过 `_sbrk()` → `SYS_brk` 请求堆空间。如需支持 `malloc`，需要在此实现真实的页分配逻辑（参考 `new_page()` / `mm.c`）。

---

## 未实现的系统调用

以下系统调用在 nanos-lite 中无需实现（navy-apps 的 libos 将它们定义为 `assert(0)` 或直接 `_exit`）：

- `SYS_kill`, `SYS_signal` — 无信号机制
- `SYS_fork`, `SYS_wait`, `SYS_execve` — 无多进程
- `SYS_link`, `SYS_unlink` — ramdisk 只读
- `SYS_times` — 未使用

如果未来调用到未处理的 syscall，`do_syscall` 会执行 `panic`，方便调试。

---

## 扩展指引

### 添加新设备文件（如 `/dev/fb` 帧缓冲）

1. 在 `device.c` 实现 `fb_write`（将像素数据通过 `io_write(AM_GPU_FBDRAW, ...)` 写入）
2. 在 `fs.c` 的 `file_table` 中更新 `[FD_FB]` 条目指向 `fb_write`
3. 在 `init_fs()` 中读取 `AM_GPU_CONFIG` 初始化 `/dev/fb` 的 size

### 支持真实 `malloc`（实现 `mm_brk`）

```c
int mm_brk(uintptr_t brk) {
  // 如果 brk > 当前 program break，分配新页
  // 使用 new_page() 从 heap 分配物理页
  return 0;
}
```
