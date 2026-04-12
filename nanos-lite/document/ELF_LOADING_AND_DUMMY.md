# nanos-lite 加载程序实现梳理

这篇文档总结当前仓库里 `nanos-lite` 的**用户程序加载路径**。重点不是 ELF 格式本身，而是：

- 程序镜像是怎么进入 `nanos-lite` 的；
- 运行时数据如何沿着 `ramdisk -> 文件系统 -> loader -> entry` 流动；
- 涉及哪些关键函数，它们各自负责什么；
- 当前实现和“真正的进程加载 / execve / 虚拟内存”之间还有哪些差距。

当前代码对应的结论可以先记成一句话：

> `nanos-lite` 现在使用的是一个 **naive ELF loader**：从 `ramdisk` 中找到目标 ELF，经过 `fs_*` 接口读取 ELF header 和 `PT_LOAD` 段，把段内容直接拷贝到 `p_vaddr` 指定的地址，清零 `.bss`，最后直接调用 ELF 入口地址。

---

## 一、先看全局数据通路

从构建到运行，完整数据通路如下：

```text
navy-apps/build/ramdisk.img
  -> nanos-lite/build/ramdisk.img  (Makefile update 建立软链接)
  -> nanos-lite/src/resources.S    (.incbin 嵌入内核镜像)
  -> ramdisk_start ~ ramdisk_end   (运行时内存中的 ramdisk 字节区)
  -> ramdisk_read()                (按 offset 从内存拷贝)
  -> fs_read()                     (根据文件表定位到某个文件)
  -> loader()                      (读取 ELF header / program header / segment)
  -> memcpy 到 phdr.p_vaddr
  -> memset 清零 bss
  -> ehdr.e_entry
  -> ((void(*)())entry)()
```

也就是说，**真正执行加载动作的不是 ramdisk 本身，而是 `loader()`；真正提供字节流的不是 ELF 文件，而是嵌入内核镜像中的 `ramdisk.img`；中间由 `fs_*` 做了一层“按文件名访问”的抽象。**

---

## 二、程序镜像是怎么进入 nanos-lite 的

### 2.1 `ramdisk.img` 的来源

`nanos-lite/Makefile` 负责把 `navy-apps` 生成的 ramdisk 接进来：

- `nanos-lite/Makefile:40-44`

关键规则：

```make
update:
	$(MAKE) -s -C $(NAVY_HOME) ISA=$(ISA) ramdisk
	@ln -sf $(NAVY_HOME)/build/ramdisk.img $(RAMDISK_FILE)
	@ln -sf $(NAVY_HOME)/build/ramdisk.h src/files.h
```

这里做了两件事：

1. 让 `navy-apps` 重新打包 `build/ramdisk.img`
2. 把生成出来的：
   - `ramdisk.img`
   - `ramdisk.h`
   
   链接到 `nanos-lite` 目录中

其中：

- `build/ramdisk.img`：真正的字节镜像
- `src/files.h`：ramdisk 中文件的元信息表，后面会被文件系统直接 `#include`

### 2.2 `ramdisk.img` 如何嵌入内核

`nanos-lite/src/resources.S`：

- `nanos-lite/src/resources.S:1-5`

```asm
.section .data
.global ramdisk_start, ramdisk_end
ramdisk_start:
.incbin "build/ramdisk.img"
ramdisk_end:
```

`.incbin` 会把 `build/ramdisk.img` 的全部字节直接放进最终的内核镜像里。这样运行时：

- `ramdisk_start` 指向镜像起始地址
- `ramdisk_end` 指向镜像结束地址

所以，`ramdisk` 本质上就是**内核镜像中的一块连续字节数组**。

---

## 三、系统启动后，谁触发了加载

### 3.1 `main()` 是怎么跑起来的

AM 平台启动代码会进入 `nanos-lite` 的 `main()`：

- NEMU 平台：`abstract-machine/am/src/platform/nemu/trm.c:21-23`
- NPC 平台：`abstract-machine/am/src/riscv/npc/trm.c:21-23`

