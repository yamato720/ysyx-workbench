# ELF 加载与 dummy 程序详解

## 一、ramdisk.img 是什么？它怎么进入 nanos-lite？

### 1.1 打包过程

`ramdisk.img` 就是 `dummy`（或其他用户程序）编译出来的 ELF 文件，直接被 nanos-lite **硬编码进内核二进制**：

```asm
# nanos-lite/src/resources.S
.section .data
.global ramdisk_start, ramdisk_end
ramdisk_start:
.incbin "build/ramdisk.img"    ← 把整个 ELF 文件字节原样嵌入 .data 段
ramdisk_end:
```

`.incbin` 是汇编器指令，相当于把文件内容直接粘贴成字节数组。  
编译完成后，`ramdisk.img` 的全部内容就存在 nanos-lite 的 `.data` 段里，
`ramdisk_start` 和 `ramdisk_end` 是两个符号，标记其在内存中的起止地址。

---

## 二、用 readelf 分析 ramdisk.img

> **前提**：ramdisk.img 本身就是一个完整的 ELF，可以直接用 `readelf` 分析。

```bash
# 以实际文件为例
ELF=nanos-lite/build/ramdisk.img
```

### 2.1 ELF 文件头（`-h`）

```bash
$ readelf -h $ELF
```

```
ELF Header:
  Magic:   7f 45 4c 46 02 01 01 00 ...    ← 魔数：0x7f 'E' 'L' 'F'，64-bit，小端
  Class:   ELF64
  Type:    EXEC (Executable file)         ← 可执行文件（非共享库 .so）
  Machine: RISC-V
  Entry point address: 0x83000468        ← 程序入口点，OS 跳到这里启动程序
  Number of program headers: 4           ← 4 个段（segment）
  Number of section headers: 13          ← 13 个节（section）
```

**关键字段**：
- `Magic`：前 4 字节必须是 `7f 45 4c 46`（即 `\x7fELF`）。loader 用它验证这确实是 ELF。
- `Entry point`：程序运行的第一条指令的地址，loader 最后会跳到这里。
- `e_phnum`：Program Header（段）的数量，loader 要遍历它们。

### 2.2 程序头（Segment）（`-l`）——Loader 关心的

```bash
$ readelf -l $ELF
```

```
Program Headers:
  Type      Offset     VirtAddr           PhysAddr
            FileSiz    MemSiz             Flags  Align

  LOAD      0x000000   0x83000000         0x83000000
            0x63d4     0x63d4             R E    0x1000   ← 代码段，只读+可执行
  LOAD      0x007000   0x83007000         0x83007000
            0x0fd8     0x1028             RW     0x1000   ← 数据段，可读写
```

**Loader 只关心 `PT_LOAD` 类型的段**，其余（`RISCV_ATTRIBUTE`、`GNU_STACK`）是元数据。

每个 `LOAD` 段的关键字段：

| 字段 | 含义 |
|---|---|
| `Offset` | 在 ELF 文件中的偏移（从文件头算起） |
| `VirtAddr` | 加载到内存的目标虚地址 |
| `FileSiz` | 文件中实际存储的字节数 |
| `MemSiz` | 在内存中占用的字节数（≥ FileSiz，差值是 `.bss`） |
| `Flags` | `R`=可读，`W`=可写，`E`=可执行 |

> **FileSiz < MemSiz** 的部分是 `.bss`（未初始化全局变量）：文件里不存储，加载时用 `memset` 清零。

### 2.3 节头（Section）（`-S`）——链接器关心的

```bash
$ readelf -S $ELF
```

