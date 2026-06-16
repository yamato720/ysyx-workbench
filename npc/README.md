# NPC - Chisel RISC-V CPU 仿真器

本项目实现了一个基于 Chisel 的 RISC-V CPU，并通过 Verilator 进行仿真。通过与 Abstract Machine (AM) 构建系统的集成，可以直接运行 AM 程序。

## 目录结构

```
npc/
├── chisel/                     # Chisel 源代码
│   └── src/main/scala/
│       ├── top.scala           # CPU 顶层模块
│       ├── DPIMem.scala        # DPI-C 内存 BlackBox
│       ├── DataManage.scala    # Cache 模块 (insCacheL1, dataCacheL1)
│       └── Elaborate.scala     # Verilog 生成入口
├── csrc/
│   ├── main_chisel.cpp         # Verilator 仿真主程序
│   └── pmem.cpp                # DPI-C 物理内存实现
├── generated-dpi/              # Chisel 生成的 Verilog (DPI-C 模式)
├── out/chisel-cpu/             # 编译输出的仿真器
└── Makefile
```

## 核心实现原理

### 1. Makefile 调用链

当执行 `make ARCH=riscv64-npc ALL=add run` 时，调用链如下：

```
am-kernels/tests/cpu-tests/
        │
        ▼ make ARCH=riscv64-npc ALL=add run
┌───────────────────────────────────────────────────────────┐
│  abstract-machine/Makefile                                │
│  - 解析 ARCH=riscv64-npc                                  │
│  - include scripts/riscv64-npc.mk                         │
└───────────────────────────────────────────────────────────┘
        │
        ▼
┌───────────────────────────────────────────────────────────┐
│  abstract-machine/scripts/riscv64-npc.mk                  │
│  - include scripts/isa/riscv.mk      (RV64 ISA 配置)      │
│  - include scripts/platform/npc.mk   (NPC 平台配置)       │
│  - COMMON_CFLAGS := -march=rv64i_zicsr -mabi=lp64        │
└───────────────────────────────────────────────────────────┘
        │
        ▼
┌───────────────────────────────────────────────────────────┐
│  abstract-machine/scripts/platform/npc.mk                 │
│  - 定义 NPC_HOME 指向 npc 目录                            │
│  - image: 编译生成 .bin 文件                              │
│  - run: 调用 $(MAKE) -C $(NPC_HOME) run-chisel IMG=...   │
└───────────────────────────────────────────────────────────┘
        │
        ▼
┌───────────────────────────────────────────────────────────┐
│  npc/Makefile                                             │
│  - run-chisel: 依赖 chisel-cpu                           │
│  - chisel-cpu: 依赖 chisel-dpi                           │
│  - chisel-dpi: 调用 sbt 生成 Verilog                     │
│  - verilator 编译 Verilog + C++ 生成仿真器               │
└───────────────────────────────────────────────────────────┘
        │
        ▼
┌───────────────────────────────────────────────────────────┐
│  执行仿真: ./out/chisel-cpu/npc-exec <image.bin>         │
└───────────────────────────────────────────────────────────┘
```

### 2. 关键 Makefile 片段

#### `abstract-machine/scripts/riscv64-npc.mk`
```makefile
include $(AM_HOME)/scripts/isa/riscv.mk
include $(AM_HOME)/scripts/platform/npc.mk

CFLAGS  += -DISA_H=\"riscv/riscv.h\"
# RV64I for Chisel CPU (with Zicsr for CSR instructions, soft-float ABI)
COMMON_CFLAGS := -fno-pic -march=rv64i_zicsr -mabi=lp64 -mcmodel=medany -mstrict-align
```

#### `abstract-machine/scripts/platform/npc.mk`
```makefile
NPC_HOME ?= $(AM_HOME)/../npc

# 编译生成 .bin 文件
image: image-dep
	@$(OBJCOPY) -S --set-section-flags .bss=alloc,contents -O binary $(IMAGE).elf $(IMAGE).bin

# 运行 Chisel CPU 仿真
run: insert-arg
	$(MAKE) -C $(NPC_HOME) run-chisel IMG=$(IMAGE).bin
```