对应逻辑都很直接：

```c
void _trm_init() {
  int ret = main(mainargs);
  halt(ret);
}
```

### 3.2 `nanos-lite` 初始化顺序

`nanos-lite/src/main.c:10-37`

```c
int main() {
  ...
  init_mm();
  init_device();
  init_ramdisk();
  init_irq();
  init_fs();
  init_proc();
  ...
}
```

和加载程序相关的关键点是：

- `init_ramdisk()`：确认 ramdisk 在内存中的范围
- `init_fs()`：初始化文件系统抽象
- `init_proc()`：选择要运行的用户程序并触发加载

---

## 四、用户程序是怎么被选中的

`nanos-lite/src/proc.c:23-35`

```c
void init_proc() {
  switch_boot_pcb();
  ...
#ifdef PROGRAM_NAME
  const char *prog = PROGRAM_NAME;
#else
  const char *prog = fs_first_file();
#endif
  Log("Loading %s", prog);
  naive_uload(NULL, prog);
}
```

这里有两个来源：

### 4.1 显式指定 `PROG`

`nanos-lite/Makefile:8-12`

```make
ifdef PROG
  CFLAGS += -DPROGRAM_NAME='"/bin/$(PROG)"'
endif
```

如果你运行：

```bash
make ARCH=riscv64-nemu run PROG=hello
```

那么 `prog` 就会变成：

```c
"/bin/hello"
```

### 4.2 默认选择 ramdisk 中第一个用户文件

`nanos-lite/src/fs.c:47-50`

```c
const char *fs_first_file(void) {
  return file_table[FD_STDERR + 1].name;
}
```

也就是跳过 `stdin/stdout/stderr` 之后，取 `file_table` 中的第一个普通文件。

---

## 五、文件系统这一层是怎么把“文件名”变成“字节流”的

这是理解当前 loader 的关键：**`loader()` 已经不直接碰 `ramdisk_read()` 了，而是先通过 `fs_open/fs_read/fs_lseek` 访问文件。**

### 5.1 文件表来自 `files.h`

`nanos-lite/src/fs.c:31-39`

```c
static Finfo file_table[] __attribute__((used)) = {
  [FD_STDIN]  = {"stdin",  0, 0, invalid_read, invalid_write},
  [FD_STDOUT] = {"stdout", 0, 0, invalid_read, serial_write},
  [FD_STDERR] = {"stderr", 0, 0, invalid_read, serial_write},
#include "files.h"
};
```

`files.h` 由 `navy-apps/build/ramdisk.h` 链接而来，其中每一项都描述一个文件：

- 文件名
- 文件大小
- 在 `ramdisk.img` 中的偏移

因此，`file_table` 可以理解成“ramdisk 的目录项数组”。

### 5.2 `fs_open()`：把路径名变成 fd

`nanos-lite/src/fs.c:52-62`

```c
int fs_open(const char *pathname, int flags, int mode) {
  for (int i = 0; i < NR_FILES; i++) {
    if (strcmp(file_table[i].name, pathname) == 0) {
      file_table[i].open_offset = 0;
      return i;
    }
  }
  panic("fs_open: '%s' not found", pathname);
}
```

它做的事很简单：

- 在 `file_table` 里线性查找文件名；
- 找到后返回数组下标作为 `fd`；
- 同时把 `open_offset` 归零。

### 5.3 `fs_read()`：把 fd 变成字节读取

`nanos-lite/src/fs.c:65-83`

对于普通文件，核心逻辑是：

```c
size_t available = f->size - f->open_offset;
if (len > available) len = available;
ramdisk_read(buf, f->disk_offset + f->open_offset, len);
f->open_offset += len;
```

关键含义：

- `f->disk_offset`：这个文件在整个 `ramdisk.img` 里的起始位置
- `f->open_offset`：这次打开后的当前读写位置
- 两者相加，才是 `ramdisk_read()` 真正使用的镜像内偏移

