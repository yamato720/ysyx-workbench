# nanos-lite 程序加载说明

## 快速参考

```bash
# 查看 ramdisk 中当前加载的文件
make ARCH=riscv64-nemu list

# 运行（使用现有 ramdisk，不重建）
make ARCH=riscv64-nemu run
make ARCH=riscv64-nemu run PROG=hello

# 重建 ramdisk 再运行（navy-apps 源码有改动时用）
make ARCH=riscv64-nemu run-update
make ARCH=riscv64-nemu run-update PROG=hello

# 编译 navy-apps 中的某个程序并打包进 ramdisk（一键载入）
make ARCH=riscv64-nemu save PROG=hello
make ARCH=riscv64-nemu save PROG=dummy

# 只重建 ramdisk（不运行）
make ARCH=riscv64-nemu update
```

> ⚠️ 不要直接在 navy-apps 根目录运行 `make ISA=riscv64`，会因为没有 NAME/SRCS 而报链接错误。
> 用 `make save PROG=<name>` 替代，它会正确地进入 `tests/<name>/` 子目录编译。

---

## PROG 变量说明

| 情形 | 命令 | 加载的程序 |
|------|------|-----------|
| 不指定 PROG | `make ARCH=riscv64-nemu run` | ramdisk 中第一个用户文件（由 `files.h` 决定） |
| 指定程序 | `make ARCH=riscv64-nemu run PROG=hello` | `/bin/hello` |
| 指定程序 | `make ARCH=riscv64-nemu run PROG=dummy` | `/bin/dummy` |

`PROG` 是一个编译时变量，会被展开为 `-DPROGRAM_NAME='"/bin/<PROG>"'` 传给编译器。
每次改变 `PROG` 都会触发 `proc.c` 重新编译。

---

## list：查看 ramdisk 内容

```bash
make ARCH=riscv64-nemu list
```

示例输出：
```
Files in ramdisk (487936 bytes total):
   1) /bin/dummy                                 41528 bytes  @ offset 0
   2) /bin/hello                                 45992 bytes  @ offset 41528
   3) /share/fonts/Courier-7.bdf                 19567 bytes  @ offset 87520
   ...
```

解析来源：`src/files.h`（symlink → `navy-apps/build/ramdisk.h`）

---

## save：编译并一键载入程序

```bash
make ARCH=riscv64-nemu save PROG=hello
make ARCH=riscv64-nemu save PROG=bmp-test
```

`save` 会依次做：
1. 在 `navy-apps/tests/<PROG>/` 或 `navy-apps/apps/<PROG>/` 目录下编译并 `install`（复制到 `fsimg/bin/`）
2. `make -C navy-apps ramdisk` 重打包 fsimg 下所有文件
3. 更新 nanos-lite 的 symlinks（ramdisk.img、files.h）

**与直接在 navy-apps 根目录 `make` 的区别：**

| 方式 | 结果 |
|------|------|
| `cd navy-apps && make ISA=riscv64` | ❌ 报链接错误（根 Makefile 无 NAME/SRCS） |
| `make ARCH=riscv64-nemu save PROG=hello` | ✅ 进入正确子目录编译，自动打包 |

**注意**：`save` 之后用 `run`（不是 `run-update`）运行，避免 `run-update` 重建 ramdisk 把刚 save 的状态覆盖：
```bash
make ARCH=riscv64-nemu save PROG=hello
make ARCH=riscv64-nemu run PROG=hello
```

---

## ramdisk 中的程序顺序

程序打包顺序由 `navy-apps/Makefile` 中的 `TESTS` 变量决定：

```makefile
# navy-apps/Makefile（默认）
TESTS = dummy hello
```

- `dummy` 排在第一位 → offset 0 → 不指定 PROG 时默认加载 `dummy`
- 若要改变默认程序，修改 `TESTS` 的顺序后重新 `make run` 即可

---

## ramdisk 导入原理

### 打包流程

