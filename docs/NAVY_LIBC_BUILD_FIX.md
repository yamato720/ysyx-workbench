# navy-apps libc riscv64 编译修复记录

## 背景

navy-apps 中的 `libs/libc` 是一份裁剪版 newlib（Newlib 是面向嵌入式目标的 C 标准库）。
使用 `riscv64-linux-gnu-gcc 15.2.0` 交叉编译时，大量源文件触发了 **implicit declaration** 和 **conflicting types** 等编译错误，原因是：

1. newlib 源码内部大量使用 `_*` 前缀的系统调用包装函数，它们的声明被 `#ifdef _COMPILING_NEWLIB` 保护，只在"正在编译 newlib 本身"时才暴露；
2. POSIX/GNU 扩展函数的声明被 feature-test 宏（`_GNU_SOURCE`、`_POSIX_PRIORITY_SCHEDULING` 等）保护；
3. 部分废弃的 BSD 信号 API（`sigblock`/`sigmask`/`sigsetmask`）在 `riscv64-linux-gnu` 目标上完全不存在。

---

## 修复一览

### 1. `libs/libc/Makefile` — CFLAGS 增加三个宏

**原始 CFLAGS：**
```makefile
CFLAGS = -DNO_FLOATING_POINT -DHAVE_INITFINI_ARRAY
```

**修改后：**
```makefile
CFLAGS = -DNO_FLOATING_POINT -DHAVE_INITFINI_ARRAY -D_COMPILING_NEWLIB -D_GNU_SOURCE -D_NO_GETPASS
```

| 新增宏 | 作用 |
|--------|------|
| `-D_COMPILING_NEWLIB` | 解锁 `_open`、`_close`、`_seekdir`、`_stat`/`_stat64` 等内部声明（在 newlib 头文件中被 `#ifdef _COMPILING_NEWLIB` 保护） |
| `-D_GNU_SOURCE` | 设置 `__GNU_VISIBLE=1`，解锁 `execvpe`、`strtold_l`、`wcwidth` 等 GNU 扩展函数的声明 |
| `-D_NO_GETPASS` | 跳过 `getpass.c` 的函数体（文件已用 `#ifndef _NO_GETPASS` 包裹），该文件使用了废弃的 BSD 信号 API |

---

### 2. `src/posix/posix_spawn.c` — 前向声明 + 替换底层调用

**错误：**
```
implicit declaration of function 'sched_setscheduler'
implicit declaration of function 'sched_setparam'
implicit declaration of function 'execvpe'
implicit declaration of function '_open'
implicit declaration of function '_close'
```

**根本原因：**

- `sched_setscheduler` / `sched_setparam`：在 `sched.h` 中被 `#if defined(_POSIX_PRIORITY_SCHEDULING)` 保护，而该宏仅对 `__rtems__` / `__CYGWIN__` 目标定义；
- `execvpe`：被 `#if __GNU_VISIBLE` 保护（依赖 `_GNU_SOURCE`，但 posix_spawn.c 内部没有设置）；
- `_open` / `_close`：这是 newlib 内部封装，仅在 `_COMPILING_NEWLIB` 宏下声明，不适合直接调用。

**修复：**

在 posix_spawn.c 的 `#include` 块之后添加前向声明，并将 `_open`/`_close` 替换为标准 `open`/`close`：

```c
/* 新增前向声明 */
int sched_setscheduler(pid_t, int, const struct sched_param *);
int sched_setparam(pid_t, const struct sched_param *);
int execvpe(const char *, char * const [], char * const []);
```

```c
/* 替换 _open/_close 为标准调用 */
int fd = open(path, oflag, mode);   /* 原: _open(...) */
close(fd);                          /* 原: _close(fd) */
```

---

### 3. `src/reent/stat64r.c` — `#ifdef __LARGE64_FILES` 条件编译

**错误：**
```
implicit declaration of function '_stat64'
```

**根本原因：**

即使添加了 `-D_COMPILING_NEWLIB`，`_stat64` 的声明还被额外限定为 `#if defined(_COMPILING_NEWLIB) && defined(__LARGE64_FILES)`。`riscv64-linux-gnu` 目标默认不定义 `__LARGE64_FILES`，所以 `_stat64` 声明仍不可见。