所以这里完成了一个重要翻译：

```text
文件内偏移
  -> ramdisk 全局偏移
  -> 内核中 ramdisk_start 起始的真实内存地址
```

### 5.4 `fs_lseek()`：修改当前文件位置

`nanos-lite/src/fs.c:111-121`

`loader()` 需要在 ELF 文件里来回跳转：

- 先读 ELF header
- 再跳到 program header table
- 再跳到每个 segment 的 `p_offset`

这个“跳转”就是靠 `fs_lseek()` 修改 `open_offset` 实现的。

### 5.5 `ramdisk_read()`：最终的数据源

`nanos-lite/src/ramdisk.c:13-16`

```c
size_t ramdisk_read(void *buf, size_t offset, size_t len) {
  assert(offset + len <= RAMDISK_SIZE);
  memcpy(buf, &ramdisk_start + offset, len);
  return len;
}
```

这一层已经完全不关心“文件”了，只负责：

- 从 `ramdisk_start + offset` 开始
- 复制 `len` 字节到 `buf`

这就是整个加载路径上的**最后一级字节提供者**。

---

## 六、`loader()` 是怎么解析 ELF 的

核心代码在 `nanos-lite/src/loader.c:13-34`。

### 6.1 先打开文件

```c
int fd = fs_open(filename, 0, 0);
assert(fd >= 0);
```

这里的 `filename` 通常是 `/bin/hello` 或 `/bin/dummy` 这种路径。

### 6.2 读取 ELF Header

```c
Elf_Ehdr ehdr;
fs_read(fd, &ehdr, sizeof(ehdr));
assert(*(uint32_t *)ehdr.e_ident == 0x464c457f);
```

这一步拿到最关键的全局元数据：

- `e_entry`：程序入口地址
- `e_phoff`：program header table 在文件中的偏移
- `e_phnum`：program header 数量
- `e_phentsize`：每个 program header 的大小

同时会验证 ELF magic，确保打开的是合法 ELF。

### 6.3 遍历 Program Header Table

```c
for (int i = 0; i < ehdr.e_phnum; i++) {
  fs_lseek(fd, ehdr.e_phoff + i * ehdr.e_phentsize, SEEK_SET);
  fs_read(fd, &phdr, sizeof(phdr));
  ...
}
```

这一段的本质是：

- 通过 `e_phoff` 找到 program header table；
- 每次跳到第 `i` 个表项的位置；
- 读取出一个 `Elf_Phdr`。

### 6.4 只处理 `PT_LOAD` 段

```c
if (phdr.p_type == PT_LOAD) {
  fs_lseek(fd, phdr.p_offset, SEEK_SET);
  fs_read(fd, (void *)(uintptr_t)phdr.p_vaddr, phdr.p_filesz);
  memset((void *)(uintptr_t)(phdr.p_vaddr + phdr.p_filesz), 0,
         phdr.p_memsz - phdr.p_filesz);
}
```

这里是整个 loader 的核心。

#### 第一步：把段内容拷贝到目标地址

- `phdr.p_offset`：该段在 ELF 文件中的起始偏移
- `phdr.p_filesz`：文件中该段实际占的字节数
- `phdr.p_vaddr`：该段要加载到的目标虚拟地址

于是：

```text
ELF 文件中 [p_offset, p_offset + p_filesz)
  -> 拷贝到内存中的 [p_vaddr, p_vaddr + p_filesz)
```

#### 第二步：把 `.bss` 补成 0

如果：

```text
p_memsz > p_filesz
```

说明这个段在内存中比在文件中更大，多出来的部分通常就是 `.bss`。

所以 loader 会执行：

```c
memset(p_vaddr + p_filesz, 0, p_memsz - p_filesz);
```

这一步非常关键，因为未初始化的全局变量依赖它变成 0。

### 6.5 返回入口地址

```c
fs_close(fd);
return ehdr.e_entry;
```

也就是说，`loader()` 自己**不跳转**，它只负责：

- 把程序装进内存
- 把 ELF entry 地址交回给上层

