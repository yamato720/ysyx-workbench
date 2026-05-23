# RTL 目录

这里放 ZCU102 PL 侧顶层和 runtime RTL/Chisel wrapper。

计划文件：

```text
../chisel/src/main/scala/ZCU102NPCDebugger.scala
                             Chisel 版 NPC 调试控制寄存器块
ZCU102RuntimeTop.scala       runtime 逻辑顶层
ZCU102BoardTop.sv           Vivado 板级 wrapper，或由 block design 生成
ZCU102NPCDebugger.sv        参考 SystemVerilog 形态，后续以 Chisel 生成版为准
SimpleMmio.scala            putch/halt/exit_code/control 寄存器
GuestMemory.scala           BRAM/PS DDR 访问抽象
TraceBuffer.scala           trace ring buffer
npc-debugger-interface.md   NPC 接入 ZCU102 调试器的接口草案
```

第一版建议只实现：

```text
CPU AXI master -> BRAM + SimpleMmio
```

不要第一版就接完整 `ysyxSoCFull`。先把 CPU 取指、访存、halt 跑通，再把 `ysyxSoC` 外设逐个迁入。

NPC 专用第一版见：

```text
../docs/npc-zcu102-debugger.md
npc-debugger-interface.md
ZCU102NPCDebugger.sv
```

Chisel 版 `ZCU102NPCDebugger` 当前只实现 PS 可访问的 AXI-Lite 控制/状态寄存器，以及 NPC 侧 run/reset/single-step/trace 控制信号。它不实例化 CPU、不实现 guest memory、不实现 trace RAM；这些应由后续 board/runtime top 组合。`ZCU102NPCDebugger.sv` 暂时保留为参考 RTL，长期应由 Chisel 生成。

生成入口在 `zcu102-runtime/` 下：

```bash
make chisel
make check
```

## 关键接口

```text
clock
reset
ps_axi_control
cpu_axi_master
optional ps_ddr_axi
uart_tx / uart_rx
debug_status
```

## 综合注意事项

- 不允许 DPI-C。
- 不允许 Verilator-only helper。
- CPU BlackBox 的真实 Verilog 必须加入 Vivado sources。
- 所有跨时钟路径必须显式 clock converter 或 CDC FIFO。
- runtime MMIO 先用单时钟域。
