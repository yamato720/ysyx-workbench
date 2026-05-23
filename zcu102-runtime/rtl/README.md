# RTL 目录

这里放 ZCU102 PL 侧顶层和 runtime RTL/Chisel wrapper。

计划文件：

```text
ZCU102RuntimeTop.scala       runtime 逻辑顶层
ZCU102BoardTop.sv           Vivado 板级 wrapper，或由 block design 生成
SimpleMmio.scala            putch/halt/exit_code/control 寄存器
GuestMemory.scala           BRAM/PS DDR 访问抽象
TraceBuffer.scala           trace ring buffer
```

第一版建议只实现：

```text
CPU AXI master -> BRAM + SimpleMmio
```

不要第一版就接完整 `ysyxSoCFull`。先把 CPU 取指、访存、halt 跑通，再把 `ysyxSoC` 外设逐个迁入。

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