---

## 七、`naive_uload()` 如何把控制流交给用户程序

`nanos-lite/src/loader.c:36-39`

```c
void naive_uload(PCB *pcb, const char *filename) {
  uintptr_t entry = loader(pcb, filename);
  Log("Jump to entry = %p", (void *)entry);
  ((void(*)())entry) ();
}
```

这里没有上下文切换，也没有 trap return，而是一个非常直接的动作：

```text
把 entry 当成函数指针，直接调用
```

这也是为什么它叫 `naive_uload()`：

- 没有建立真正的用户态执行上下文
- 没有切换地址空间
- 没有单独的用户栈准备逻辑
- 只是“把 ELF 放好，然后跳过去”

---

## 八、跳到 entry 之后，用户程序是怎么开始执行的

以 navy-apps 程序为例，ELF 入口通常是 `_start`。

### 8.1 `_start`

`navy-apps/libs/libos/src/crt0/start.S:31-34`

```asm
#elif defined(__riscv)
  mv s0, zero
  jal call_main
```

RISC-V 路径下，`_start` 会跳到 `call_main`。

### 8.2 `call_main()`

`navy-apps/libs/libos/src/crt0/crt0.c:7-11`

```c
void call_main(uintptr_t *args) {
  char *empty[] =  {NULL };
  environ = empty;
  exit(main(0, empty, empty));
  assert(0);
}
```

也就是：

```text
_start
  -> call_main()
  -> main()
  -> exit()
```

所以在 `nanos-lite` 看来，loader 的职责到 `entry` 为止；之后程序如何进入 `main()`，是用户程序运行时 (`crt0`) 的职责。

---

## 九、把整个“调用链 + 数据流”串起来

下面这条链路最值得记住：

```text
AM _trm_init()
  -> nanos-lite main()
    -> init_ramdisk()
    -> init_fs()
    -> init_proc()
      -> naive_uload(NULL, prog)
        -> loader(NULL, prog)
          -> fs_open("/bin/xxx")
          -> fs_read(... ELF header ...)
          -> fs_lseek(... program header table ...)
          -> fs_read(... each phdr ...)
          -> fs_lseek(... phdr.p_offset ...)
          -> fs_read(... to phdr.p_vaddr ...)
            -> ramdisk_read(...)
              -> memcpy from ramdisk_start + offset
          -> memset(... bss ...)
          -> return ehdr.e_entry
        -> ((void(*)())entry)()
          -> user ELF _start
          -> call_main()
          -> main()
```

如果只看数据流，可以再压缩成一句：

```text
ramdisk 中的 ELF 字节
  -> 按文件名经 fs 找到
  -> 按 ELF program header 拆成 segment
  -> 拷贝到各自 p_vaddr
  -> 从 e_entry 开始执行
```

---

## 十、关键函数速查表

| 层次 | 关键函数 | 位置 | 作用 |
|---|---|---|---|
| 启动入口 | `_trm_init()` | `abstract-machine/am/src/platform/nemu/trm.c` / `abstract-machine/am/src/riscv/npc/trm.c` | 进入 `nanos-lite main()` |
| 内核初始化 | `main()` | `nanos-lite/src/main.c` | 初始化内存、设备、ramdisk、fs、proc |
| 进程初始化 | `init_proc()` | `nanos-lite/src/proc.c` | 选定要运行的程序并调用 `naive_uload()` |
| 程序加载入口 | `naive_uload()` | `nanos-lite/src/loader.c` | 调 `loader()`，然后跳转到 entry |
| ELF 装载核心 | `loader()` | `nanos-lite/src/loader.c` | 读 ELF header / program header，加载 `PT_LOAD` 段 |
| 文件查找 | `fs_open()` | `nanos-lite/src/fs.c` | 路径名 -> fd |
| 文件读取 | `fs_read()` | `nanos-lite/src/fs.c` | fd -> 文件字节流 |
| 文件定位 | `fs_lseek()` | `nanos-lite/src/fs.c` | 修改文件读取位置 |
| ramdisk 读取 | `ramdisk_read()` | `nanos-lite/src/ramdisk.c` | 从嵌入内核镜像的 `ramdisk` 内存区拷贝字节 |
| 用户程序入口 | `_start` | `navy-apps/libs/libos/src/crt0/start.S` | 用户态运行时入口 |
| 进入 `main()` | `call_main()` | `navy-apps/libs/libos/src/crt0/crt0.c` | 调用户 `main()`，并在返回后 `exit()` |