**修复：**

在 stat64r.c 中用条件编译包裹 `_stat64` 调用，fallback 到 `_stat`：

```c
#ifdef __LARGE64_FILES
  if ((ret = _stat64 (file, pstat)) == -1 && errno != 0)
#else
  if ((ret = _stat ((const char *)file, (struct stat *)pstat)) == -1 && errno != 0)
#endif
```

---

### 4. `src/stdlib/wcstold.c` — `strtold_l` 前向声明

**错误：**
```
implicit declaration of function 'strtold_l'
```

**根本原因：**

`stdlib.h` 中 `strtold_l` 的声明条件为 `#if __GNU_VISIBLE && defined(_HAVE_LONG_DOUBLE)`。该文件内部已有 `#define _GNU_SOURCE`（满足 `__GNU_VISIBLE`），但 `_HAVE_LONG_DOUBLE` 对该目标未定义（编译器层面使用 double 代替 long double）。

**修复：**

在 wcstold.c 中添加条件前向声明：

```c
#ifndef _HAVE_LONG_DOUBLE
extern long double strtold_l(const char *__restrict, char **__restrict, locale_t);
#endif
```

---

### 5. `src/string/wcwidth.c` — 函数参数类型修正

**错误：**
```
conflicting types for 'wcwidth'; have 'int(const unsigned int)'
```

**根本原因：**

全局添加 `-D_GNU_SOURCE` 后，`wchar.h` 通过 `__XSI_VISIBLE` 暴露了 `wcwidth(const wchar_t)` 的声明（`wchar_t` = `int`），而 wcwidth.c 内定义的参数类型是 `const wint_t`（`wint_t` = `unsigned int`）——有符号/无符号不匹配，导致 conflicting types 错误。

**修复：**

将函数参数类型改为 `wchar_t`，并在函数体内显式转型：

```c
/* 修改前 */
int wcwidth (const wint_t wc) {
    /* 直接使用 wc */
}

/* 修改后 */
int wcwidth (const wchar_t wc) {
    wint_t wi = (wint_t)wc;   /* 显式转型，保持与原逻辑一致 */
    /* 使用 wi */
}
```

---

## 错误分类总结

| 类别 | 受影响文件 | 根本原因 | 修复方式 |
|------|-----------|----------|----------|
| `_COMPILING_NEWLIB` 缺失 | rewinddir.c | `_seekdir` 声明被宏保护 | Makefile 添加 `-D_COMPILING_NEWLIB` |
| `__LARGE64_FILES` 缺失 | stat64r.c | `_stat64` 需要额外宏 | 源码 `#ifdef` 条件分支 |
| `_GNU_SOURCE` 缺失 | posix_spawn.c, wcstold.c | GNU 扩展函数声明不可见 | Makefile 添加 `-D_GNU_SOURCE` + 前向声明 |
| `_GNU_SOURCE` 副作用 | wcwidth.c | XSI 声明与源码定义类型冲突 | 修正参数类型 |
| BSD 废弃 API | getpass.c | `sigblock`/`sigmask`/`tcgetattr` 不存在 | Makefile 添加 `-D_NO_GETPASS` |
| POSIX 扩展宏缺失 | posix_spawn.c | `sched_*` 函数声明仅为 RTEMS/Cygwin | 源码添加前向声明 |

---

## 验证

```bash
cd navy-apps
export NAVY_HOME=$(pwd)

# 单独验证 libc 和 libos 编译/归档成功
make -C libs/libc  ISA=riscv64 archive   # 应无 error:
make -C libs/libos ISA=riscv64 archive   # 应无 error:
```

> **注意：** 直接在 `navy-apps/` 根目录运行 `make ISA=riscv64` 会在最终链接阶段报 `undefined reference to 'main'`，这是因为顶层 Makefile 的 `APP` 变量没有 `NAME` 导致尝试链接一个无入口的可执行文件。这是独立于编译的预存在问题；应从具体应用目录（`apps/*/` 或 `tests/*/`）运行 `make ISA=riscv64` 来构建完整应用。
