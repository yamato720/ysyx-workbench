# ZCU102 Runtime 设计指南

本文档用于规划将当前仓库中的 RISC-V CPU / `ysyxSoC` 放到 AMD ZCU102 上运行所需的板级运行环境。它和 `u55c-runtime` 的定位类似：不是把 NEMU 搬进 FPGA，而是在 FPGA/MPSoC 上重做一套可以加载程序、启动 CPU、观察输出和判断退出的轻量运行平台。

ZCU102 和 U55C 的关键差异是：ZCU102 是 Zynq UltraScale+ MPSoC 板卡，带 Processing System(PS) 和 Programmable Logic(PL)。因此推荐把 PS 当作天然 host/controller，把 `ysyxSoC` 或 CPU 子系统放在 PL 中运行。

参考资料：

- AMD ZCU102 Evaluation Kit product page: https://www.amd.com/en/products/adaptive-socs-and-fpgas/evaluation-boards/ek-u1-zcu102-g.html
- AMD UG1182 ZCU102 Evaluation Board User Guide: https://docs.amd.com/v/u/en-US/ug1182-zcu102-eval-bd

---

## 1. 目标

第一阶段目标不是完整 Linux SoC，而是让一个最小 RISC-V 程序在 ZCU102 的 PL 中跑起来：

```text
PS side
  - bare-metal or Linux control program
  - load image.bin into BRAM or PS DDR window
  - release PL RISC-V CPU reset
  - poll status registers
  - collect putch / exit_code / simple trace

PL side
  - ysyx CPU or ysyxSoC-derived wrapper
  - local guest memory or PS DDR AXI window
  - simple MMIO: putch, halt, exit_code, timer
  - optional trace buffer
```

最终目标是让 ZCU102 上的 PL RISC-V CPU 像当前 Verilator/NPC 一样，可以被加载镜像、启动、输出字符、报告退出码，并逐步具备 trace 和离线 DiffTest 能力。

---

## 2. 与 U55C Runtime 的差异

| 项目 | U55C | ZCU102 |
| --- | --- | --- |
| Host 通道 | PCIe / XRT | PS bare-metal、PS Linux、JTAG、UART |
| 大容量内存 | HBM | PS DDR4，或 PL DDR4 + MIG |
| 控制平面 | 服务器 host 程序 | PS 侧程序或 Vivado hw_server |
| 第一版推荐内存 | BRAM 或 HBM | BRAM，随后 PS DDR |
| 板级复杂度 | Alveo shell / kernel | ZynqMP PS 配置、PS-PL clock/reset/AXI |

ZCU102 的优势是 PS 可以直接承担 host runtime，不需要 PCIe/XRT host 程序。板上还有 PS 侧 DDR4 和 PL 侧 DDR4 component 两类内存资源。劣势是 PL 设计必须认真处理 PS-PL AXI、MIG、时钟、复位、地址空间和 Vivado block design。

---

## 3. `ysyxSoC` 带来的变化

现在仓库里已经有 `ysyxSoC/`，这明显提高了可行性，但不能把它当成“直接能上 ZCU102 的 bitstream”。

### 3.1 有利条件

- `ysyxSoC/src/CPU.scala` 已经把 CPU 封成 AXI4 master。
- `ysyxSoC/src/SoC.scala` 已有 AXI/APB 互连、UART、SPI、GPIO、PSRAM、SDRAM、VGA 等外设接入。
- `ysyxSoC/spec/cpu-interface.md` 已定义 D 阶段之后的 AXI CPU 接口。
- `ysyxSoC/Makefile` 已有 Chisel 到 `ysyxSoCFull.v` 的生成流程。
- `ysyxSoC/ready-to-run/D-stage/` 提供了一个可参考的 ready-to-run 形态。

### 3.2 需要改造的地方

- 当前 `ysyxSoCTop` 把 `externalPins` 直接接成 `DontCare`，不能作为 ZCU102 板级顶层。
- `ysyxSoC/src/device/MROM.scala` 里的 `MROMHelper` 使用 DPI-C，不可综合，必须替换成 BRAM/ROM 初始化或 AXI 可访问存储。
- 现有 PSRAM/SDRAM/flash blackbox 更像教学板或仿真环境接口，不等价于 ZCU102 板上实际 DDR/flash 连接。
- ZCU102 的主内存可以走两条路线：PS DDR 经 PS-PL AXI 访问，或 PL DDR4 经 MIG 访问；两者都不能直接复用现有离散 SDRAM 控制器。
- `ysyx_00000000` 是 BlackBox，Vivado 工程必须加入真实 CPU Verilog。
- UART/VGA/GPIO/PS2 等外设 pinout 需要按 ZCU102 原理图和 XDC 重新约束，不能复用教学板假设。