---

## 十一、当前实现的边界：它还不是完整的进程加载器

这一点很容易和后续实验内容混在一起，所以单独说明。

### 11.1 `PCB *pcb` 目前基本没用上

`loader(PCB *pcb, ...)` 和 `naive_uload(PCB *pcb, ...)` 都带着 `pcb` 参数，但当前代码并没有真正使用它。

虽然 `PCB` 里已经有这些字段：

- `Context *cp`
- `AddrSpace as`
- `max_brk`

见 `nanos-lite/include/proc.h:9-17`，但现在的加载路径仍然是**直接把段放到固定地址，然后直接调用 entry**。

### 11.2 `mm` / `vme` 还没接入真实用户地址空间加载

`nanos-lite/src/mm.c:5-21` 中：

- `new_page()` 还没实现
- `pg_alloc()` 还没实现
- `mm_brk()` 还是空实现

因此当前 loader 还没有做：

- 为用户进程分配独立页表
- 把 ELF 段映射到用户地址空间
- 设置用户栈
- 构造 `ucontext`

### 11.3 现在也没有真正跑通内核侧 `execve()`

当前仓库中的启动方式是**开机时在 `init_proc()` 里直接加载一个程序**，不是由内核系统调用路径动态 `execve()` 一个新程序。

所以现在更准确的说法是：

> `nanos-lite` 当前实现的是“启动时的单程序 naive 装载”，而不是“完整的进程创建 + execve + 地址空间切换”。

---

## 十二、最值得抓住的三个理解点

### 12.1 loader 当前依赖的是 `fs_*`，不是直接读 raw ramdisk

这是当前代码和很多旧资料最容易混淆的地方。

**现在的真实链路是：**

```text
loader -> fs_read -> ramdisk_read
```

不是：

```text
loader -> ramdisk_read
```

### 12.2 `PT_LOAD` 才是 loader 真正关心的对象

section（`.text/.data/.bss`）主要是链接视角；  
loader 运行时真正按 segment 工作，只处理 `PT_LOAD` 项。

### 12.3 `e_entry` 只是入口地址，真正“如何进入 main”由 CRT 决定

`nanos-lite` 只负责跳到 ELF entry。  
entry 之后 `_start -> call_main -> main` 的组织，是用户程序运行时自己完成的。

---

## 十三、建议结合源码阅读的顺序

如果你想顺着数据通路自己再走一遍，推荐按这个顺序看：

1. `nanos-lite/src/main.c`
2. `nanos-lite/src/proc.c`
3. `nanos-lite/src/loader.c`
4. `nanos-lite/src/fs.c`
5. `nanos-lite/src/ramdisk.c`
6. `nanos-lite/src/resources.S`
7. `navy-apps/libs/libos/src/crt0/start.S`
8. `navy-apps/libs/libos/src/crt0/crt0.c`

按这个顺序，控制流和数据流会同时对上。

---

## 十四、一句话总结

当前仓库里的 `nanos-lite` 加载程序，本质上是：

> **把嵌入在内核镜像中的 `ramdisk.img` 当作文件系统后端，通过 `fs_open/fs_read/fs_lseek` 读取目标 ELF，只加载其中的 `PT_LOAD` 段到 `p_vaddr`，补零 `.bss`，最后直接调用 `e_entry`。**

这条路径已经足够支撑“从 ramdisk 启动一个用户程序”，但还没有扩展成完整的用户进程管理与虚拟内存加载机制。
