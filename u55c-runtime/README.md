# U55C Runtime 设计指南

本文档用于规划将 `npc` 中的 Chisel RISC-V CPU 放到 AMD Alveo U55C 上运行时所需的运行环境。这里的目标不是把 NEMU 原样搬进 HLS，而是仿照 NEMU 的内存、MMIO、halt、trace 等思想，重做一套适合 FPGA/HLS/RTL 的轻量级运行时。

换句话说，本目录规划的是一个 **U55C 上的 NPC 运行平台**，它负责推动 Chisel CPU kernel 运行、提供极简外设接口、收集执行状态，并和服务器 host 程序配合。

---

## 1. 背景与目标

当前 `npc` 目录中的 CPU 主要面向 Verilator 仿真：

- Chisel 生成 Verilog；
- Verilator 编译成可执行仿真器；
- DPI-C 提供物理内存和 MMIO；
- 可通过 NEMU `.so` 做 DiffTest。

迁移到 U55C 后，DPI-C、Verilator 主循环、NEMU 动态库都不应该进入 FPGA 侧。FPGA 侧应该只保留可综合的硬件逻辑。

本设计的目标是：

```text
服务器 host 程序
  ├── 加载 image.bin 到 U55C HBM
  ├── 启动 FPGA 上的 CPU/runtime kernel
  ├── 轮询或读取 halt/status/trace
  ├── 模拟复杂外设
  └── 可选：用 host 上的 NEMU 做离线 DiffTest

U55C FPGA
  ├── Chisel CPU RTL
  ├── guest memory 访问通路
  ├── MMIO decode
  ├── putch/halt/exit_code 等极简外设
  ├── trace buffer
  └── host request/response queue
```

---

## 2. 不要做什么

本项目不建议做以下事情：

### 2.1 不要把完整 NEMU 搬进 HLS

NEMU 是软件模拟器，内部包含大量不适合 HLS 综合的内容：

- 函数指针；
- 宏生成代码；
- 大量全局状态；
- 调试器、表达式求值、watchpoint；
- 文件 IO、printf/log；
- host syscall/device 抽象；
- 复杂 build system。

即使强行裁剪，最终也会变成一个全新的小模拟器，维护成本很高。

### 2.2 不要在 HLS 中重新解释执行 RISC-V 指令

如果 HLS 侧开始实现：

```text
fetch → decode → execute → update pc
```

那就变成了另一个硬件解释器，而不是推动真实 Chisel CPU 运行。这样会和当前 CPU 设计目标冲突。

### 2.3 不要让普通访存走 host

PCIe 往返延迟很高，不能让每次取指/load/store 都请求服务器处理。

正确原则：

```text
高频路径：取指 / load / store → U55C HBM，本地完成
低频路径：putch / halt / timer / disk / debug → host 模拟
```

---

## 3. 正确定位：HLS/RTL Runtime，不是 NEMU

建议将本模块定位为：

```text
u55c-runtime = FPGA 上的 NPC 运行时 / 设备管理器 / kernel 推动器
```

它不执行 RISC-V 指令，而是负责：

1. 复位并启动 Chisel CPU；
2. 设置 boot PC，例如 `0x80000000`；
3. 提供 guest memory 访问窗口；
4. 解码 MMIO 访问；
5. 实现 putch/halt/exit_code 等简单外设；
6. 维护 trace buffer；
7. 将复杂外设请求转发给 host；
8. 向 host 暴露运行状态。

---

## 4. 推荐总体架构

```text
+----------------------------------------------------------+
| Host C++ Program                                         |
|----------------------------------------------------------|
| - xrt::device / xrt::kernel / xrt::bo                    |
| - load image.bin                                         |
| - start kernel                                           |
| - poll status                                            |
| - handle MMIO requests                                   |
| - read trace buffer                                      |
| - optional offline NEMU DiffTest                         |
+---------------------------+------------------------------+
                            |
                            | PCIe / XRT / HBM buffer
                            |
+---------------------------v------------------------------+
| U55C FPGA                                                |
|----------------------------------------------------------|
|                                                          |
|  +------------------+                                    |
|  | Chisel CPU RTL   |                                    |
|  |------------------|                                    |
|  | i_axi            |----+                               |
|  | d_axi            |----+----> memory/MMIO interconnect  |
|  | commit trace     |----+                               |
|  | halt/status      |----+                               |
|  +------------------+                                    |
|                                                          |
|  +------------------+                                    |
|  | Runtime Bridge   |                                    |
|  |------------------|                                    |
|  | HBM guest memory |                                    |
|  | MMIO decode      |                                    |
|  | putch/halt       |                                    |
|  | trace buffer     |                                    |
|  | host queues      |                                    |
|  +------------------+                                    |
|                                                          |
+----------------------------------------------------------+
```