结论：`ysyxSoC` 适合作为 SoC 集成基础，但第一版上 ZCU102 建议抽出 CPU master + 最小 runtime 外设，先跑 BRAM MVP；等 CPU/时钟/复位稳定后再逐步合入完整 `ysyxSoC` 外设。

---

## 4. 推荐总体架构

```text
+--------------------------------------------------------+
| ZCU102 PS                                               |
|--------------------------------------------------------|
| bare-metal app or Linux user program                    |
|  - write image to BRAM/DDR window                       |
|  - configure boot_pc / reset / run                      |
|  - poll halt / exit_code                                |
|  - read putch FIFO / trace buffer                       |
+--------------------------+-----------------------------+
                           |
                           | AXI GP / HP / HPC
                           |
+--------------------------v-----------------------------+
| ZCU102 PL                                               |
|--------------------------------------------------------|
| ZCU102RuntimeTop                                        |
|  +----------------------+                              |
|  | ysyx CPU or ysyxSoC  |                              |
|  | AXI4 master          |                              |
|  +----------+-----------+                              |
|             |                                          |
|  +----------v-----------+                              |
|  | memory/MMIO fabric   |                              |
|  | - BRAM or PS DDR     |                              |
|  | - putch/halt/status  |                              |
|  | - timer              |                              |
|  | - trace buffer       |                              |
|  +----------------------+                              |
+--------------------------------------------------------+
```

---

## 5. 阶段路线

### 阶段 0：生成可综合 RTL

- 跑通 `ysyxSoC/Makefile verilog`。
- 确认生成的 Verilog 不包含 DPI-C。
- 明确真实 CPU Verilog 文件来源。
- 建立 `ZCU102RuntimeTop`，不要直接使用 `ysyxSoCTop`。

### 阶段 1：PL BRAM MVP

- CPU AXI master 访问一块 AXI BRAM 或 Chisel/Verilog BRAM。
- 程序镜像通过 Vivado/Vitis 初始化或 PS AXI 写入。
- MMIO 只实现 `PUTCH`、`HALT`、`EXIT_CODE`。
- PS 侧 bare-metal 程序轮询状态寄存器。

这是最值得优先做的版本，风险最低。

### 阶段 2：板载 DDR 版本

- ZynqMP PS 配置 HP/HPC AXI slave port。
- PL CPU AXI master 通过 AXI interconnect 访问 PS DDR。
- PS 程序把 guest image 写入 DDR 约定地址。
- 处理 32-bit CPU AXI 与 PS 端口较宽 AXI 之间的数据宽度转换。
- 或者接 PL DDR4 MIG，把 guest memory 放在 PL DDR；这条路线更接近纯 PL SoC，但 MIG bring-up 和约束更重。

这一步才适合跑更大的 AM 程序。

### 阶段 3：引入更多 `ysyxSoC` 外设

- 用 ZCU102 板载 UART 替代教学板 UART pin 假设。
- 评估 VGA/HDMI、GPIO、SPI flash 是否需要映射。
- 若保留 `ysyxSoC` 地址图，新增板级适配层，不直接修改 SoC 内核。

### 阶段 4：Trace 与离线 DiffTest

- CPU commit trace 写入 BRAM/DDR ring buffer。
- PS 侧导出 trace。
- Host PC 或 PS Linux 上跑 NEMU 做离线比对。

---

## 6. 目录说明

```text
zcu102-runtime/
  README.md                    总体设计与路线
  docs/
    feasibility.md             可行性分析
    memory-map.md              建议地址映射
    ysyxSoC-adaptation.md      ysyxSoC 适配点
    bringup-plan.md            上板 bring-up checklist
  rtl/
    README.md                  PL RTL 顶层规划
  vivado/
    README.md                  Vivado 工程策略
    create_project.tcl         工程脚本骨架
  constraints/
    README.md                  XDC 约束策略
    zcu102_runtime.xdc         占位约束文件
  sw/
    README.md                  PS 侧 runtime 软件规划
```

---

## 7. 当前结论

ZCU102 路线可行，而且有 `ysyxSoC` 后比从裸 CPU 做板级互连更现实。但第一版不应追求“一次把完整 ysyxSoC 外设全接上板”。最稳妥的路径是：

```text
CPU AXI master
  -> BRAM memory + simple MMIO
  -> PS-controlled load/run/status
  -> PS DDR memory
  -> ysyxSoC peripheral adaptation
```

先证明 CPU 能在真实 PL 时钟下取指、访存和退出，再逐步替换内存和外设。
