# Vivado IP 优先集成方案

ZCU102 runtime 第一版应尽量使用 AMD/Xilinx Vivado IP。手写 RTL 只做 NPC 专用 glue、状态寄存器和少量协议适配，避免重复造板级基础设施。

## 1. 原则

- PS、时钟、复位、AXI interconnect、BRAM、DDR、ILA 都优先用 Vivado IP。
- 手写 RTL 不实现通用 AXI crossbar、BRAM controller、DDR controller。
- NPC CPU 只暴露标准 AXI4-Lite/AXI4 接口和 debug/trace 信号。
- `zcu102-runtime` 负责把 NPC 接到 IP 组成的平台上。

## 2. 第一版 IP 清单

| 功能 | 推荐 IP | 用途 |
| --- | --- | --- |
| PS/ARM 控制端 | Zynq UltraScale+ MPSoC | A53/R5、FCLK、reset、AXI master/slave |
| 控制总线互连 | AXI SmartConnect | PS 访问调试寄存器、BRAM aperture |
| PL 复位 | Processor System Reset | 生成 PL reset/resetn |
| guest BRAM | AXI BRAM Controller + Block Memory Generator | 第一版 guest memory |
| NPC 访存互连 | AXI SmartConnect | NPC i_axi/d_axi 到 BRAM/MMIO |
| 数据宽度转换 | AXI Data Width Converter | 32/64/128-bit AXI 宽度适配 |
| 时钟域转换 | AXI Clock Converter | 跨 FCLK/MIG UI clock 时使用 |
| MMIO FIFO | AXI FIFO MM S 或 AXI Stream FIFO | 可选 putch/trace FIFO |
| Timer | AXI Timer | 可选 PS/NPC 可见 timer |
| GPIO/status | AXI GPIO | 可选 debug GPIO/LED |
| Trace memory | AXI BRAM Controller 或 AXI DataMover + DDR | trace ring buffer |
| DDR | ZynqMP PS DDR 或 DDR4 MIG | 第二阶段 guest memory |
| Debug probe | ILA + VIO | AXI、reset、commit、halt 调试 |

## 3. 手写 RTL 清单

第一版保留：

```text
ZCU102NPCDebugger.sv
  - PS-visible control/status regs
  - NPC run/reset/single-step/trace control
  - last commit snapshot
  - putch/halt/exit latch
  - irq_to_ps

NPCFPGACore wrapper
  - 从 npc/chisel 导出的 CPU wrapper
  - 暴露 i_axi/d_axi
  - 暴露 commit/debug bundle

Simple MMIO decode glue
  - 0xa000_0000 PUTCH
  - 0xa000_0004 HALT
  - 0xa000_0008 EXIT_CODE
```

不手写：

```text
AXI crossbar
BRAM controller
DDR controller
PS bridge
clock/reset infrastructure
ILA/debug capture fabric
```

## 4. 推荐 block design

### 4.1 BRAM MVP

```text
ZynqMP PS
  M_AXI_HPM -> SmartConnect(control)
    -> ZCU102NPCDebugger AXI-Lite slave
    -> AXI BRAM Controller PS aperture

NPCFPGACore
  i_axi -> SmartConnect(cpu_mem) -> AXI BRAM Controller
  d_axi -> SmartConnect(cpu_mem) -> AXI BRAM Controller
                              \-> Simple MMIO glue -> ZCU102NPCDebugger sideband

PS FCLK -> all PL logic
Processor System Reset -> resetn
ILA probes -> NPC AXI, commit, control/status
```

### 4.2 PS DDR

```text
NPC i_axi/d_axi
  -> SmartConnect
  -> AXI Data Width Converter
  -> ZynqMP PS S_AXI_HP/HPC
  -> PS DDR
```

PS 软件负责预留 DDR 并加载 guest image。

### 4.3 PL DDR4 MIG

```text
NPC i_axi/d_axi
  -> SmartConnect
  -> AXI Data Width Converter
  -> DDR4 MIG user AXI
  -> PL DDR4
```

如果 PS 也要初始化 PL DDR，需要再给 PS AXI master 接入同一个 SmartConnect。

## 5. 对 NPC 接口的要求

为了最大化 IP 复用，NPC export top 应尽量靠近标准 AXI：

- instruction/data master 使用 AXI4-Lite 或 AXI4；
- 如果保持 AXI4-Lite，BRAM MVP 最简单；
- 如果后续要高效访问 DDR，建议升级或包装成 AXI4 burst master；
- 复位使用高电平内部 reset 或统一 wrapper 转换；
- debug/commit 信号保持 sideband，不塞进 AXI payload。

## 6. Vivado 工程策略

- `create_project.tcl` 只保留可复现骨架。
- 复杂 block design 可以先手工搭建，再导出 Tcl。
- 后续生成 `create_bd.tcl`，由 Tcl 创建 IP、连接 AXI、配置地址。
- IP output products 不提交。
- XCI 可按团队习惯选择提交或由 Tcl 生成；第一版建议 Tcl 生成。