#### `npc/Makefile`
```makefile
# 生成 DPI-C 模式的 Verilog
chisel-dpi:
	cd $(shell pwd) && sbt "root/runMain scpu.ElaborateDPI"

# 用 Verilator 编译仿真器
chisel-cpu: chisel-dpi
	verilator -Wall --cc --exe --build \
		$(CHISEL_DPI_OUT)/*.v ./csrc/main_chisel.cpp ./csrc/pmem.cpp

# 运行仿真
run-chisel: chisel-cpu
	cd ./out/chisel-cpu && ./npc-exec $(IMG)
```

### 3. DPI-C 内存接口

Chisel CPU 通过 DPI-C (Direct Programming Interface for C) 与 C++ 物理内存交互：

```
┌─────────────────────────────────────────────────────────────┐
│                    Chisel CPU                               │
│  ┌─────────────┐              ┌─────────────┐              │
│  │ insCacheL1  │              │ dataCacheL1 │              │
│  │ (指令缓存)  │              │ (数据缓存)  │              │
│  └──────┬──────┘              └──────┬──────┘              │
│         │                            │                      │
│         └──────────┬─────────────────┘                      │
│                    ▼                                        │
│           ┌───────────────┐                                │
│           │   DPIMem      │  (BlackBox)                    │
│           │   Verilog     │                                │
│           └───────┬───────┘                                │
└───────────────────┼─────────────────────────────────────────┘
                    │ DPI-C 函数调用
                    ▼
┌───────────────────────────────────────────────────────────┐
│                  pmem.cpp (C++)                           │
│  ┌─────────────────────────────────────────────────────┐  │
│  │  static uint8_t pmem[128MB];  // 物理内存           │  │
│  │                                                     │  │
│  │  // DPI-C 导出函数                                  │  │
│  │  char pmem_read_a(int addr);   // 端口A 读          │  │
│  │  void pmem_write_a(int addr, char data);            │  │
│  │  char pmem_read_b(int addr);   // 端口B 读          │  │
│  │  void pmem_write_b(int addr, char data);            │  │
│  └─────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────┘
```

#### `DPIMem.scala` (Chisel BlackBox)
```scala
class DPIMem extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val addr_a = Input(UInt(32.W))
    val dout_a = Output(UInt(8.W))
    // ... 双端口接口
  })

  setInline("DPIMem.v", """
    module DPIMem(...);
      import "DPI-C" function byte pmem_read_a(input int addr);
      import "DPI-C" function void pmem_write_a(input int addr, input byte data);
      // ...
    endmodule
  """)
}
```

#### `pmem.cpp` (C++ 实现)
```cpp
#define PMEM_SIZE (128 * 1024 * 1024)  // 128MB
#define PMEM_BASE 0x80000000

static uint8_t pmem[PMEM_SIZE] = {};

extern "C" {
  // DPI-C 函数 - Verilog 可调用
  char pmem_read_a(int addr) {
    return pmem[addr - PMEM_BASE];
  }
  
  void pmem_write_a(int addr, char data) {
    pmem[addr - PMEM_BASE] = data;
  }
  
  // 加载二进制镜像
  int load_image(const char *filename) {
    FILE *fp = fopen(filename, "rb");
    fread(pmem, 1, size, fp);
    // ...
  }
}
```

### 4. Cache 模块的 DPI-C 支持

`insCacheL1` 和 `dataCacheL1` 支持三种模式：

```scala
class insCacheL1(useBlackBox: Boolean = false, 
                 useDPI: Boolean = false, 
                 initFile: Option[String] = None) extends Module {
  
  if (useDPI) {
    // 使用 DPI-C 外部内存 (Verilator 仿真)
    val dpiMem = Module(new DPIMem)
    // 连接 DPI-C 接口...
  } else if (useBlackBox) {
    // 使用 FPGA BRAM BlackBox (综合)
    val blackbox = Module(new bram_8_4096_mem_shell)
    // ...
  } else {
    // 使用 Chisel SyncReadMem (功能仿真)
    val bram = SyncReadMem(4096, UInt(8.W))
    // ...
  }
}
```

### 5. Elaborate 入口