```
  [Nr] Name       Type     Address          Offset   Size
  [ 1] .text      PROGBITS 0x83000120       0x120    0x50c4   AX  ← 代码
  [ 2] .rodata    PROGBITS 0x830051e8       0x51e8   0x260    A   ← 只读数据（字符串等）
  [ 3] .eh_frame  PROGBITS 0x83005448       0x5448   0xf8c    A   ← 异常展开信息
  [ 4] .data      PROGBITS 0x83007000       0x7000   0xf58    WA  ← 已初始化全局变量
  [ 5] .sdata     PROGBITS 0x83007f58       0x7f58   0x80     WA  ← 小数据段（RISC-V 优化）
  [ 6] .sbss      NOBITS   0x83007fd8       ...      0x24     WA  ← 小 BSS（NOBITS：文件中无内容）
  [ 7] .bss       NOBITS   0x83008000       ...      0x28     WA  ← 未初始化全局变量
```

**Flags 含义**：`A`=运行时分配内存，`X`=可执行，`W`=可写

> Section 与 Segment 的关系：多个 Section 被打包进一个 Segment。
> 链接时 ld 关心 Section；运行时 OS 关心 Segment（更粗粒度）。

### 2.4 符号表（`-s`）——调试用

```bash
$ readelf -s $ELF | grep -E "main|_start|_syscall_"
```

```
169: 0x83000138   24 FUNC  GLOBAL  _syscall_
175: 0x83000470   52 FUNC  GLOBAL  call_main
177: 0x83000468    0 NOTYPE GLOBAL _start          ← 入口点！
192: 0x83007fd8    0 NOTYPE GLOBAL __bss_start
194: 0x83000120   24 FUNC  GLOBAL  main            ← dummy 的 main
```

可以看到 `_start`（`0x83000468`）正好等于 ELF header 里的 `Entry point address`。

### 2.5 其他常用 readelf 选项

```bash
readelf -a $ELF          # 显示所有信息（等于 -h -l -S -s -r -d）
readelf -x .rodata $ELF  # 十六进制 dump 某个 section
readelf -p .rodata $ELF  # 字符串 dump 某个 section
objdump -d $ELF          # 反汇编代码段（比 readelf 更直观）
```

---

## 三、nanos-lite 是如何加载并运行 dummy 的

### 3.1 完整流程

```
nanos-lite main()
  │
  ├─ init_ramdisk()    打印 ramdisk 在内存中的位置（.data 段里的字节数组）
  ├─ init_irq()        注册 trap handler（写 mtvec）
  ├─ init_proc()
  │     └─ naive_uload(NULL, "/bin/dummy")
  │           └─ loader(pcb, filename)
  │                 │
  │                 ├─ ① 读 ELF Header：ramdisk_read(&ehdr, 0, sizeof(Elf64_Ehdr))
  │                 │   验证 Magic: assert(*(uint32_t*)ehdr.e_ident == 0x464c457f)
  │                 │
  │                 ├─ ② 遍历 Program Headers（ehdr.e_phnum = 4 个）
  │                 │   for i in range(e_phnum):
  │                 │     ramdisk_read(&phdr, e_phoff + i*e_phentsize, sizeof(Phdr))
  │                 │     if phdr.p_type == PT_LOAD:
  │                 │       ③ 拷贝文件内容到目标地址：
  │                 │          ramdisk_read(phdr.p_vaddr, phdr.p_offset, phdr.p_filesz)
  │                 │       ④ BSS 清零：
  │                 │          memset(phdr.p_vaddr + phdr.p_filesz, 0,
  │                 │                 phdr.p_memsz - phdr.p_filesz)
  │                 │
  │                 └─ ⑤ 返回入口点：return ehdr.e_entry  (= 0x83000468)
  │
  └─ ((void(*)()) entry)()    ← 直接函数调用跳转到 _start
```

### 3.2 loader 源码对照

