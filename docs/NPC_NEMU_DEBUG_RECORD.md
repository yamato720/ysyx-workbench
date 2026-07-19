# NPC-NEMU 集成 Debug 记录

> 本文记录将 NPC（Chisel CPU，经 Verilator 仿真）集成进 NEMU difftest 框架过程中遇到的所有 bug，包括误导性现象、真实原因及解决方案。

---

## Bug 1：Verilator 生成文件找不到 `VCPU_64_DPI*.cpp`

### 现象

```
VCPU_64_DPI*.cpp: No such file or directory
```

编译 NEMU（`USENPC=1`）时，链接阶段找不到 Verilator 生成的源文件。

### 原因

Verilator 在未指定顶层模块时，会**按字母顺序**选择第一个"独立可编译"的模块作为顶层。工程中存在 `ALU_Core_M` 等模块，Verilator 实际选择了它，生成的类名为 `VALU_Core_M`，而非预期的 `VCPU`。

而 `npc/Makefile` 和 `npc/csrc/npc_core.h` 中写死了 `VCPU_64_DPI`（旧命名），导致文件和类型都找不到。

### 解决

1. 在 `npc/Makefile` 所有 `verilator` 调用中加上 `--top-module CPU`，强制指定顶层模块。
2. `npc/csrc/npc_core.h` 中：
   - `#include "VCPU_64_DPI.h"` → `#include "VCPU.h"`
   - `typedef VCPU_64_DPI TopModule;` → `typedef VCPU TopModule;`
3. Makefile 中编译 Verilator 生成源的 pattern 从 `VCPU_64_DPI*.cpp` 改为 `VCPU*.cpp`。

---

## Bug 2：`run-bat` 报 `undefined reference to 'npc_start_trace'`

### 现象

不带 NPC 的普通 `run-bat` 构建时报链接错误：

```
undefined reference to 'npc_start_trace'
undefined reference to 'npc_stop_trace'
```

### 误导

最初以为是 `npc_core.cpp` 没有被编译进去，或者函数签名不对。

### 真实原因

`nemu/src/monitor/sdb/sdb.h` 中 VCD 接口声明没有同时受 NPC 和 VCD 构建选项保护，导致不带波形运行时的构建也会引用这些符号，但此时没有链接 `npc_core.o`，产生 undefined reference。

同时发现 `npc_core.cpp` 中有一处 `printf("traced!\n")` 缺少分号，导致该文件根本编译不过。

### 解决

1. 修复 `npc_core.cpp` 中缺失的分号。
2. `sdb.h` 中只在 `NPC && NPC_VCD_TRACE` 下声明 VCD 接口。
3. `sdb.c` 中只在 `NPC && NPC_VCD_TRACE` 下注册 `start` / `stop` 命令。

---

## 当前 VCD 行为

### 现象

Config 驱动的默认 NPC 构造不生成 VCD，默认构建不包含 Verilator VCD 运行时。

### 原因

NEMU 的文本追踪和 NPC 波形追踪是不同功能。历史实现曾把两者混在一起，并让 batch 模式自动打开波形，既增加了默认构建开销，也不利于按需截取波形。

### 解决

在 NEMU 的 `Testing and Debugging` 菜单启用 `CONFIG_NPC_VCD_TRACE`（它依赖 `CONFIG_TRACE`）后，AM 会将选项传给 NPC/SoC 集成构建：

```text
CONFIG_NPC_VCD_TRACE=y
  -> Verilator --trace + verilated_vcd_c.o
  -> SDB registers start / stop
  -> start opens clip_wave.vcd; stop closes it
```

关闭该选项时，不传 `--trace`，不编译或链接 VCD 运行时，且 SDB 中没有这些命令。Config 构造库不会根据
menuconfig 自动重建 Verilator；需要波形支持时应新增固定该能力的终端 Config，并以 `rebuild=1` 构造。
独立的 `chisel-cpu` 调试模拟器保留自己的波形支持，不受这个 NEMU 配置控制。Batch 模式不会自动打开
波形；请在交互式 SDB 中用 `start` 和 `stop` 选择波形区间。

---

## Bug 5：旧 NPC batch 入口触发 `Fatal glibc error: malloc.c:4512 assertion failed`

### 现象

```
Fatal glibc error: malloc.c:4512 (_int_malloc): assertion failed:
(unsigned long)(size) >= (unsigned long)(nb)
```

NEMU 二进制文件启动即崩溃，在 `npc_init()` 调用处（打印"NPC mode enabled"之后，"NPC initialized successfully"之前）中断。

### 误导过程（耗时最长）

#### 误导 1：以为是 make jobserver 导致链接损坏

观察到崩溃在旧 batch 流程调用 `$(MAKE) -C $(NEMU_HOME)` 时发生，恰好在 Verilator 多线程编译（`-j$(nproc)`）之后，因此怀疑是 **make jobserver** 的 file descriptor 被 Verilator 子进程耗尽，导致后续 `ld`/`g++` 链接时内存损坏。

为此尝试了：
- `$(MAKE) -j1 -C $(NEMU_HOME)` → 仍然崩溃
- `+$(SHELL) -c '$(MAKE) ...'` → 仍然崩溃
- `$(MAKE) --jobserver-auth= ...` → make 报 invalid option
- `env -i HOME=... PATH=... $(MAKE) -j1 -C $(NEMU_HOME)` → **仍然崩溃**

