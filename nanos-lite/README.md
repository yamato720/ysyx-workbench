# Nanos-lite

Nanos-lite is the simplified version of Nanos (http://cslab.nju.edu.cn/opsystem).
It is ported to the [AM project](https://github.com/NJU-ProjectN/abstract-machine.git).
It is a two-tasking operating system with the following features
* ramdisk device drivers
* ELF program loader
* memory management with paging
* a simple file system
  * with fix number and size of files
  * without directory
  * some device files
* 9 system calls
  * open, read, write, lseek, close, gettimeofday, brk, exit, execve
* scheduler with two tasks

---

## 用户程序加载流程（技术说明）

### 总览

Nanos-lite 是运行在 NEMU 模拟器之上的裸机操作系统（M-mode），通过 AbstractMachine(AM) 提供硬件抽象。用户程序以 ELF 格式打包进 ramdisk，由 OS 在启动时加载并跳转执行。

```
navy-apps/tests/dummy   →  ELF binary
        ↓  手动复制为
nanos-lite/build/ramdisk.img
        ↓  编译时通过 resources.S 嵌入
nanos-lite 可执行文件
        ↓  在 NEMU 上运行
OS 启动 → init_proc() → naive_uload() → loader() → 跳转到 entry
```

---

### 第一步：编译用户程序

用户程序位于 `navy-apps/`，使用独立的工具链编译，链接地址约定为：

| ISA     | 链接地址       |
|---------|--------------|
| x86     | `0x3000000`  |
| mips32  | `0x83000000` |
| riscv32/64 | `0x83000000` |

链接脚本由 `navy-apps/scripts/$ISA.mk` 中的 `LDFLAGS` 指定。

```bash
cd navy-apps/tests/dummy
make ISA=riscv64
cp build/dummy-riscv64 ../../nanos-lite/build/ramdisk.img
```

---

### 第二步：ramdisk 嵌入

`nanos-lite/src/resources.S` 通过汇编 `.incbin` 指令将 `build/ramdisk.img` 直接嵌入到 Nanos-lite 的 data 段，暴露为两个符号：

```
ramdisk_start  ←  文件内容起始
ramdisk_end    ←  文件内容结束
```

当前 ramdisk 只有一个文件（即 dummy ELF），偏移为 0。

---

### 第三步：ELF loader（`src/loader.c`）

`loader()` 从 ramdisk 偏移 0 处读取 ELF 文件，将所有 `PT_LOAD` 段复制到对应的虚拟地址，BSS 区域清零，最后返回程序入口地址 `e_entry`。

```c
static uintptr_t loader(PCB *pcb, const char *filename) {
    // 1. 读 ELF header，校验 magic
    // 2. 遍历 program header，找 PT_LOAD 段
    // 3. ramdisk_read → 目标虚拟地址
    // 4. memset 清零 (memsz - filesz) 的 BSS 部分
    // 5. 返回 ehdr.e_entry
}
```

`naive_uload()` 调用 `loader()` 后直接函数跳转到 entry，不保存返回地址：

```c
void naive_uload(PCB *pcb, const char *filename) {
    uintptr_t entry = loader(pcb, filename);
    ((void(*)())entry)();
}
```

`init_proc()` 中触发加载：

```c
void init_proc() {
    switch_boot_pcb();
    naive_uload(NULL, "/bin/dummy");
}
```

---

### 第四步：系统调用分发

#### 问题根因

RISC-V 中，`yield()` 和用户 `ecall` 均触发 `mcause = 11`（M-mode Environment Call）。原始代码将 mcause=11 一律映射为 `EVENT_YIELD`，导致用户程序的 syscall 被误当成 yield 处理，陷入无限循环。

#### 解决方案：通过 `a7` 寄存器区分

AM 的 `yield()` 约定：
```asm
li a7, -1
ecall        # a7 = -1 表示 yield
```

用户 syscall 约定：
```c
// a7 = syscall 号（非 -1）
_syscall_(SYS_yield, 0, 0, 0);  // a7 = 1
```

`abstract-machine/am/src/riscv/nemu/cte.c` 中的修改：

```c
case 11:
    if (c->GPR1 == (uintptr_t)-1) {
        ev.event = EVENT_YIELD;
    } else {
        ev.event = EVENT_SYSCALL;
    }
    c->mepc += 4;
    break;
```

#### syscall 处理链

```
用户 ecall
  → __am_irq_handle() (cte.c)
    → EVENT_SYSCALL
      → do_event() (irq.c)
        → do_syscall() (syscall.c)
          → switch(a7): SYS_yield → yield(); return 0;
```

#### 目前实现的 syscall

| 编号 | 名称      | 行为          |
|------|-----------|---------------|
| 0    | SYS_exit  | （待实现）    |
| 1    | SYS_yield | 调用 yield()，返回 0 |

---

### 关键文件索引

| 文件 | 作用 |
|------|------|
| `src/loader.c` | ELF 解析与段加载 |
| `src/proc.c` | 进程初始化，调用 naive_uload |
| `src/irq.c` | 异常/中断事件分发 |
| `src/syscall.c` | 系统调用实现 |
| `src/syscall.h` | syscall 编号枚举 |
| `src/resources.S` | ramdisk 嵌入 |
| `src/ramdisk.c` | ramdisk 读写接口 |
| `abstract-machine/am/src/riscv/nemu/cte.c` | CTE 异常处理，yield/syscall 区分 |