```
navy-apps/tests/dummy/  navy-apps/tests/hello/
        ↓ make ISA=riscv64 ramdisk
navy-apps/build/ramdisk.img   ← 多个 ELF 文件顺序拼接的二进制
navy-apps/build/ramdisk.h     ← 每个文件的 { 名称, 大小, 偏移 }
        ↓ ln -sf（make update / make run）
nanos-lite/build/ramdisk.img  (symlink)
nanos-lite/src/files.h        (symlink)
```

### 编译时嵌入（.incbin）

```asm
# nanos-lite/src/resources.S
.section .data
.global ramdisk_start, ramdisk_end
ramdisk_start:
.incbin "build/ramdisk.img"    ← 链接时直接嵌入 ramdisk 二进制
ramdisk_end:
```

ramdisk **不是运行时加载的**，而是通过 `.incbin` 在链接时嵌进 nanos-lite 镜像。
运行时 `ramdisk_start` / `ramdisk_end` 是内存中的符号，`ramdisk_read` 直接 `memcpy`。

### Make 依赖链

```
make run / make run PROG=hello
  └── insert-arg → image（重链接 nanos-lite，re-embed 现有 ramdisk）
  └── make -C nemu clean → make -C nemu run（重编 NEMU）

make run-update / make run-update PROG=hello
  └── update（重建 ramdisk.img，更新 symlinks）
        └── make -C navy-apps ISA=riscv64 ramdisk
  └── make run（同上）
```

### 清理机制

| 命令 | 清理范围 |
|------|---------|
| `make clean` | 删除 `nanos-lite/build/`（含 .elf / .bin，但 ramdisk.img symlink 保留） |
| `make -C $(NAVY_HOME) clean` | 删除 `navy-apps/build/`（含 ramdisk.img） |
| `make -C $(NEMU_HOME) clean` | 删除 NEMU 的 build，每次 `make run` 自动触发 |

ramdisk.img 本身是 symlink，`make clean` 删除的是 build/ 目录，symlink 会一起消失。
下次 `make run-update` 会通过 `update` 重新创建 symlink 和 ramdisk。

---

## 实现原理

### 代码链路

```
Makefile (PROG=hello)
  └── CFLAGS += -DPROGRAM_NAME='"/bin/hello"'
        └── proc.c → naive_uload(NULL, "/bin/hello")

Makefile (PROG 未设置)
  └── proc.c → fs_first_file()
        └── fs.c → file_table[FD_STDERR+1].name
              └── files.h (= navy-apps/build/ramdisk.h 的 symlink)
                    → 第一个用户文件的路径名
```

### `fs_first_file()` 函数

定义在 `src/fs.c`，返回文件表中第一个用户文件（索引 `FD_STDERR+1`）的文件名（完整路径，如 `/bin/dummy`）。文件表的前三项固定是 stdin/stdout/stderr，第四项起才是 ramdisk 中的文件。

### proc.c 中的选择逻辑

```c
#ifdef PROGRAM_NAME
  const char *prog = PROGRAM_NAME;   // make PROG=xxx 时编译进来的字符串
#else
  const char *prog = fs_first_file(); // 运行时读文件表第一项
#endif
naive_uload(NULL, prog);
```

---

## 常见问题

### Q：改了 navy-apps 里的程序，run 时看不到变化？

用 `run-update` 替代 `run`：

```bash
make ARCH=riscv64-nemu run-update PROG=hello
```

### Q：为什么不用 `make run -- hello` 语法？

`--` 在 GNU Make 中是结束 make 选项的分隔符，后面的内容不会传给 Makefile。
Makefile 变量赋值（`KEY=VALUE`）是标准做法，对 make 的所有版本都兼容。

### Q：PROG 变量影响 update 吗？

不影响。`make update` 只重建 ramdisk 和 symlink，不涉及程序选择。
`PROG` 只在编译 nanos-lite 本体时生效（影响 `proc.c`）。

### Q：想在运行时动态切换程序怎么办？

目前 nanos-lite 还没有多进程调度（`schedule()` 返回 NULL）。
后续实现多进程后，可通过 `init_proc()` 依次加载多个 PCB，无需重新编译。

