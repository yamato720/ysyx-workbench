# ZCU102 可行性分析

## 1. 总体判断

可行，但应该分阶段推进。

`ysyxSoC` 的出现把问题从“裸 CPU 如何接板级资源”变成了“已有 AXI SoC 如何适配 ZynqMP 板级环境”。这是好事，因为 AXI/APB 互连和外设抽象已经存在；也是风险点，因为当前 SoC 里包含仿真/教学板假设，不能原样映射到 ZCU102。

## 2. 最小可行路径

优先做：

```text
ysyx CPU AXI master
  -> AXI BRAM / local SRAM
  -> simple MMIO
  -> PS AXI-Lite control registers
```

理由：

- 不依赖 PS DDR 初始化细节；
- 不依赖复杂外设 pinout；
- 不需要先调通完整 `ysyxSoC`；
- 可以最快验证 CPU 在 FPGA 上的时序、复位、取指和访存。

## 3. `ysyxSoC` 对可行性的帮助

`ysyxSoC` 已经提供：

- AXI4 CPU master 接口；
- AXI4/APB 地址解码和互连；
- UART/GPIO/SPI/PSRAM/SDRAM/VGA 等设备模型；
- Chisel/RocketChip 依赖管理；
- `Makefile verilog` 生成流程。

这些可以降低 SoC 集成工作量。尤其是 `CPU.scala` 已经把学生 CPU 包成 AXI master，这是上 ZCU102 最关键的接口。

ZCU102 的 PS ARM 还能承担仿真 host 的一部分职责：加载程序、控制 reset/run、读取 trace，并在 PS Linux 或 host PC 上运行 NEMU 做参考比对。这让调试体验比纯 FPGA 板更好。

## 4. `ysyxSoC` 不能直接解决的问题

### 4.1 板级内存不匹配

当前 `ysyxSoC` 有 PSRAM/SDRAM 控制器，但 ZCU102 的板级大内存资源不同：一类是 PS 侧 DDR4，PL 可通过 ZynqMP PS 的 AXI HP/HPC 端口访问；另一类是 PL 侧 DDR4 component，需要 Vivado MIG/DDR4 控制器。两者都不是现有教学式 SDRAM 控制器能直接驱动的对象。

建议：

- 第一版用 BRAM；
- 第二版把 guest memory 放到 PS DDR，或在资源/时序允许时接 PL DDR4 MIG；
- 不优先复用 `ysyxSoC` 的 SDRAM/PSRAM 作为 ZCU102 主存。

### 4.2 DPI-C 不可综合

`ysyxSoC/src/device/MROM.scala` 中 `MROMHelper` 使用 DPI-C。任何包含 DPI-C 的生成结果都不能进入 Vivado 综合。

建议：

- MROM 改为可综合 ROM；
- 或把 boot ROM 内容转成 `$readmemh` 初始化；
- 或直接让 PS 侧写 BRAM/DDR，并从固定 boot PC 启动。

### 4.3 外设 pinout 不匹配

UART、GPIO、VGA、PS2、SPI flash 都需要按 ZCU102 实际连接重新约束。教学板或仿真 blackbox 的接口不能直接认为等价。

建议：

- MVP 只暴露时钟、复位、AXI 控制、UART TX/RX；
- 其余外设先从地址图中屏蔽或保持未接。

### 4.4 时钟和复位需要板级化

ZCU102 设计中常见做法是由 PS 输出 PL clock/reset，或者使用板上时钟经过 clocking wizard。`ysyxSoC` 当前顶层没有 ZCU102 板级 clock/reset 规划。

建议：

- 第一版用 PS FCLK 作为 PL 时钟；
- reset 由 PS GPIO/AXI control 或 processor system reset IP 产生；
- CPU reset 和 runtime reset 分开。

## 5. 推荐实施顺序

| 阶段 | 目标 | 风险 |
| --- | --- | --- |
| 0 | 生成无 DPI 的 RTL | 中 |
| 1 | BRAM + simple MMIO 跑 hello | 低 |
| 2 | PS bare-metal 控制 load/run/status | 中 |
| 3 | CPU 访问 PS DDR | 中高 |
| 4 | 接入完整 `ysyxSoC` 地址图 | 高 |
| 5 | trace / timer / interrupt / 更复杂 AM 程序 | 高 |

## 6. 结论

有 `ysyxSoC` 后，ZCU102 不是“能不能做”的问题，而是“先收敛到哪个最小目标”。推荐优先交付一个小而硬的 BRAM MVP，再把内存换成 PS DDR，最后才扩大到完整 SoC 外设。

PS ARM 应作为调试控制器和 trace/NEMU 协作端使用。不要把它放到普通访存高频路径里，否则会把硬件运行退化成慢速协同仿真。