实现形式可以有两种：

1. **RTL kernel 为主**：Chisel CPU + Verilog/Chisel runtime；
2. **RTL CPU + HLS runtime**：CPU 保持 Chisel RTL，运行时桥接部分用 HLS C++ 写。

第一版建议优先使用统一顶层，把 CPU 和 runtime 放进同一个 kernel，减少 kernel 间通信复杂度。

---

## 5. 建议内存地址规划

保持与当前 NPC/NEMU 风格接近：

```text
0x8000_0000 ~ 0x8fff_ffff  guest physical memory / HBM window
0xa000_0000 ~ 0xa000_ffff  simple MMIO devices
0xa001_0000 ~ 0xa001_ffff  host request/response queue window
0xa002_0000 ~ 0xa002_ffff  trace/control window
```

最小 MMIO 设备：

```text
0xa000_0000  PUTCH
  write: 低 8 bit 作为字符输出

0xa000_0008  HALT
  write: 写入 1 表示 guest 程序结束

0xa000_0010  EXIT_CODE
  write: guest 写入退出码
  read : host 或 debug 逻辑读取退出码

0xa000_0020  TIMER_LOW
  read : cycle/timer 低 32 bit

0xa000_0028  TIMER_HIGH
  read : cycle/timer 高 32 bit
```

第一版只需要实现 `PUTCH`、`HALT`、`EXIT_CODE`。

---

## 6. Host 与 FPGA 的职责划分

### 6.1 Host 侧负责

- 读取 `image.bin`；
- 分配 XRT buffer；
- 将 guest image 拷贝到 HBM；
- 设置启动参数；
- 启动 kernel；
- 轮询 `halt/status`；
- 读取输出字符缓冲区；
- 处理复杂 MMIO 请求；
- 读取 trace；
- 可选：调用 host 上的 NEMU 做离线比对。

### 6.2 FPGA 侧负责

- 复位 CPU；
- 提供 CPU 取指和访存通路；
- 对 MMIO 地址进行 decode；
- 简单设备直接处理；
- 复杂设备请求写入 request queue；
- 等待 host response；
- 收集 commit trace；
- 将 halt 和 exit_code 写入状态寄存器。

---

## 7. 最小可行版本 MVP

第一阶段目标：让 AM cpu-tests 中最简单的程序能在 U55C 上跑完。

### 7.1 FPGA 侧

- Chisel CPU 生成可综合 Verilog；
- 去掉 DPI RAM / DPI MMIO；
- CPU 暴露取指和访存接口；
- 使用 BRAM 或 HBM 作为 guest memory；
- 实现 `PUTCH`、`HALT`、`EXIT_CODE`；
- host 能读到运行状态。

### 7.2 Host 侧

- 加载 `.bin` 到 guest memory；
- 写启动寄存器；
- 启动 kernel；
- 轮询 halt；
- 输出 putch buffer；
- 检查 exit_code。

### 7.3 暂不实现

- Linux；
- 磁盘；
- 文件系统；
- 实时 DiffTest；
- 完整 interrupt；
- 完整 CLINT/PLIC；
- 多核；
- 高性能 cache。

---

## 8. HLS 友好的数据结构建议

HLS 侧不要 include NEMU 的大头文件。建议单独定义干净的数据结构。

```cpp
#include <ap_int.h>

struct CpuStatus {
  ap_uint<32> running;
  ap_uint<32> halted;
  ap_uint<32> exit_code;
  ap_uint<64> cycle;
  ap_uint<64> instret;
};

struct MmioReq {
  ap_uint<64> addr;
  ap_uint<64> wdata;
  ap_uint<8>  wstrb;
  ap_uint<1>  is_write;
  ap_uint<32> tag;
};

struct MmioResp {
  ap_uint<64> rdata;
  ap_uint<1>  ok;
  ap_uint<32> tag;
};

struct TraceEntry {
  ap_uint<64> pc;
  ap_uint<32> inst;
  ap_uint<5>  rd;
  ap_uint<64> rd_wdata;
  ap_uint<1>  rd_wen;
  ap_uint<1>  trap;
};
```

建议原则：

- 不使用 `malloc/free`；
- 不使用文件 IO；
- 不使用递归；
- 不使用复杂 STL 容器；
- ring buffer 使用固定大小数组；
- 状态机显式化；
- 所有接口宽度固定。

