# NPC Integration with NEMU

## 当前实现说明

### 问题
NEMU 需要在 NPC 模式下调用 NPC (Chisel CPU) 来执行指令，但它们是两个独立的程序。

### 解决方案选项

#### 选项 1: 共享库方式 (推荐)
将 NPC 编译成共享库 (.so)，NEMU 动态加载并调用：

1. 修改 NPC Makefile，编译成共享库：
```makefile
chisel-cpu-lib: chisel-dpi
	verilator ... -shared -fPIC ...
	# 生成 libnpc.so
```

2. NEMU 使用 dlopen/dlsym 加载 NPC：
```c
#ifdef NPC
void* npc_handle = dlopen("libnpc.so", RTLD_LAZY);
void (*npc_single_run)() = dlsym(npc_handle, "npc_single_run");
#endif
```

#### 选项 2: 独立运行 (当前方案)
NEMU 和 NPC 分别独立运行：
- `make ARCH=riscv64-nemu` → 使用 NEMU 软件模拟
- `make ARCH=riscv64-npc` → 使用 NPC 硬件仿真

**这是当前实现的方案，两者不需要直接调用。**

#### 选项 3: DiffTest 集成
如果需要对比测试，应该：
1. NPC 集成 DiffTest，加载 NEMU-so 作为参考
2. NPC 每执行一条指令，调用 NEMU-so 对比状态

## 当前代码的作用

`cpu-exec.c` 中的 NPC 分支代码主要是**占位符**，表示"在 NPC 模式下，不使用 NEMU 的软件模拟"。

实际执行流程：
- 编译时如果定义了 NPC 宏，NEMU 的 `exec_once` 会调用 NPC 接口
- 但这需要 NPC 被编译到同一个可执行文件中，或作为库链接

## 推荐做法

**保持当前的独立运行方式**，不需要复杂的集成：

1. 软件仿真：`make ARCH=riscv64-nemu ALL=add run-npc-bat`
   - 使用 NEMU（定义 NPC 宏，但可以有不同的行为）

2. 硬件仿真：`make ARCH=riscv64-npc ALL=add run`
   - 使用 NPC (Verilator)

3. DiffTest：在 NPC 中实现，对比 NEMU-so

## 如果必须集成

需要做的事情：
1. 在 NPC 的 `main_chisel.cpp` 中添加全局变量和导出函数
2. 将 NPC 编译成库
3. 修改 NEMU Makefile，链接 NPC 库
4. 处理 Verilator 的依赖和初始化

这会非常复杂，不建议这样做。