#### 误导 2：以为是 NEMU 每次强制 relink

`native.mk` 中有 `$(BINARY):: compile_git` 双冒号规则，导致每次执行 `make -C $(NEMU_HOME)` 都会触发重新链接。怀疑是在被污染的 jobserver 环境里链接出了损坏的二进制。

但直接单独运行 `make -C $(NEMU_HOME) ISA=riscv64 USENPC=1`（脱离父 make 环境）重新链接的二进制，**依然崩溃**，排除 jobserver 影响。

#### 关键转折：直接运行二进制

将怀疑从"链接损坏"转向"运行时堆损坏"：

```bash
# 不带 --diff，只测二进制本身能否启动
./build/riscv64-nemu-interpreter -b /path/to/add.bin
→ Fatal glibc error: malloc.c assertion
```

但注释掉 `monitor.c` 中的 `npc_init()` 调用后：

```bash
→ Segfault（因为 npc_cpu == nullptr 时执行 npc_single_run()）
```

说明：**crash 在 `npc_init()` 内部，是运行时堆损坏，不是链接问题**。

### 真实原因

**Verilator 头文件版本不一致（ABI 不兼容）**

| 文件 | 使用的 `verilated.h` |
|------|---------------------|
| `VCPU*.cpp`（Verilator 生成） | `/home/pyx/Software/oss-cad-suite/share/verilator/include/` |
| `npc_core.cpp`、`pmem.cpp` | `/usr/local/share/verilator/include/`（**系统旧版本**） |

`npc/Makefile` 中编译 `npc_core.cpp` 和 `pmem.cpp` 时使用了**硬编码路径** `/usr/local/share/verilator/include`，而不是从 `which verilator` 动态获取的 `oss-cad-suite` 路径。

两个版本的 `verilated.h` 中 `VerilatedContext` 结构体的**内存布局（struct layout）不同**。`new TopModule` 时调用 `VCPU(const char*)` → `VCPU(Verilated::threadContextp(), name)` → `new VCPU__Syms(contextp, ...)` → `VerilatedSyms` 构造函数对 `VerilatedContext` 对象的偏移量访问错乱，写越界，破坏 glibc malloc 的 chunk header，最终触发 `malloc` 断言。

### 如何发现

1. 用 `strings` 检查 `verilated.o`：

   ```bash
   strings constructions/scpu.NpcDpiConfig/abi/verilator/verilated.o | grep "verilator"
   → /home/pyx/Software/oss-cad-suite/share/verilator/include/verilated.cpp
   ```

2. 检查 `npc/Makefile` 中编译 `npc_core.cpp` 的命令，发现硬编码的 `/usr/local/share/verilator/include`。

3. 确认两路径下 `verilated.h` 内容不同：

   ```bash
   diff /usr/local/share/verilator/include/verilated.h \
        /home/pyx/Software/oss-cad-suite/share/verilator/include/verilated.h
   → 多处 struct 成员和 inline 函数定义有差异
   ```

### 解决

修改 `npc/Makefile`，将 NPC core 对象的编译也改为动态获取 `VERILATOR_ROOT`：

```makefile
# 修改前（硬编码）：
g++ -c -fPIC ... -I/usr/local/share/verilator/include ... npc_core.cpp

# 修改后（动态获取，与 Verilator 生成代码保持一致）：
@VERILATOR_ROOT=$$(dirname $$(dirname $$(which verilator)))/share/verilator && \
g++ -c -fPIC ... -I$$VERILATOR_ROOT/include ... npc_core.cpp
```

同时将 `monitor.c` 中被注释掉的 `npc_init()` 调用恢复：

```c
#ifdef NPC
printf("NPC mode enabled, initializing NPC...\n");
extern void npc_init();
npc_init();
printf("NPC initialized successfully\n");
#endif
```

### 修复后效果

```
NPC mode enabled, initializing NPC...
NPC initialized successfully
NPC flag triggered.
...
[add] PASS
```

---

## 最终状态

| 目标 | 状态 |
|------|------|
| `run-bat`（纯 NEMU） | ✅ PASS |
| `run-bat config=NpcDpiConfig`（NEMU + NPC） | ✅ PASS（difftest 报告 CPU 寄存器不一致为 NPC CPU 逻辑问题，非构建问题） |
| NEMU host 按 C/C++ 依赖增量更新；NPC 层显式 `rebuild=1` | ✅ 正常 |
| VCD 按 `CONFIG_NPC_VCD_TRACE` 构建并由 SDB 截取 | ✅ `clip_wave.vcd` |

---

## 经验总结

1. **malloc/heap corruption 通常是 ABI 不兼容，不是逻辑 bug**：遇到 `malloc assertion` 优先排查头文件版本、编译 flag 一致性，而不是逻辑问题。

2. **多版本工具链共存时注意路径污染**：系统 `/usr/local` 和用户级工具链（如 `oss-cad-suite`）同时存在时，硬编码路径极易引入隐蔽的 ABI 不兼容。

3. **脱离父 make 环境测试是定位 jobserver 问题的关键**：直接执行二进制（完全脱离 make）能排除 jobserver 影响，快速确认是否为运行时问题。

4. **注释代码绕过崩溃只是 workaround，要找根因**：`npc_init()` 被注释后崩溃消失，这是关键线索——crash 在 `npc_init()` 内部，而不是调用者。