```c
// nanos-lite/src/loader.c
static uintptr_t loader(PCB *pcb, const char *filename) {
  Elf64_Ehdr ehdr;
  ramdisk_read(&ehdr, 0, sizeof(ehdr));
  assert(*(uint32_t *)ehdr.e_ident == 0x464c457f); // 检查 ELF magic

  Elf64_Phdr phdr;
  for (int i = 0; i < ehdr.e_phnum; i++) {
    ramdisk_read(&phdr,
                 ehdr.e_phoff + i * ehdr.e_phentsize,
                 sizeof(phdr));
    if (phdr.p_type == PT_LOAD) {
      // 把文件中的内容拷贝到虚地址（Naive OS 中虚地址 == 物理地址）
      ramdisk_read((void *)phdr.p_vaddr, phdr.p_offset, phdr.p_filesz);
      // bss 清零
      memset((void *)(phdr.p_vaddr + phdr.p_filesz), 0,
             phdr.p_memsz - phdr.p_filesz);
    }
  }
  return ehdr.e_entry;
}
```

### 3.3 加载后的内存布局（以 ramdisk.img 为例）

```
物理内存（本项目中虚地址 = 物理地址）：

0x83000000  ┌─────────────────────────────┐  ← LOAD segment 1 起始
            │  .text  (代码)  0x50c4 字节  │  0x83000120
            │  .rodata (只读数据)           │  0x830051e8
            │  .eh_frame                   │  0x83005448
0x83006000  └─────────────────────────────┘  ← LOAD segment 1 结束（对齐 4KB）
0x83007000  ┌─────────────────────────────┐  ← LOAD segment 2 起始
            │  .data  (已初始化全局变量)    │
            │  .sdata                      │
            │  .sbss  ← memset 清零        │  0x83007fd8
            │  .bss   ← memset 清零        │  0x83008000
0x83009000  └─────────────────────────────┘

0x83000468  ← _start（程序入口）：loader 跳到这里
```

### 3.4 _start → call_main → main 的调用链

```
_start (start.S):
  mv s0, zero          # 清帧指针
  la sp, _stack_pointer # 设置栈指针
  call _trm_init
  
_trm_init → call_main:
  environ = {NULL};
  exit(main(0, empty, empty));   ← 调用 dummy 的 main

dummy main():
  return _syscall_(SYS_yield, 0, 0, 0)
  ↓
  a7=1, ecall
  ↓
  nanos-lite do_syscall → case SYS_yield: yield()
```

---

## 四、dummy 是什么？名字从哪来？

### 4.1 代码全文

```c
// navy-apps/tests/dummy/dummy.c
#include <stdint.h>
#define SYS_yield 1
extern int _syscall_(int, uintptr_t, uintptr_t, uintptr_t);

int main() {
  return _syscall_(SYS_yield, 0, 0, 0);
}
```

就这么多。它做的事情只有一件：**发出一个 `SYS_yield` 系统调用，然后退出**。

### 4.2 dummy 的历史角色

"dummy"在英文里是**占位符、假的、最小化的**意思——就是用来填一个位置，验证机制能通的最小存在。

在这个项目里，dummy 在历史上有几个具体用途：

**1. 验证 ELF 加载器能工作**  
当你刚写完 `loader.c` 时，你需要一个最简单的用户程序来测试"OS 能不能把 ELF 加载到内存并跳转执行"。dummy 就是那个程序——它几乎不做任何事，只要能跑起来就说明 loader 正确。

**2. 验证系统调用通路**  
dummy 调用了 `SYS_yield`，这是整个系统调用框架中最简单的一条路：用户程序 → ecall → trap handler → nanos-lite → yield()。跑通 dummy 说明这条链路完整。

**3. 作为 ramdisk 的"占位程序"**  
在 nanos-lite 还没有文件系统、没有其他用户程序时，ramdisk 里放的就是 dummy。它是 OS 能加载并运行的第一个"用户程序"。后续进度解锁后，ramdisk 里会换成 `hello`、`pal`、`fceux` 等真正的程序。

### 4.3 为什么叫 dummy 而不叫 hello

`hello` 是"我能输出字符串"，需要 `write` 系统调用实现。  
`dummy` 是"我什么都不做，只证明自己能被加载运行"，连输出都没有。

在测试驱动开发的语境中，**dummy** 专指"为了让编译/链接/系统通过，随手填进去的最小实现"——它不测功能，它测框架本身是否活着。