```scala
// 普通模式 - 内部 SyncReadMem
object Elaborate extends App {
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new CPU(Width = 64, Debug = true),
    Array("--target-dir", "./generated"),
    Array("--disable-annotation-unknown")
  )
}

// DPI-C 模式 - 外部内存
object ElaborateDPI extends App {
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new CPU(Width = 64, Debug = true, useDPI = true),
    Array("--target-dir", "./generated-dpi"),
    Array("--disable-annotation-unknown")
  )
}
```

## 使用方法

### 编译并运行测试

```bash
# 在 am-kernels/tests/cpu-tests 目录下
make ARCH=riscv64-npc ALL=add run      # 运行 add 测试
make ARCH=riscv64-npc ALL=dummy run    # 运行 dummy 测试

# 或者直接在 npc 目录下
make run-chisel IMG=/path/to/image.bin
```

### 单独构建步骤

```bash
cd npc/

# 1. 生成 Verilog (DPI-C 模式)
make chisel-dpi

# 2. 编译 Verilator 仿真器
make chisel-cpu

# 3. 运行仿真
make run-chisel IMG=xxx.bin
```

### 查看生成的文件

```bash
# Chisel 生成的 Verilog
ls generated-dpi/
# CPU.sv

# 编译后的仿真器
ls out/chisel-cpu/
# npc-exec
```

## 架构支持

| ARCH | 描述 | CPU |
|------|------|-----|
| `riscv64-npc` | 64位 RISC-V NPC | Chisel CPU (RV64I) |
| `minirv-npc` | 32位 mini RISC-V NPC | 原 Verilog CPU |
| `minirv-nemu` + `run-npc` | 通过 NEMU 配置运行 NPC | Chisel CPU |
| `riscv64-nemu` | 64位 RISC-V NEMU | NEMU 模拟器 |

## AXI4-Lite 相关文档

当前 NPC 的 AXI4-Lite 说明分成三类，建议按下面顺序阅读：

1. `../docs/NPC_AXI4_LITE_TOP_INTEGRATION.md`
   面向当前实现的总览，重点讲 top 接线、Crossbar 地址分发、Chisel 语法
2. `AXI4_LITE_CHANGE_ANALYSIS.md`
   重点讲接入 AXI4-Lite 后结构发生了什么变化
3. `AXI4_LITE_MIGRATION.md`
   重点讲迁移设计思路，以及为什么这样改

## DiffTest 支持

DiffTest 可以让 NPC 与 NEMU 进行对比验证，每执行一条指令就比较两者的状态。

### 构建 NEMU 参考实现

```bash
# 编译 NEMU 为动态库 (.so)
cd ../nemu
make ISA=riscv64 SHARE=1
# 生成 build/riscv64-nemu-interpreter-so
```

### 使用 DiffTest 运行

```bash
# 方式1: 直接在 npc 目录运行
make run-difftest IMG=/path/to/image.bin

# 方式2: 手动指定参考实现
./out/chisel-cpu/npc-exec -d ../nemu/build/riscv64-nemu-interpreter-so image.bin
```

### 命令行选项

```
Usage: npc-exec [OPTIONS] <image.bin>
Options:
  -d, --diff=REF_SO    启用 DiffTest，指定 NEMU .so 文件
  -b, --batch          批处理模式 (无交互)
  -h, --help           显示帮助信息
```

### DiffTest 工作原理

```
┌──────────────────┐                    ┌──────────────────┐
│       NPC        │                    │       NEMU       │
│   (Chisel CPU)   │                    │    (Reference)   │
│                  │                    │                  │
│   执行一条指令   │                    │   执行一条指令   │
│        │         │                    │        │         │
│        ▼         │                    │        ▼         │
│   ┌─────────┐    │    比较状态        │   ┌─────────┐    │
│   │ PC, GPR │◄───┼────────────────────┼──►│ PC, GPR │    │
│   └─────────┘    │                    │   └─────────┘    │
└──────────────────┘                    └──────────────────┘
         │                                       ▲
         │          dlopen/dlsym                 │
         └───────────────────────────────────────┘
                  riscv64-nemu-interpreter-so
```

## 调试

仿真器会生成波形文件：
```bash
cd out/chisel-cpu/
./npc-exec image.bin
# 生成 wave.vcd

# 用 GTKWave 查看波形
gtkwave wave.vcd
```