---

## 9. MMIO 请求队列设计

复杂外设可以通过 request/response ring 交给 host 模拟。

### 9.1 请求项

```text
addr      : MMIO 地址
wdata     : 写数据
wstrb     : 写字节掩码
is_write  : 1=写，0=读
tag       : 请求编号，用于匹配响应
```

### 9.2 响应项

```text
rdata     : 读返回数据
ok        : 请求是否成功
tag       : 对应请求编号
```

### 9.3 处理流程

```text
CPU 访问复杂 MMIO
  ↓
Runtime 生成 MmioReq
  ↓
写入 request ring
  ↓
CPU 在该 MMIO 请求上 stall
  ↓
Host 读取请求并处理
  ↓
Host 写入 response ring
  ↓
Runtime 取回响应
  ↓
CPU 继续执行
```

注意：这种路径延迟较高，只能用于低频设备。

---

## 10. Trace 与离线 DiffTest

不建议第一版做实时 DiffTest。更推荐：

```text
CPU 每退休一条指令
  ↓
输出 commit trace
  ↓
Runtime 写入 HBM trace buffer
  ↓
Host 程序读取 trace
  ↓
Host 上运行 NEMU
  ↓
离线逐条比对 PC / inst / rd / rd_wdata
```

最小 trace 项：

```text
pc
inst
rd
rd_wen
rd_wdata
trap/halt
```

如果后续要查访存 bug，可扩展：

```text
mem_valid
mem_addr
mem_wdata
mem_rdata
mem_wstrb
```

---

## 11. 推荐开发阶段

### 阶段 0：整理 CPU 顶层

目标：把当前仿真相关逻辑和 CPU core 拆开。

```text
CPUCore
  ├── i_axi / imem interface
  ├── d_axi / dmem interface
  ├── commit trace output
  └── status/halt output

SimTop
  ├── CPUCore
  ├── DPI RAM
  └── DPI MMIO

FPGATop
  ├── CPUCore
  ├── HBM/BRAM memory
  └── runtime bridge
```

### 阶段 1：BRAM MVP

- guest memory 先用 BRAM；
- 只跑很小的 bin；
- 实现 putch/halt/exit_code；
- 不接 HBM，不接复杂 host queue。

### 阶段 2：HBM memory

- host 使用 XRT buffer 加载 image；
- CPU 从 HBM 取指；
- load/store 访问 HBM；
- MMIO 仍然走本地 decode。

### 阶段 3：Trace buffer

- CPU 输出 commit trace；
- runtime 写 trace 到 HBM；
- host 读取 trace 文件；
- 用 NEMU 离线比对。

### 阶段 4：Host-assisted MMIO

- 加 request/response ring；
- host 模拟 timer、输入、块设备等复杂外设；
- CPU 对复杂 MMIO 请求 stall 等待响应。

### 阶段 5：更完整的平台

- timer interrupt；
- 简化 CLINT；
- 更复杂 AM 程序；
- 性能计数器；
- cache / burst / AXI 优化。

---

## 12. 对当前仓库的改造建议

建议后续逐步新增：

```text
u55c-runtime/
  README.md                  本文档
  host/
    main.cpp                 XRT host 程序
  hls/
    runtime_bridge.cpp       HLS runtime 原型
    runtime_bridge.hpp
  rtl/
    FPGATop.scala            Chisel/RTL 顶层规划或 wrapper
  docs/
    memory-map.md            地址映射
    trace-format.md          trace 格式
    host-mmio.md             host-assisted MMIO 协议
```

`npc/chisel` 侧建议新增：

```text
CPUCore.scala                纯 CPU core，不包含 DPI slave
SimTop.scala                 Verilator/DPI 仿真顶层
FPGATop.scala                FPGA 运行顶层
ElaborateFPGA.scala          生成 FPGA Verilog
```

---

## 13. 关键原则总结

```text
NEMU 留在 host 上，不进 FPGA。
HLS/RTL runtime 只做运行环境，不解释指令。
CPU 普通内存访问必须在 FPGA 本地完成。
复杂外设可以交给 host 模拟。
第一版只实现 putch/halt/exit_code。
先跑通，再扩展 trace、timer、host queue。
```

这个方向的核心不是“重写 NEMU”，而是“复刻 NEMU 中对硬件 CPU 有用的运行环境思想”。

最终目标是让 U55C 上的 Chisel CPU 像当前 Verilator NPC 一样，可以被 host 程序加载镜像、启动、观察输出、判断退出，并逐步具备可调试、可比对、可扩展的运行能力。