### 4.4 dummy 在整个项目中的出现规律

```
每当你在某个层引入新机制时，就需要一个 dummy 来走通第一步：

阶段                新机制                需要验证的事
─────────────────────────────────────────────────────
am-kernels 测试     AM 接口 + NEMU 执行    程序能在模拟器上跑
nanos-lite 初期     ELF 加载 + 系统调用    OS 能加载并运行用户程序  ← dummy
nanos-lite 进阶     文件系统 + 多进程       real apps (hello, pal...)
```

dummy 是你每个新阶段的"Hello World 前的 Hello World"。

---

## 五、NEMU 的 ELF 符号解析与函数追踪（FTRACE）

NEMU 利用同样的 ELF 符号表信息，在仿真时实时追踪函数调用与返回。

### 5.1 用法

```bash
# 启动 NEMU 时用 --elf 指定要分析的 ELF 文件
./nemu/build/riscv64-nemu-interpreter --elf nanos-lite/build/ramdisk.img <image>
```

NEMU 的 `monitor.c` 会在启动时解析 `--elf` 参数并调用 `analyze_elf()`：

```c
// nemu/src/monitor/monitor.c
{"elf", required_argument, NULL, 'e'},
...
case 'e': analyze_elf(optarg); break;
```

### 5.2 `analyze_elf()` 的工作原理

`nemu/src/monitor/sdb/elfreader.c` 实现了完整的 ELF Section 解析：

```
ELF 文件
  │
  ├─ ① 读 ELF Header → 拿到 e_shoff（Section Header 表偏移）和 e_shstrndx
  ├─ ② 读全部 Section Headers
  ├─ ③ 读 .shstrtab（节名字符串表）→ 找到各节的名字
  ├─ ④ 遍历节名，定位 .symtab 和 .strtab
  ├─ ⑤ 读取 .symtab（Elf64_Sym 数组）
  ├─ ⑥ 读取 .strtab（符号名字符串表）
  └─ ⑦ 过滤 STT_FUNC 类型且 st_size>0 的符号 → 填入 func_table[]
```

每条 `func_table` 记录包含：

```c
typedef struct func_info {
  char     name[32];       // 函数名（来自 .strtab）
  uint64_t entry_addr;     // 函数起始地址（st_value）
  uint64_t exit_addr;      // 最后一条指令地址（entry + size - 4）
  uint64_t size;           // 函数字节大小（st_size）
} func_info;
```

### 5.3 ramdisk.img 的实际输出（节选）

运行后 NEMU 打印所有函数符号表，以 `dummy` 相关的关键函数为例：

```
=== Function Symbols ===
Idx  Name                 Entry              Exit               Size
----------------------------------------------------------------------
48   _syscall_            0x0000000083000138 0x000000008300014c 24
...
173  _syscall_            0x0000000083000138  24
179  call_main            0x0000000083000470  52
198  main                 0x0000000083000120  24     ← dummy 的 main
236  _exit                0x0000000083000150  20

Total functions found: 109   ← ramdisk.img（dummy + libos + libc）共含 109 个函数
```

对照 ELF 文件头的 `Entry point: 0x83000468`（即 `_start`），可以验证：
- `main` 在 `0x83000120`，大小 24 字节（6 条 RISC-V 指令）
- `_syscall_` 在 `0x83000138`，刚好是 `main` 后面 `0x18` 字节处
- `call_main` → `main` → `_syscall_` 的调用链地址完全吻合

### 5.4 仿真时的函数追踪：`check_ftrace()`

每执行一条指令，`cpu-exec.c` 的主循环都调用 `check_ftrace()`：

```c
// nemu/src/cpu/cpu-exec.c（主执行循环）
#if defined(CONFIG_FTRACE) || defined(CONFIG_MY_TRACE)
check_ftrace(cpu.pc, s.isa.inst);
#endif
```

`check_ftrace()` 做两件事：

**① 检测函数入口**（PC 命中 `func_table` 中某个 `entry_addr`）：
```c
if (is_func_entry(pc)) {
  // 压入 ftrace_stack，记录函数名、调用深度
  ftrace_stack[ftrace_stack_idx].type  = 0;  // entry
  ftrace_stack[ftrace_stack_idx].depth = deepest_ftrace_depth++;
}
```

**② 检测 `ret` 指令**（即 `jalr x0, 0(ra)`，编码为 opcode=0x67, rd=0, rs1=1, imm=0）：
```c
if (opcode==0x67 && funct3==0 && rd==0 && rs1==1 && imm==0) {
  ftrace_stack[ftrace_stack_idx].type  = 1;  // exit
  ftrace_stack[ftrace_stack_idx].depth = --deepest_ftrace_depth;
}
```

### 5.5 `display_ftrace()` 的输出格式

程序结束后调用 `display_ftrace()`，输出缩进的调用树：

```
==== Function Trace ====
--> call_main                    (0x83000470)
  --> main                       (0x83000120)    ← dummy 的 main
    --> _syscall_                 (0x83000138)    ← ecall SYS_yield
    <-- _syscall_                 (0x83000148)
  <-- main                       (0x83000128)
  --> _exit                      (0x83000150)    ← main 返回后 call_main 调 exit
========================
```

缩进层数 = `depth` 字段；`-->` 是入口，`<--` 是出口（`ret` 指令处）。

### 5.6 为什么用 Section 而不用 Program Header 来找符号表

| | Program Header（Segment） | Section Header |
|---|---|---|
| 用途 | 运行时加载，告诉 OS 如何映射内存 | 链接/调试，描述内容的逻辑分类 |
| 包含符号表吗 | 否（`.symtab` 是调试信息，不需要加载到内存） | 是（`.symtab` + `.strtab`） |
| strip 后还在吗 | 段依然存在 | `.symtab` 被删除（`strip` 命令） |

`analyze_elf()` 走 Section Header 路径是正确的选择——符号表是 Section 级别的信息，只有 `.symtab` 节里才有函数名和大小，Program Header 无法提供这些。

---

## 六、快速参考：readelf 常用选项

| 命令 | 用途 |
|---|---|
| `readelf -h <elf>` | ELF 文件头（架构、入口点、段/节数量） |
| `readelf -l <elf>` | Program Headers（Segment，Loader 用） |
| `readelf -S <elf>` | Section Headers（Section，链接器/调试用） |
| `readelf -s <elf>` | 符号表（函数/变量地址、大小） |
| `readelf -r <elf>` | 重定位表（.o 文件中的待填地址） |
| `readelf -d <elf>` | 动态节（.so 的依赖库信息） |
| `readelf -x <section> <elf>` | 十六进制 dump 某节 |
| `readelf -p <section> <elf>` | 字符串 dump 某节 |
| `readelf -a <elf>` | 以上所有 |
| `objdump -d <elf>` | 反汇编（可读性比 readelf 好） |
| `objdump -S <elf>` | 反汇编 + 源码交叉显示（需 -g 编译） |

---

## 七、参考文件

- `nanos-lite/src/resources.S` — `.incbin` 把 ramdisk.img 嵌入内核
- `nanos-lite/src/loader.c` — `loader()` ELF 加载实现
- `nanos-lite/src/ramdisk.c` — `ramdisk_read()`
- `nanos-lite/src/proc.c` — `init_proc()` 调用 `naive_uload("/bin/dummy")`
- `navy-apps/tests/dummy/dummy.c` — dummy 程序源码
- `navy-apps/libs/libos/src/syscall.c` — `_syscall_()` 实现
- `nemu/src/monitor/sdb/elfreader.c` — `analyze_elf()`、`check_ftrace()`、`display_ftrace()`
- `nemu/src/cpu/cpu-exec.c` — 主循环中调用 `check_ftrace()`
- `nemu/src/monitor/monitor.c` — `--elf` 命令行参数解析

